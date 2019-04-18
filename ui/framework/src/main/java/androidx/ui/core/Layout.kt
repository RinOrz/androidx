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

package androidx.ui.core

import com.google.r4a.Ambient
import com.google.r4a.Children
import com.google.r4a.Composable
import com.google.r4a.R4a
import com.google.r4a.ambient
import com.google.r4a.composer
import com.google.r4a.memo
import com.google.r4a.onCommit
import com.google.r4a.unaryPlus

internal typealias LayoutBlock =
        LayoutBlockReceiver.(List<Measurable>, Constraints) -> Unit
internal typealias IntrinsicMeasurementBlock =
        IntrinsicMeasurementsReceiver.(List<Measurable>, IntPx) -> IntPx
internal val LayoutBlockStub: LayoutBlock = { _, _ -> }
internal val IntrinsicMeasurementBlockStub: IntrinsicMeasurementBlock = { _, _ -> 0.ipx }

internal class ComplexLayoutState(
    internal var layoutBlock: LayoutBlock = LayoutBlockStub,
    internal var minIntrinsicWidthBlock: IntrinsicMeasurementBlock = IntrinsicMeasurementBlockStub,
    internal var maxIntrinsicWidthBlock: IntrinsicMeasurementBlock = IntrinsicMeasurementBlockStub,
    internal var minIntrinsicHeightBlock: IntrinsicMeasurementBlock = IntrinsicMeasurementBlockStub,
    internal var maxIntrinsicHeightBlock: IntrinsicMeasurementBlock = IntrinsicMeasurementBlockStub,
    internal val density: Density
) : Measurable, Placeable(), MeasurableLayout {
    override val parentData: Any?
        get() = layoutNode.parentData

    internal var positioningBlock: PositioningBlockReceiver.() -> Unit = {}

    internal val layoutBlockReceiver = LayoutBlockReceiver(this)
    internal val intrinsicMeasurementsReceiver =
        IntrinsicMeasurementsReceiver(this)
    internal val positioningBlockReceiver = PositioningBlockReceiver()

    internal val layoutNodeRef = Ref<LayoutNode>()
    internal val layoutNode: LayoutNode
        get() = layoutNodeRef.value!!

    internal val childrenMeasurables: List<Measurable> get() =
        ComplexLayoutStateMeasurablesList(layoutNode.childrenLayouts().map { it as Measurable })

    internal val onChildPositioned = mutableListOf<(LayoutCoordinates) -> Unit>()
    internal val onPositioned = mutableListOf<(LayoutCoordinates) -> Unit>()

    override fun callMeasure(constraints: Constraints) { measure(constraints) }
    override fun callLayout() {
        placeChildren()
    }

    fun measure(constraints: Constraints): Placeable {
        if (layoutNode.constraints == constraints && !layoutNode.needsRemeasure) {
            layoutNode.resize(layoutNode.width, layoutNode.height)
            return this // we're already measured to this size, don't do anything
        }
        layoutNode.startMeasure()
        layoutNode.constraints = constraints
        layoutBlockReceiver.layoutBlock(childrenMeasurables, constraints)
        layoutNode.endMeasure()
        return this
    }

    fun minIntrinsicWidth(h: IntPx) =
        minIntrinsicWidthBlock(intrinsicMeasurementsReceiver, childrenMeasurables, h)

    fun maxIntrinsicWidth(h: IntPx) =
        maxIntrinsicWidthBlock(intrinsicMeasurementsReceiver, childrenMeasurables, h)

    fun minIntrinsicHeight(w: IntPx) =
        minIntrinsicHeightBlock(intrinsicMeasurementsReceiver, childrenMeasurables, w)

    fun maxIntrinsicHeight(w: IntPx) =
        maxIntrinsicHeightBlock(intrinsicMeasurementsReceiver, childrenMeasurables, w)

    internal fun placeChildren() {
        layoutNode.startLayout()
        positioningBlockReceiver.apply { positioningBlock() }
        dispatchOnPositionedCallbacks()
        layoutNode.endLayout()
    }

    private fun dispatchOnPositionedCallbacks() {
        // There are two types of callbacks:
        // a) when the Layout is positioned - `onPositioned`
        // b) when the child of the Layout is positioned - `onChildPositioned`
        // To create LayoutNodeCoordinates only once here we will call callbacks from
        // both `onPositioned` and our parent Layout's `onChildPositioned`.
        val parentLayout = layoutNode.parentLayoutNode?.layout as ComplexLayoutState?
        val parentOnChildPositioned = parentLayout?.onChildPositioned
        if (onPositioned.isNotEmpty() || !parentOnChildPositioned.isNullOrEmpty()) {
            val coordinates = LayoutNodeCoordinates(layoutNode)
            onPositioned.forEach { it.invoke(coordinates) }
            parentOnChildPositioned?.forEach { it.invoke(coordinates) }
        }
    }

    internal fun resize(width: IntPx, height: IntPx) {
        layoutNode.resize(width, height)
    }

    private fun moveTo(x: IntPx, y: IntPx) {
        layoutNode.moveTo(x, y)
    }

    override val width: IntPx get() = layoutNode.width
    override val height: IntPx get() = layoutNode.height
    override fun place(x: IntPx, y: IntPx) {
        moveTo(x, y)
        placeChildren()
    }
}

internal class ComplexLayoutStateMeasurablesList(
    internal val measurables: List<Measurable>
) : List<Measurable> by (measurables.filter { it.parentData !is ChildrenEndParentData })

/**
 * [ComplexMeasureBox] which composes its children during its own composition, so the tree of
 * component nodes will be built in one composition pass. Since it composes its children
 * non-lazily, the children composable has to be known beforehand, which is the case for most
 * use cases.
 * TODO(popam): improve this doc when the component is finalized
 */
@Composable
fun ComplexLayout(
    layoutBlock: LayoutBlock,
    minIntrinsicWidthBlock: IntrinsicMeasurementBlock,
    maxIntrinsicWidthBlock: IntrinsicMeasurementBlock,
    minIntrinsicHeightBlock: IntrinsicMeasurementBlock,
    maxIntrinsicHeightBlock: IntrinsicMeasurementBlock,
    @Children children: () -> Unit
) {
    val density = +ambientDensity()
    val layoutState = +memo { ComplexLayoutState(density = density) }
    layoutState.apply {
        this.layoutBlock = layoutBlock
        this.minIntrinsicWidthBlock = minIntrinsicWidthBlock
        this.maxIntrinsicWidthBlock = maxIntrinsicWidthBlock
        this.minIntrinsicHeightBlock = minIntrinsicHeightBlock
        this.maxIntrinsicHeightBlock = maxIntrinsicHeightBlock
    }

    +onCommit {
        layoutState.layoutNode.requestLayout()
    }

    <ParentDataAmbient.Consumer> parentData ->
        <LayoutNode ref=layoutState.layoutNodeRef layout=layoutState parentData>
            <OnChildPositionedAmbient.Provider value=layoutState.onChildPositioned>
                <OnPositionedAmbient.Provider value=layoutState.onPositioned>
                    <ParentDataAmbient.Provider value=null>
                        <children />
                    </ParentDataAmbient.Provider>
                </OnPositionedAmbient.Provider>
            </OnChildPositionedAmbient.Provider>
        </LayoutNode>
    </ParentDataAmbient.Consumer>
}

/**
 * Receiver scope for [ComplexLayout]'s intrinsic measurements lambdas.
 */
class IntrinsicMeasurementsReceiver internal constructor(
    internal val layoutState: ComplexLayoutState
) : DensityReceiver {
    override val density: Density
        get() = layoutState.density
    fun Measurable.minIntrinsicWidth(h: IntPx) =
        (this as ComplexLayoutState).minIntrinsicWidth(h)
    fun Measurable.maxIntrinsicWidth(h: IntPx) =
        (this as ComplexLayoutState).maxIntrinsicWidth(h)
    fun Measurable.minIntrinsicHeight(w: IntPx) =
        (this as ComplexLayoutState).minIntrinsicHeight(w)
    fun Measurable.maxIntrinsicHeight(w: IntPx) =
        (this as ComplexLayoutState).maxIntrinsicHeight(w)
}

/**
 * Receiver scope for [ComplexLayout]'s layout lambda.
 */
class LayoutBlockReceiver internal constructor(
    internal val layoutState: ComplexLayoutState
) : DensityReceiver {
    override val density: Density
        get() = layoutState.density

    fun Measurable.measure(constraints: Constraints): Placeable {
        this as ComplexLayoutState
        return this.measure(constraints)
    }
    fun layoutResult(
        width: IntPx,
        height: IntPx,
        block: PositioningBlockReceiver.() -> Unit
    ) {
        layoutState.resize(width, height)
        layoutState.positioningBlock = block
    }
    fun Measurable.minIntrinsicWidth(h: IntPx) =
        (this as ComplexLayoutState).minIntrinsicWidth(h)
    fun Measurable.maxIntrinsicWidth(h: IntPx) =
        (this as ComplexLayoutState).maxIntrinsicWidth(h)
    fun Measurable.minIntrinsicHeight(w: IntPx) =
        (this as ComplexLayoutState).minIntrinsicHeight(w)
    fun Measurable.maxIntrinsicHeight(w: IntPx) =
        (this as ComplexLayoutState).maxIntrinsicHeight(w)
}

internal class DummyPlaceable(override val width: IntPx, override val height: IntPx) : Placeable() {
    override fun place(x: IntPx, y: IntPx) { }
}

/**
 * A simpler version of [ComplexLayout], intrinsic dimensions do not need to be defined.
 * If a layout of this [Layout] queries the intrinsics, an exception will be thrown.
 * This [Layout] is built using public API on top of [ComplexLayout].
 */
@Composable
fun Layout(
    layoutBlock: LayoutReceiver
        .(measurables: List<Measurable>, constraints: Constraints) -> Unit,
    @Children children: () -> Unit
) {
    val complexLayoutBlock: LayoutBlock = { measurables, constraints: Constraints ->
        val layoutReceiver = LayoutReceiver(
            layoutState,
            { m, c -> m.measure(c) }, /* measure lambda */
            this::layoutResult,
            density
        )
        layoutReceiver.layoutBlock(measurables, constraints)
    }

    val minIntrinsicWidthBlock: IntrinsicMeasurementBlock = { measurables, h ->
        var intrinsicWidth = IntPx.Zero
        val measureBoxReceiver = LayoutReceiver(layoutState, { m, c ->
            val width = m.minIntrinsicWidth(c.minHeight)
            DummyPlaceable(width, h)
        }, { width, _, _ -> intrinsicWidth = width }, density)
        val constraints = Constraints.tightConstraintsForHeight(h)
        layoutBlock(measureBoxReceiver, measurables, constraints)
        intrinsicWidth
    }

    val maxIntrinsicWidthBlock: IntrinsicMeasurementBlock = { measurables, h ->
        var intrinsicWidth = IntPx.Zero
        val layoutReceiver = LayoutReceiver(layoutState, { m, c ->
            val width = m.maxIntrinsicWidth(c.minHeight)
            DummyPlaceable(width, h)
        }, { width, _, _ -> intrinsicWidth = width }, density)
        val constraints = Constraints.tightConstraintsForHeight(h)
        layoutBlock(layoutReceiver, measurables, constraints)
        intrinsicWidth
    }

    val minIntrinsicHeightBlock: IntrinsicMeasurementBlock = { measurables, w ->
        var intrinsicHeight = IntPx.Zero
        val layoutReceiver = LayoutReceiver(layoutState, { m, c ->
            val height = m.minIntrinsicHeight(c.minWidth)
            DummyPlaceable(w, height)
        }, { _, height, _ -> intrinsicHeight = height }, density)
        val constraints = Constraints.tightConstraintsForWidth(w)
        layoutBlock(layoutReceiver, measurables, constraints)
        intrinsicHeight
    }

    val maxIntrinsicHeightBlock: IntrinsicMeasurementBlock = { measurables, w ->
        var intrinsicHeight = IntPx.Zero
        val layoutReceiver = LayoutReceiver(layoutState, { m, c ->
            val height = m.maxIntrinsicHeight(c.minWidth)
            DummyPlaceable(w, height)
        }, { _, height, _ -> intrinsicHeight = height }, density)
        val constraints = Constraints.tightConstraintsForWidth(w)
        layoutBlock(layoutReceiver, measurables, constraints)
        intrinsicHeight
    }

    <ComplexLayout
        layoutBlock=complexLayoutBlock
        minIntrinsicWidthBlock
        maxIntrinsicWidthBlock
        minIntrinsicHeightBlock
        maxIntrinsicHeightBlock
        children />
}

/**
 * Used by [MultiChildLayout] as parent data for the dummy [Layout] instances that mark
 * the end of the [Measurable]s sequence corresponding to a particular child.
 */
internal data class ChildrenEndParentData(val children: () -> Unit)

/**
 * Temporary component that allows composing and indexing measurables of multiple composables.
 * The logic here will be moved back to Layout, which will accept vararg children argument.
 * TODO(popam): remove this when the new syntax is available
 * With the new syntax, the API should support both:
 * Layout(children) { measurables, constraints ->
 *     val placeables = measurables.map { it.measure(...) }
 *     ...
 * }
 * and
 * Layout(header, cardContent, footer) { measurables, constraints ->
 *     val headerPlaceables = measurables[header].map { it.measure(...) }
 *     val cardContentPlaceables = measurables[cardContent].map { ... }
 *     val footerPlaceables = measurables[footer].map { ... }
 *     ...
 * }
 */
@Composable
fun MultiChildLayout(
    childrenArray: Array<@Composable() () -> Unit>,
    @Children(composable = false) layoutBlock: LayoutReceiver
        .(measurables: List<Measurable>, constraints: Constraints) -> Unit
) {
    val ChildrenEndMarker = @Composable { children: () -> Unit ->
        <ParentData data=ChildrenEndParentData(children)>
            <Layout layoutBlock={ _, _ -> layout(0.ipx, 0.ipx) {}} children={} />
        </ParentData>
    }
    val children = @Composable {
        val addMarkers = childrenArray.size > 1
        childrenArray.forEach { childrenComposable ->
            <childrenComposable />
            if (addMarkers) <ChildrenEndMarker p1 = childrenComposable />
        }
    }

    <Layout children layoutBlock />
}

/**
 * Receiver scope for the lambda of [Layout].
 * Used to mask away intrinsics inside [Layout].
 */
class LayoutReceiver internal constructor(
    internal val layoutState: ComplexLayoutState,
    private val complexMeasure: (Measurable, Constraints) -> Placeable,
    private val complexLayoutResult: (IntPx, IntPx, PositioningBlockReceiver.() -> Unit) -> Unit,
    override val density: Density
) : DensityReceiver {
    /**
     * Returns all the [Measurable]s emitted for a particular children lambda.
     * TODO(popam): finding measurables for each individual composable is O(n^2), consider improving
     */
    operator fun List<Measurable>.get(children: () -> Unit): List<Measurable> {
        if (this !is ComplexLayoutStateMeasurablesList) error("Invalid list of measurables")

        val childrenMeasurablesEnd = measurables.indexOfFirst {
            it.parentData is ChildrenEndParentData &&
                    (it.parentData as ChildrenEndParentData).children == children
        }
        val childrenMeasurablesStart = measurables.take(childrenMeasurablesEnd).indexOfLast {
            it.parentData is ChildrenEndParentData
        } + 1
        return measurables.subList(childrenMeasurablesStart, childrenMeasurablesEnd)
    }
    /**
     * Measure the child [Measurable] with a specific set of [Constraints]. The result
     * is a [Placeable], which can be used inside the [layout] method to position the child.
     */
    fun Measurable.measure(constraints: Constraints): Placeable = complexMeasure(this, constraints)
    /**
     * Sets the width and height of the current layout. The lambda is used to perform the
     * calls to [Placeable.place], defining the positions of the children relative to the current
     * layout.
     */
    fun layout(width: IntPx, height: IntPx, block: PositioningBlockReceiver.() -> Unit) {
        complexLayoutResult(width, height, block)
    }
}

/**
 * A widget that defines its own content according to the available space, based on the incoming
 * constraints. Example usage:
 *
 * <WithConstraints> constraints ->
 *     if (constraints.maxWidth < 100.ipx) {
 *         <Icon />
 *     } else {
 *         <Row>
 *             <Icon />
 *             <IconDescription />
 *          </Row>
 *     }
 * </WithConstraints>
 *
 * The widget will compose the given children, and will position the resulting layout widgets
 * in a parent [Layout]. The widget will be as small as possible such that it can fit its
 * children. If the composition yields multiple layout children, these will be all placed at the
 * top left of the WithConstraints, so consider wrapping them in an additional common
 * parent if different positioning is preferred.
 *
 * Please note that using this widget might be a performance hit, so please use with care.
 */
@Composable
fun WithConstraints(@Children children: (Constraints) -> Unit) {
    var ambients: Ambient.Reference? = null
    <Ambient.Portal> value ->
        ambients = value
    </Ambient.Portal>

    <Layout
        layoutBlock = { _, constraints ->
            val root = layoutState.layoutNode
            // Start subcomposition from the current node.
            R4a.composeInto(
                root,
                ambients!!.getAmbient(ContextAmbient),
                ambients
            ) {
                <children p1=constraints />
            }

            // Measure the obtained children and compute our size.
            val measurables = layoutState.childrenMeasurables
            val placeables = measurables.map { it.measure(constraints) }
            val layoutSize = constraints.constrain(IntPxSize(
                placeables.map { it.width }.maxBy { it.value } ?: IntPx.Zero,
                placeables.map { it.height }.maxBy { it.value } ?: IntPx.Zero
            ))

            layout(layoutSize.width, layoutSize.height) {
                placeables.forEach { placeable ->
                    placeable.place(IntPx.Zero, IntPx.Zero)
                }
            }
        }
        children={} />
}

internal val OnPositionedAmbient =
    Ambient.of<MutableList<(LayoutCoordinates) -> Unit>>()

internal val OnChildPositionedAmbient =
    Ambient.of<MutableList<(LayoutCoordinates) -> Unit>>()

/**
 * [onPositioned] callback will be called with the final LayoutCoordinates of the parent
 * MeasureBox after measuring.
 * Note that it will be called after a composition when the coordinates are finalized.
 *
 * Usage example:
 *     <Column>
 *         <Item1/>
 *         <Item2/>
 *         <OnPositioned onPositioned={ coordinates ->
 *             // This coordinates contain bounds of the Column within it's parent Layout.
 *             // Store it if you want to use it later f.e. when a touch happens.
 *         } />
 *     </Column>
 */
@Composable
fun OnPositioned(
    onPositioned: (coordinates: LayoutCoordinates) -> Unit
) {
    val coordinatesCallbacks = +ambient(OnPositionedAmbient)
    +onCommit(onPositioned) {
        coordinatesCallbacks.add(onPositioned)
        onDispose {
            coordinatesCallbacks.remove(onPositioned)
        }
    }
}

/**
 * [onPositioned] callback will be called with the final LayoutCoordinates of the children
 * MeasureBox(es) after measuring.
 * Note that it will be called after a composition when the coordinates are finalized.
 *
 * Usage example:
 *     <OnChildPositioned onPositioned={ coordinates ->
 *         // This coordinates contain bounds of the Item within it's parent Layout.
 *         // Store it if you want to use it later f.e. when a touch happens.
 *     } >
 *         <Item/>
 *     </OnChildPositioned>
 * </Column>
 */
@Composable
fun OnChildPositioned(
    onPositioned: (coordinates: LayoutCoordinates) -> Unit,
    @Children children: () -> Unit
) {
    val coordinatesCallbacks = +ambient(OnChildPositionedAmbient)
    +onCommit(onPositioned) {
        coordinatesCallbacks.add(onPositioned)
        onDispose {
            coordinatesCallbacks.remove(onPositioned)
        }
    }
    <children/>
}
