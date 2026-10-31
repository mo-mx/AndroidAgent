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
 * Round 33: the "五分钟 arrives as the real tool parameter 300 s" item was only ever checked at the
 * parser boundary and on a device in PARSEONLY mode. Nothing asserted what the *runner* actually
 * hands to `ClockControl.createTimer`, so a parse that produced a wrong number would still look
 * correct in the parser suite — the gate compares the utterance against the parser's own output,
 * and agrees with itself.
 *
 * These tests start from the utterance and go through the real parser, capability table, gate and
 * runner. Only `ClockControl` is mocked, and the assertion is on the argument it receives. Pure
 * JVM: this is the tool boundary, not a device acceptance test, and it says nothing about whether
 * the clock app accepts these lengths.
 */
class TimerCreateArgumentTest {
    private val ctx = mockk<Context>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
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
    fun `a spoken five minute timer reaches the tool as 300 seconds`() {
        SemanticRunner.run(ctx, "开始五分钟倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 300) }
        // The reverse-risk shape from the model evaluation: "五分钟" delivered as 5 s.
        verify(exactly = 0) { ClockControl.createTimer(any(), 5) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 3000) }
    }

    @Test
    fun `hours plus minutes reaches the tool as their sum`() {
        SemanticRunner.run(ctx, "设置一小时十分钟的倒计时", 1)

        verify(exactly = 1) { ClockControl.createTimer(ctx, 4200) }
        verify(exactly = 0) { ClockControl.createTimer(any(), 216600) }
    }

    @Test
    fun `a create without a duration asks instead of inventing 300 seconds`() {
        // "开始倒计时" parses as a query (no create verb), which is a read, not a silent create.
        // "设一个倒计时" is an explicit create with no length: the runner must ask, not default.
        val reply = SemanticRunner.run(ctx, "设一个倒计时", 1)

        assertThat(reply).contains("多久")
        verify(exactly = 0) { ClockControl.createTimer(any(), any()) }
    }
}
