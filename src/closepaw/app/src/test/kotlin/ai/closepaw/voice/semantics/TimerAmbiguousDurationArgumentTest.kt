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
 * Review `6551213132` P1, end of the chain: an unclear duration in the utterance must never reach
 * `ClockControl.createTimer` as a number the user did not say.
 *
 * `IntentGate` re-parses the utterance with the same `ChineseTime.durationSeconds`, so a parser bug
 * that picks one of two durations is invisible to the gate — the only place to check is the
 * argument the tool actually receives.
 */
class TimerAmbiguousDurationArgumentTest {
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
    fun `two alternative durations never reach the tool as one number`() {
        val speech = SemanticRunner.run(ctx, "设置五分钟或者十分钟的倒计时", 1)

        verify(exactly = 0) { ClockControl.createTimer(any(), any()) }
        assertThat(speech).isNotEmpty()
        assertThat(speech).doesNotContain("已经请求设置倒计时")
    }

    @Test
    fun `a second duration joined by 和 never reaches the tool`() {
        SemanticRunner.run(ctx, "设置十分钟和一分钟的倒计时", 1)

        verify(exactly = 0) { ClockControl.createTimer(any(), any()) }
    }

    @Test
    fun `one and a half minutes reaches the tool as ninety seconds`() {
        SemanticRunner.run(ctx, "设置一个半分钟的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 90) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 30) }
    }
}
