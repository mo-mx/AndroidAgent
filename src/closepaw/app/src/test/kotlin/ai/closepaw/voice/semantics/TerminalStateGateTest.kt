package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Post-execution terminal-state contract (`expectedState` evaluation).
 *
 * The gap these tests pin: `expectedState` is descriptive metadata and nothing checked the
 * *outcome* against it, so three different facts collapsed into "SUCCESS":
 *   1. the device read back and agrees           → verified = true
 *   2. nothing could be read back                → verified = null (unknown)
 *   3. the device read back and disagrees        → verified = false
 * Only (1) may be spoken as done. (3) may never be reported as success. (2) stays a success with
 * an honest "request sent, not confirmed" sentence.
 *
 * Pure JVM: no Android imports, no device, no accessibility tree.
 */
class TerminalStateGateTest {

    private fun task(action: Action, target: Target) = SemanticTask(action, target)

    private fun success(verified: Boolean?, speak: String = "五分钟的倒计时请求已经发送，但无法确认是否开始") =
        ToolResult(ToolStatus.SUCCESS, "timer", speak, "fixture", verified)

    // ── verdict classification ──

    @Test fun `mutating success with read-back agreement is confirmed`() {
        val r = success(true)
        assertThat(TerminalState.verdict(task(Action.CREATE, Target.TIMER), r))
            .isEqualTo(TerminalState.Verdict.CONFIRMED)
    }

    @Test fun `mutating success with no read-back is unproven, not confirmed`() {
        val r = ToolResult.sentWithoutProof("timer", "五分钟的倒计时请求已经发送，但无法确认是否开始")
        assertThat(TerminalState.verdict(task(Action.CREATE, Target.TIMER), r))
            .isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test fun `mutating success whose own read-back disagrees is contradicted`() {
        val r = success(false)
        assertThat(TerminalState.verdict(task(Action.CANCEL, Target.TIMER), r))
            .isEqualTo(TerminalState.Verdict.CONTRADICTED)
    }

    @Test fun `query never needs a terminal state even when unverified`() {
        val r = ToolResult.ok("alarm_query", "可见 2 个时间显示", verified = false, detail = "partial")
        assertThat(TerminalState.verdict(task(Action.QUERY, Target.ALARM), r))
            .isEqualTo(TerminalState.Verdict.NOT_APPLICABLE)
    }

    @Test fun `a refusal is not a failed postcondition`() {
        val r = ToolResult.blocked("timer_cancel", "无法唯一定位你说的倒计时")
        assertThat(TerminalState.verdict(task(Action.CANCEL, Target.TIMER), r))
            .isEqualTo(TerminalState.Verdict.NOT_APPLICABLE)
    }

    // ── enforcement ──

    @Test fun `confirmed result passes through untouched`() {
        val r = success(true, "已经开始了五分钟的倒计时")
        assertThat(TerminalState.enforce(task(Action.CREATE, Target.TIMER), r)).isEqualTo(r)
    }

    @Test fun `unproven result stays a success, so the request is not reported as a failure`() {
        val r = ToolResult.sentWithoutProof("timer", "五分钟的倒计时请求已经发送，但无法确认是否开始")
        val out = TerminalState.enforce(task(Action.CREATE, Target.TIMER), r)
        assertThat(out.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(out.verified).isNull()
        assertThat(out.speak).isEqualTo(r.speak)
        assertThat(out.detail).isEqualTo(r.detail)
    }

    @Test fun `contradicted success is downgraded and never claims the job was done`() {
        val r = success(false, "已经关闭了八点的闹钟")
        val out = TerminalState.enforce(task(Action.CLOSE, Target.ALARM), r)
        assertThat(out.status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        assertThat(out.verified).isFalse()
        assertThat(out.speak).doesNotContain("已经关闭")
        assertThat(out.speak).contains("没有变化")
        assertThat(out.detail).contains("postcondition=contradicted")
        // The original evidence is preserved, not replaced by the new sentence.
        assertThat(out.detail).contains(r.detail)
    }

    @Test fun `enforcement is idempotent`() {
        val r = success(false)
        val once = TerminalState.enforce(task(Action.CANCEL, Target.TIMER), r)
        val twice = TerminalState.enforce(task(Action.CANCEL, Target.TIMER), once)
        assertThat(twice).isEqualTo(once)
    }

    @Test fun `non-mutating and refused results are never rewritten`() {
        val q = ToolResult.ok("timer_query", "现在有 1 个倒计时在运行", verified = true)
        assertThat(TerminalState.enforce(task(Action.QUERY, Target.TIMER), q)).isEqualTo(q)
        val b = ToolResult.blocked("alarm_close", "对象身份不可确认")
        assertThat(TerminalState.enforce(task(Action.CLOSE, Target.ALARM), b)).isEqualTo(b)
    }

    // ── producer contract ──

    @Test fun `request-sent-without-proof is reported unknown, not unchanged`() {
        val r = ToolResult.sentWithoutProof("timer", "请求已经发送", "clockFg=true")
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.tool).isEqualTo("timer")
        assertThat(r.detail).isEqualTo("clockFg=true")
    }
}
