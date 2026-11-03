package ai.closepaw.voice.semantics

import ai.closepaw.voice.semantics.SemanticRunner
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.semantics.SemanticParser
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
 * Round 35 — the review's point is that a wrong duration reaches the tool, and the gate cannot
 * catch it because it re-parses the same utterance with the same function. So the assertion has to
 * be on the argument the tool actually receives, driven from the real utterance.
 *
 * Pure JVM. Only `ClockControl` is mocked; nothing here proves the clock app accepts these values.
 */
class TimerCompoundDurationArgumentTest {
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
    fun `half a minute reaches the tool as thirty seconds`() {
        val task = SemanticParser.parse("设置半分钟的倒计时").single()
        assertThat(task.target).isEqualTo(Target.TIMER)

        SemanticRunner.run(ctx, "设置半分钟的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 30) }
        // The two wrong readings this replaces: half an hour, or a "1" fall-through.
        verify(exactly = 0) { ClockControl.createTimer(any(), 1800) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 1) }
    }

    @Test
    fun `one and a half hours reaches the tool as ninety minutes`() {
        val task = SemanticParser.parse("设置一个半小时的倒计时").single()
        assertThat(task.target).isEqualTo(Target.TIMER)

        SemanticRunner.run(ctx, "设置一个半小时的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 5400) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 1800) }
    }

    @Test
    fun `a decimal hour reaches the tool as the decimal`() {
        SemanticRunner.run(ctx, "设置1.5小时的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 5400) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 18000) }
    }
}
