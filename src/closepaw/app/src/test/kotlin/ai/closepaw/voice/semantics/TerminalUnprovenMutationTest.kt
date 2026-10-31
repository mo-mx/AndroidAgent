package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ObservedState
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review `6547564219` P1: `TerminalState.enforce` only hedged `Action.OPEN`.
 *
 * `verdict` already graded a task with a declared `expectedState` and no object evidence as
 * UNPROVEN. But enforcement returned the original `ToolResult` for anything that was not an OPEN,
 * so `CREATE`/`CANCEL`/`SET` kept `verified = true` and a sentence such as “倒计时已经开始” was
 * spoken — and `SemanticRunner` could still record it as a completed action. The missing evidence
 * was judged; it just was not acted upon.
 *
 * Contract pinned here: for every action that promises a state, UNPROVEN may stay a SUCCESS (the
 * request really was delivered) but may never keep `verified = true` or a settled “done” sentence.
 */
class TerminalUnprovenMutationTest {

    private fun claimed(tool: String, speak: String, observed: ObservedState? = null) =
        ToolResult(ToolStatus.SUCCESS, tool, speak, "fixture", true, observed)

    private fun timerTask() = SemanticTask(
        action = Action.CREATE,
        target = Target.TIMER,
        params = mapOf("seconds" to "300"),
        expectedState = "timer_running:300",
    )

    private fun cancelTask() = SemanticTask(
        action = Action.CANCEL,
        target = Target.TIMER,
        params = mapOf("seconds" to "300"),
        expectedState = "no_running_timer",
    )

    private fun torchTask() = SemanticTask(
        action = Action.SET,
        target = Target.TORCH,
        params = mapOf("setting" to "torch", "value" to "on"),
        expectedState = "on:TORCH",
        targetIdentity = "TORCH",
    )

    // ── the claim is judged UNPROVEN; enforcement must follow ──

    @Test fun `unproven timer creation is not spoken as done and drops verified`() {
        val r = claimed("timer", "五分钟的倒计时已经开始")
        assertThat(TerminalState.verdict(timerTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)

        val out = TerminalState.enforce(timerTask(), r)

        assertThat(out.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(out.verified).isNull()
        assertThat(out.speak).doesNotContain("已经开始")
        assertThat(out.speak).doesNotContain("完成")
        assertThat(out.speak).contains("没能确认")
        assertThat(out.detail).contains("postcondition=unproven")
        assertThat(out.detail).contains(r.detail)
    }

    @Test fun `unproven timer cancel keeps success but never claims it was cancelled`() {
        val r = claimed("timer_cancel", "已经取消了五分钟的倒计时")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)

        val out = TerminalState.enforce(cancelTask(), r)

        assertThat(out.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(out.verified).isNull()
        assertThat(out.speak).doesNotContain("已经取消")
        assertThat(out.detail).contains("postcondition=unproven")
    }

    @Test fun `unproven setting change is hedged like any other promised state`() {
        val r = claimed(
            "torch",
            "手电筒已经打开",
            ObservedState(identity = "CAMERA_LIGHT", state = "on", source = "settings"),
        )
        // Evidence names a different object, so the promised TORCH state was never observed.
        assertThat(TerminalState.verdict(torchTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)

        val out = TerminalState.enforce(torchTask(), r)

        assertThat(out.verified).isNull()
        assertThat(out.speak).doesNotContain("已经打开")
        assertThat(out.detail).contains("postcondition=unproven")
    }

    @Test fun `enforcement stays idempotent for an unproven mutation`() {
        val r = claimed("timer", "五分钟的倒计时已经开始")
        val once = TerminalState.enforce(timerTask(), r)
        assertThat(TerminalState.enforce(timerTask(), once)).isEqualTo(once)
    }

    // ── non-regression: evidence-backed and evidence-free paths are untouched ──

    @Test fun `confirmed mutation still passes through untouched`() {
        val r = claimed(
            "timer",
            "五分钟的倒计时已经开始",
            ObservedState(identity = "timer", state = "running:300", source = "clock-ui"),
        )
        // No target identity declared, so only the state token is compared.
        assertThat(TerminalState.verdict(timerTask(), r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
        assertThat(TerminalState.enforce(timerTask(), r)).isEqualTo(r)
    }

    @Test fun `mutation that already reported unknown keeps its own sentence`() {
        val hedged = ToolResult.sentWithoutProof("timer", "五分钟的倒计时请求已经发送，但无法确认是否开始")
        assertThat(TerminalState.enforce(timerTask(), hedged)).isEqualTo(hedged)
    }

    @Test fun `query and refusal are still never rewritten`() {
        val q = ToolResult.ok("timer_query", "现在有 1 个倒计时在运行", verified = true)
        assertThat(TerminalState.enforce(SemanticTask(Action.QUERY, Target.TIMER), q)).isEqualTo(q)
        val b = ToolResult.blocked("alarm_close", "对象身份不可确认")
        assertThat(TerminalState.enforce(SemanticTask(Action.CLOSE, Target.ALARM), b)).isEqualTo(b)
    }
}
