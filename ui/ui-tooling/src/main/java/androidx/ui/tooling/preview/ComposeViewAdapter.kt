/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.ui.tooling.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.AtomicReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Providers
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.compose.ui.platform.AnimationClockAmbient
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.FontLoaderAmbient
import androidx.ui.tooling.Group
import androidx.ui.tooling.Inspectable
import androidx.ui.tooling.SlotTableRecord
import androidx.ui.tooling.SourceLocation
import androidx.ui.tooling.asTree
import androidx.ui.tooling.preview.animation.PreviewAnimationClock
import androidx.compose.ui.unit.IntBounds
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import kotlin.reflect.KClass

const val TOOLS_NS_URI = "http://schemas.android.com/tools"

/**
 * Class containing the minimum information needed by the Preview to map components to the
 * source code and render boundaries.
 *
 * @suppress
 */
data class ViewInfo(
    val fileName: String,
    val lineNumber: Int,
    val bounds: IntBounds,
    val location: SourceLocation?,
    val children: List<ViewInfo>
) {
    fun hasBounds(): Boolean = bounds.bottom != 0 && bounds.right != 0

    fun allChildren(): List<ViewInfo> =
        children + children.flatMap { it.allChildren() }

    override fun toString(): String =
        """($fileName:$lineNumber,
            |bounds=(top=${bounds.top}, left=${bounds.left},
            |location=${location?.let { "(${it.offset}L${it.length}"} ?: "<none>" }
            |bottom=${bounds.bottom}, right=${bounds.right}),
            |childrenCount=${children.size})""".trimMargin()
}

/**
 * View adapter that renders a `@Composable`. The `@Composable` is found by
 * reading the `tools:composableName` attribute that contains the FQN. Additional attributes can
 * be used to customize the behaviour of this view:
 *  - `tools:parameterProviderClass`: FQN of the [PreviewParameterProvider] to be instantiated by
 *  the [ComposeViewAdapter] that will be used as source for the `@Composable` parameters.
 *  - `tools:parameterProviderIndex`: The index within the [PreviewParameterProvider] of the
 *  value to be used in this particular instance.
 *  - `tools:paintBounds`: If true, the component boundaries will be painted. This is only meant
 *  for debugging purposes.
 *  - `tools:printViewInfos`: If true, the [ComposeViewAdapter] will log the tree of [ViewInfo]
 *  to logcat for debugging.
 *  - `tools:animationClockStartTime`: When set, the [AnimationClockAmbient] will provide a
 *  [PreviewAnimationClock] using this value as start time. The clock will control the animations
 *  in the [ComposeViewAdapter] context.
 *
 * @suppress
 */
@Suppress("unused")
internal class ComposeViewAdapter : FrameLayout {
    private val TAG = "ComposeViewAdapter"

    /**
     * When enabled, generate and cache [ViewInfo] tree that can be inspected by the Preview
     * to map components to source code.
     */
    private var debugViewInfos = false
    /**
     * When enabled, paint the boundaries generated by layout nodes.
     */
    private var debugPaintBounds = false
    internal var viewInfos: List<ViewInfo> = emptyList()
    private val slotTableRecord = SlotTableRecord.create()

    /**
     * Saved exception from the last composition. Since we can not handle the exception during the
     * composition, we save it and throw it during onLayout, this allows Studio to catch it and
     * display it to the user.
     */
    private val delayedException = AtomicReference<Throwable?>(null)

    private val debugBoundsPaint = Paint().apply {
        pathEffect = DashPathEffect(floatArrayOf(5f, 10f, 15f, 20f), 0f)
        style = Paint.Style.STROKE
        color = Color.Red.toArgb()
    }

    private var composition: Composition? = null

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(attrs)
    }

    private fun walkTable(viewInfo: ViewInfo, indent: Int = 0) {
        Log.d(TAG, ("|  ".repeat(indent)) + "|-$viewInfo")
        viewInfo.children.forEach { walkTable(it, indent + 1) }
    }

    private val Group.fileName: String
        get() = (key as? String)?.substringBefore(":") ?: ""

    private val Group.lineNumber: Int
        get() = ((key as? String)?.substringAfter(":") ?: "-1").toInt()

    /**
     * Returns true if this [Group] has no source position information
     */
    private fun Group.hasNullSourcePosition(): Boolean =
        fileName.isEmpty() && lineNumber == -1

    /**
     * Returns true if this [Group] has no source position information and no children
     */
    private fun Group.isNullGroup(): Boolean =
        hasNullSourcePosition() && children.isEmpty()

    private fun Group.toViewInfo(): ViewInfo {
        if (children.size == 1 && hasNullSourcePosition()) {
            // There is no useful information in this intermediate node, remove.
            return children.single().toViewInfo()
        }

        val childrenViewInfo = children
            .filter { !it.isNullGroup() }
            .map { it.toViewInfo() }

        // TODO: Use group names instead of indexing once it's supported
        return ViewInfo(
            location?.sourceFile ?: "",
            location?.lineNumber ?: -1,
            box,
            location,
            childrenViewInfo
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        delayedException.getAndSet(null)?.let { exception ->
            // There was a pending exception. Throw it here since Studio will catch it and show
            // it to the user.
            throw exception
        }
        viewInfos = slotTableRecord.store.map { it.asTree() }.map { it.toViewInfo() }.toList()

        if (debugViewInfos) {
            viewInfos.forEach {
                walkTable(it)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas?) {
        super.dispatchDraw(canvas)

        if (!debugPaintBounds) {
            return
        }

        viewInfos
            .flatMap { listOf(it) + it.allChildren() }
            .forEach {
                if (it.hasBounds()) {
                    canvas?.apply {
                        val pxBounds = android.graphics.Rect(
                            it.bounds.left,
                            it.bounds.top,
                            it.bounds.right,
                            it.bounds.bottom
                        )
                        drawRect(pxBounds, debugBoundsPaint)
                    }
                }
            }
    }

    /**
     * Clock that controls the animations defined in the context of this [ComposeViewAdapter].
     *
     * @suppress
     */
    private lateinit var clock: PreviewAnimationClock

    /**
     * Wraps a given [Preview] method an does any necessary setup.
     */
    @Composable
    private fun WrapPreview(children: @Composable () -> Unit) {
        // We need to replace the FontResourceLoader to avoid using ResourcesCompat.
        // ResourcesCompat can not load fonts within Layoutlib and, since Layoutlib always runs
        // the latest version, we do not need it.
        Providers(FontLoaderAmbient provides LayoutlibFontResourceLoader(context)) {
            Inspectable(slotTableRecord, children)
        }
    }

    /**
     * Initializes the adapter and populates it with the given [Preview] composable.
     * @param className name of the class containing the preview function
     * @param methodName `@Preview` method name
     * @param parameterProvider [KClass] for the [PreviewParameterProvider] to be used as
     * parameter input for this call. If null, no parameters will be passed to the composable.
     * @param parameterProviderIndex when [parameterProvider] is not null, this index will
     * reference the element in the [Sequence] to be used as parameter.
     * @param debugPaintBounds if true, the view will paint the boundaries around the layout
     * elements.
     * @param debugViewInfos if true, it will generate the [ViewInfo] structures and will log it.
     * @param animationClockStartTime if positive, the [AnimationClockAmbient] will provide
     * [clock] instead of the default clock, setting this value as the clock's initial time.
     */
    @VisibleForTesting
    internal fun init(
        className: String,
        methodName: String,
        parameterProvider: KClass<out PreviewParameterProvider<*>>? = null,
        parameterProviderIndex: Int = 0,
        debugPaintBounds: Boolean = false,
        debugViewInfos: Boolean = false,
        animationClockStartTime: Long = -1
    ) {
        ViewTreeLifecycleOwner.set(this, FakeSavedStateRegistryOwnerOwner)
        ViewTreeSavedStateRegistryOwner.set(this, FakeSavedStateRegistryOwnerOwner)
        ViewTreeViewModelStoreOwner.set(this, FakeViewModelStoreOwner)
        this.debugPaintBounds = debugPaintBounds
        this.debugViewInfos = debugViewInfos

        composition = setContent(Recomposer.current()) {
            WrapPreview {
                val composer = currentComposer
                // We need to delay the reflection instantiation of the class until we are in the
                // composable to ensure all the right initialization has happened and the Composable
                // class loads correctly.
                val composable = {
                    try {
                        invokeComposableViaReflection(
                            className,
                            methodName,
                            composer,
                            *getPreviewProviderParameters(parameterProvider, parameterProviderIndex)
                        )
                    } catch (t: Throwable) {
                        // If there is an exception, store it for later but do not catch it so
                        // compose can handle it and dispose correctly.
                        var exception: Throwable = t
                        // Find the root cause and use that for the delayedException.
                        while (exception is ReflectiveOperationException) {
                            exception = exception.cause ?: break
                        }
                        delayedException.set(exception)
                        throw t
                    }
                }
                if (animationClockStartTime >= 0) {
                    // Provide a custom clock when animation inspection is enabled, i.e. when a
                    // valid `animationClockStartTime` is passed. This clock will control the
                    // animations defined in this `ComposeViewAdapter` from Android Studio.
                    clock = PreviewAnimationClock(animationClockStartTime)
                    Providers(AnimationClockAmbient provides clock) {
                        composable()
                    }
                } else {
                    composable()
                }
            }
        }
    }

    /**
     * Disposes the Compose elements allocated during [init]
     */
    internal fun dispose() {
        composition?.dispose()
        composition = null
        if (::clock.isInitialized) {
            clock.dispose()
        }
    }

    private fun init(attrs: AttributeSet) {
        val composableName = attrs.getAttributeValue(TOOLS_NS_URI, "composableName") ?: return
        val className = composableName.substringBeforeLast('.')
        val methodName = composableName.substringAfterLast('.')
        val parameterProviderIndex = attrs.getAttributeIntValue(
            TOOLS_NS_URI,
            "parameterProviderIndex", 0
        )
        val parameterProviderClass = attrs.getAttributeValue(TOOLS_NS_URI, "parameterProviderClass")
            ?.asPreviewProviderClass()

        val animationClockStartTime = try {
            attrs.getAttributeValue(TOOLS_NS_URI, "animationClockStartTime").toLong()
        } catch (e: Exception) {
            -1L
        }

        init(
            className = className,
            methodName = methodName,
            parameterProvider = parameterProviderClass,
            parameterProviderIndex = parameterProviderIndex,
            debugPaintBounds = attrs.getAttributeBooleanValue(
                TOOLS_NS_URI,
                "paintBounds",
                debugPaintBounds
            ),
            debugViewInfos = attrs.getAttributeBooleanValue(
                TOOLS_NS_URI,
                "printViewInfos",
                debugViewInfos
            ),
            animationClockStartTime = animationClockStartTime
        )
    }

    private val FakeSavedStateRegistryOwnerOwner = object : SavedStateRegistryOwner {
        private val lifecycle = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this).apply {
            performRestore(Bundle())
        }

        init {
            lifecycle.currentState = Lifecycle.State.RESUMED
        }

        override fun getSavedStateRegistry(): SavedStateRegistry = controller.savedStateRegistry
        override fun getLifecycle(): Lifecycle = lifecycle
    }

    private val FakeViewModelStoreOwner = ViewModelStoreOwner {
        throw IllegalStateException("ViewModels creation is not supported in Preview")
    }
}
