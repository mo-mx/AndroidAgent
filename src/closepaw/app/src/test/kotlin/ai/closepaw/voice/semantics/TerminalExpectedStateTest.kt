package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ObservedState
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `expectedState` contract itself — review `6546660320` P1/P2.
 *
 * The previous gate answered "did the tool claim it verified?" and nothing else, so
 * `expectedState` (`timer_running:300`, `feature_visible`, …) was never compared with anything.
 * Any capability that reported `verified = true` — including for the wrong object, the wrong
 * duration, or a permission sheet that happened to change the screen — was graded CONFIRMED and
 * spoken as done.
 *
 * The rules pinned here:
 *   1. A task that *declares* an expected state may only be CONFIRMED when the result carries
 *      structured evidence (object identity + observed state) that matches both the declared
 *      identity and the declared state. A bare `verified = true` is not evidence.
 *   2. Evidence about a *different* object, or no state token at all, leaves the outcome unknown;
 *      it must not be upgraded into "done" and must not be turned into a false "no change" claim.
 *   3. Evidence that names the right object with a different state IS a contradiction and must be
 *      refused, with the spoken sentence rewritten.
 *   4. `Action.OPEN` is not exempt: `打开了微信扫一扫` promises a visible page too.
 *
 * Pure JVM: no Android, no device, no accessibility tree.
 */
class TerminalExpectedStateTest {

    private fun timerTask(expected: String = "timer_running:300", identity: String? = null) =
        SemanticTask(
            action = Action.CREATE,
            target = Target.TIMER,
            params = mapOf("seconds" to "300"),
            expectedState = expected,
            targetIdentity = identity,
        )

    private fun featureTask(identity: String? = "com.tencent.mm") = SemanticTask(
        action = Action.OPEN,
        target = Target.APP_FEATURE,
        params = mapOf("name" to "微信", "feature" to "扫一扫"),
        expectedState = "feature_visible",
        targetIdentity = identity,
    )

    private fun claimed(
        tool: String = "timer",
        speak: String = "五分钟的倒计时已经开始",
        observed: ObservedState? = null,
    ) = ToolResult(ToolStatus.SUCCESS, tool, speak, "fixture", true, observed)

    // ── 1. a claim without evidence is not confirmation ──

    @Test fun `verified true without structured evidence is not confirmed`() {
        val v = TerminalState.verdict(timerTask(), claimed())
        assertThat(v).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test fun `verified true with matching object and state is confirmed`() {
        val v = TerminalState.verdict(
            timerTask(identity = "timer#1"),
            claimed(observed = ObservedState(identity = "timer#1", state = "running:300", source = "clock-ui")),
        )
        assertThat(v).isEqualTo(TerminalState.Verdict.CONFIRMED)
    }

    // ── 2. evidence about another object stays unknown ──

    @Test fun `verified true read back on a different object is not confirmed`() {
        val v = TerminalState.verdict(
            timerTask(identity = "timer#1"),
            claimed(observed = ObservedState(identity = "timer#2", state = "running:300")),
        )
        assertThat(v).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test fun `evidence without a state token is not confirmation`() {
        val v = TerminalState.verdict(
            timerTask(identity = "timer#1"),
            claimed(observed = ObservedState(identity = "timer#1", state = null)),
        )
        assertThat(v).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    // ── 3. same object, different state is a contradiction ──

    @Test fun `verified true with the wrong duration is contradicted`() {
        val v = TerminalState.verdict(
            timerTask(identity = "timer#1"),
            claimed(observed = ObservedState(identity = "timer#1", state = "running:600")),
        )
        assertThat(v).isEqualTo(TerminalState.Verdict.CONTRADICTED)
    }

    @Test fun `a contradicted duration claim is never spoken as done`() {
        val out = TerminalState.enforce(
            timerTask(identity = "timer#1"),
            claimed(speak = "五分钟的倒计时已经开始",
                observed = ObservedState(identity = "timer#1", state = "running:600")),
        )
        assertThat(out.status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        assertThat(out.verified).isFalse()
        assertThat(out.speak).doesNotContain("已经开始")
        assertThat(out.detail).contains("postcondition=contradicted")
    }

    // ── 4. OPEN promises a page too ──

    @Test fun `feature open with no page identity is not confirmed`() {
        val v = TerminalState.verdict(
            featureTask(),
            ToolResult(ToolStatus.SUCCESS, "app_feature", "已经进入微信的扫一扫", "newTexts=2", true),
        )
        assertThat(v).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test fun `feature open without page identity is spoken as clicked but unconfirmed`() {
        val r = ToolResult(ToolStatus.SUCCESS, "app_feature", "已经进入微信的扫一扫", "newTexts=2", true)
        val out = TerminalState.enforce(featureTask(), r)
        assertThat(out.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(out.verified).isNull()
        assertThat(out.speak).doesNotContain("已经进入")
        assertThat(out.speak).contains("扫一扫")
        assertThat(out.speak).contains("没能确认")
        assertThat(out.detail).contains("postcondition=unproven")
    }

    @Test fun `feature open with page evidence is confirmed and spoken unchanged`() {
        val r = ToolResult(
            ToolStatus.SUCCESS, "app_feature", "已经进入微信的扫一扫", "fg=com.tencent.mm", true,
            ObservedState(identity = "com.tencent.mm", state = "visible", source = "page-header"),
        )
        assertThat(TerminalState.verdict(featureTask(), r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
        assertThat(TerminalState.enforce(featureTask(), r)).isEqualTo(r)
    }

    @Test fun `feature visible evidence in another package is not confirmed`() {
        val r = ToolResult(
            ToolStatus.SUCCESS, "app_feature", "已经进入微信的扫一扫", "fg=com.android.permissioncontroller", true,
            ObservedState(identity = "com.android.permissioncontroller", state = "visible"),
        )
        assertThat(TerminalState.verdict(featureTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
        assertThat(TerminalState.enforce(featureTask(), r).verified).isNull()
    }

    @Test fun `hedged feature result is stable under repeated enforcement`() {
        val r = ToolResult(ToolStatus.SUCCESS, "app_feature", "已经进入微信的扫一扫", "newTexts=2", true)
        val once = TerminalState.enforce(featureTask(), r)
        assertThat(TerminalState.enforce(featureTask(), once)).isEqualTo(once)
    }

    // ── non-regression: tasks that declare nothing keep the tri-state grading ──

    @Test fun `a task that declares no expected state keeps the verified tri-state`() {
        val bare = SemanticTask(Action.CREATE, Target.TIMER, mapOf("seconds" to "300"))
        assertThat(TerminalState.verdict(bare, claimed())).isEqualTo(TerminalState.Verdict.CONFIRMED)
        assertThat(TerminalState.verdict(bare, ToolResult.sentWithoutProof("timer", "请求已发送")))
            .isEqualTo(TerminalState.Verdict.UNPROVEN)
        assertThat(TerminalState.verdict(bare, ToolResult(ToolStatus.SUCCESS, "timer", "x", "", false)))
            .isEqualTo(TerminalState.Verdict.CONTRADICTED)
    }

    @Test fun `an unproven mutation keeps its own hedged sentence`() {
        val hedged = ToolResult.sentWithoutProof("timer", "五分钟的倒计时请求已经发送，但无法确认是否开始")
        val out = TerminalState.enforce(timerTask(), hedged)
        assertThat(out).isEqualTo(hedged)
    }

    @Test fun `a refused feature open is not rewritten`() {
        val blocked = ToolResult.blocked("app_feature", "对象身份不可确认")
        assertThat(TerminalState.verdict(featureTask(), blocked)).isEqualTo(TerminalState.Verdict.NOT_APPLICABLE)
        assertThat(TerminalState.enforce(featureTask(), blocked)).isEqualTo(blocked)
    }
}
