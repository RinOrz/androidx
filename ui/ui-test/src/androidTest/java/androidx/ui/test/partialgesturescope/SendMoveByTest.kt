/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.ui.test.partialgesturescope

import androidx.test.filters.MediumTest
import androidx.ui.graphics.Color
import androidx.ui.test.GestureToken
import androidx.ui.test.android.AndroidInputDispatcher
import androidx.ui.test.createComposeRule
import androidx.ui.test.doPartialGesture
import androidx.ui.test.findByTag
import androidx.ui.test.runOnIdleCompose
import androidx.ui.test.sendDown
import androidx.ui.test.sendMoveBy
import androidx.ui.test.util.ClickableTestBox
import androidx.ui.test.util.PointerInputRecorder
import androidx.ui.test.util.assertTimestampsAreIncreasing
import androidx.ui.test.util.inMilliseconds
import androidx.ui.unit.PxPosition
import androidx.ui.unit.inMilliseconds
import androidx.ui.unit.px
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private val width = 200.px
private val height = 200.px

private const val tag = "widget"

@MediumTest
@RunWith(Parameterized::class)
class SendMoveByTest(private val config: TestConfig) {
    data class TestConfig(val moveByDelta: PxPosition) {
        val downPosition = PxPosition(1.px, 1.px)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun createTestSet(): List<TestConfig> {
            return mutableListOf<TestConfig>().apply {
                for (x in listOf(2.px, (-100).px)) {
                    for (y in listOf(3.px, (-530).px)) {
                        add(TestConfig(PxPosition(x, y)))
                    }
                }
            }
        }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dispatcherRule = AndroidInputDispatcher.TestRule(disableDispatchInRealTime = true)
    @get:Rule
    val inputDispatcherRule: TestRule = dispatcherRule

    private lateinit var recorder: PointerInputRecorder
    private val expectedEndPosition = config.downPosition + config.moveByDelta

    @Test
    fun testSendMoveBy() {
        // Given some content
        recorder = PointerInputRecorder()
        composeTestRule.setContent {
            ClickableTestBox(width, height, Color.Yellow, tag, recorder)
        }

        // When we inject a down event followed by a move event
        lateinit var token: GestureToken
        findByTag(tag).doPartialGesture { token = sendDown(config.downPosition) }
        findByTag(tag).doPartialGesture { sendMoveBy(token, config.moveByDelta) }

        runOnIdleCompose {
            recorder.run {
                // Then we have recorded 1 down event and 1 move event
                assertTimestampsAreIncreasing()
                assertThat(events).hasSize(2)
                assertThat(events[1].down).isTrue()
                assertThat(events[1].position).isEqualTo(expectedEndPosition)
                assertThat((events[1].timestamp - events[0].timestamp).inMilliseconds())
                    .isEqualTo(dispatcherRule.eventPeriod)

                // And the information in the token matches the last move event
                assertThat(token.downTime).isEqualTo(events[0].timestamp.inMilliseconds())
                assertThat(token.eventTime).isEqualTo(events[1].timestamp.inMilliseconds())
                assertThat(token.lastPosition).isEqualTo(expectedEndPosition)
            }
        }
    }
}
