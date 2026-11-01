package ai.closepaw.voice.semantics

import ai.closepaw.voice.VoiceSession
import ai.closepaw.voice.tools.ToolResult
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review `6551563018` P1, end of the chain: a cross-unit choice must never reach
 * `ClockControl.createTimer` as a number the user did not settle on.
 *
 * `IntentGate` re-parses with the same `ChineseTime.durationSeconds`, so a parser that picks one of
 * two alternatives is invisible to the gate — the only place to check is the argument the tool
 * actually receives.
 */
class TimerChoiceUnitArgumentTest {
    private val ctx = mockk<Context>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, ClockControl)
        every { VoiceSession.isCurrent(any()) } returns true
        every { ClockControl.createTimer(any(), any()) } returns ToolResult.sentWithoutProof(
            "timer", "已经请求设置倒计时，但没法确认它是否真的开始"
        )
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun `an hour or ten minutes never reaches the tool`() {
        val speech = SemanticRunner.run(ctx, "设置一小时或者十分钟的倒计时", 1)

        verify(exactly = 0) { ClockControl.createTimer(any(), any()) }
        assertThat(speech).isNotEmpty()
        assertThat(speech).doesNotContain("已经请求设置倒计时")
    }

    @Test
    fun `five minutes or half a minute never reaches the tool`() {
        SemanticRunner.run(ctx, "设置五分钟或者半分钟的倒计时", 1)

        verify(exactly = 0) { ClockControl.createTimer(any(), any()) }
    }

    @Test
    fun `a compound duration still reaches the tool as one number`() {
        SemanticRunner.run(ctx, "设置一小时十分钟的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 4200) }
    }
}
