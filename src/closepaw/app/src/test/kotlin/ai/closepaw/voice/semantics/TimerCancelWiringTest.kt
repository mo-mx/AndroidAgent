package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ChineseTime
import ai.closepaw.voice.tools.ObservedState
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round 29 review P2: `TerminalState.identityMatches` returned `true` whenever the *task* had no
 * `targetIdentity`, and `SemanticParser` never set one for a timer cancel. So the object-level half
 * of the terminal contract was never exercised on the real voice path — the passing fixtures had
 * hand-filled the identity.
 *
 * These tests start from an utterance, not a hand-built task, and pin:
 *
 *   1. the parser derives an identity from the same parameters it derives `expectedState` from, so
 *      a real path can name the object at all;
 *   2. evidence naming a *different* object than the utterance leaves the outcome UNPROVEN;
 *   3. evidence the task never declared an identity for is not correspondence either — it is
 *      UNPROVEN, never CONFIRMED.
 *
 * Pure JVM: no Android, no device.
 */
class TimerCancelWiringTest {

    private fun parsed(utterance: String): SemanticTask = SemanticParser.parse(utterance).single()

    @Test
    fun `a spoken cancel declares both the expected state and the object identity`() {
        val task = parsed("取消五分钟倒计时")

        assertThat(task.action).isEqualTo(Action.CANCEL)
        assertThat(task.target).isEqualTo(Target.TIMER)
        assertThat(task.expectedState).isEqualTo("no_running_timer")
        assertThat(task.targetIdentity).isEqualTo("timer:300")
        // The identity is not a second guess: it is derived from the same number as the params.
        assertThat(task.params["seconds"]).isEqualTo("${ChineseTime.durationSeconds("五分钟")}")
    }

    @Test
    fun `a spoken create declares the identity of the timer it asked for`() {
        val task = parsed("设一个五分钟的倒计时")

        assertThat(task.action).isEqualTo(Action.CREATE)
        assertThat(task.target).isEqualTo(Target.TIMER)
        assertThat(task.expectedState).isEqualTo("timer_running:300")
        assertThat(task.targetIdentity).isEqualTo("timer:300")
    }

    @Test
    fun `a cancelled timer observed under another duration stays unproven on the real path`() {
        // The tool cannot bind the click to a stable id; even a matching `none` read is not a
        // confirmation, and a mismatched identity must not be read as one either.
        val task = parsed("取消五分钟倒计时")
        val claimed = ToolResult(
            ToolStatus.SUCCESS, "timer_cancel", "已取消这个倒计时", "fixture", true,
            ObservedState(identity = "timer:600", state = "none"),
        )

        assertThat(TerminalState.verdict(task, claimed)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test
    fun `a spoken cancel that the tool never proved is unproven end to end`() {
        val task = parsed("取消五分钟倒计时")
        val hedged = ToolResult.sentWithoutProof("timer_cancel", "已请求取消指定倒计时，但无法确认最终状态")

        assertThat(TerminalState.verdict(task, hedged)).isEqualTo(TerminalState.Verdict.UNPROVEN)
        assertThat(TerminalState.enforce(task, hedged)).isEqualTo(hedged)
    }

    @Test
    fun `object evidence on a task with no declared identity is never a confirmation`() {
        val task = SemanticParser.parse("取消五分钟倒计时").single().copy(targetIdentity = null)
        val claimed = ToolResult(
            ToolStatus.SUCCESS, "timer_cancel", "已取消这个倒计时", "fixture", true,
            ObservedState(identity = "timer:300", state = "none"),
        )

        assertThat(TerminalState.verdict(task, claimed)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }
}
