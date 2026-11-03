package ai.closepaw.voice.semantics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Acceptance tests for the final-boundary semantic gate (Issue #3, stage 1).
 *
 * Each test pairs a real utterance (parsed by [SemanticParser]) with the tool call the executor
 * *would* make. The gate must refuse whenever the call drifted from what was asked, and allow the
 * exact match. Nothing here touches Android APIs, so this runs on the JVM before any deployment.
 */
class IntentGateTest {

    @Test fun `alarm cancellation cannot forge original clock time with planner identity`() {
        val raw = "取消八点的闹钟"
        val t = task(raw).copy(params = mapOf("hour" to "7", "minute" to "0"), targetIdentity = "alarm:7:0")
        refuse(raw, t, call(Action.CANCEL, Target.ALARM, t.params, "alarm:7:0"))
    }

    @Test fun `general app needs independent unique installed identity evidence`() {
        val raw = "打开微信"
        val t = task(raw)
        val good = call(Action.OPEN, Target.APP, mapOf("name" to "微信"), "com.tencent.mm")
        allow(raw, t, good)
        refuse(raw, t, good.copy(identity = "com.tencent.mobileqq"))
        refuse(raw, t, good.copy(apps = emptyList()))
        refuse(raw, t, good.copy(apps = good.apps + IntentGate.AppIdentity("work.wechat", "微信")))
        refuse(raw, t.copy(params = mapOf("name" to "QQ")), good.copy(params = mapOf("name" to "QQ"), identity = "com.tencent.mobileqq"))
    }

    @Test fun `feature app identity and feature argument must both survive`() {
        val raw = "打开微信扫一扫"
        val t = task(raw)
        val good = call(Action.OPEN, Target.APP_FEATURE, t.params, "com.tencent.mm")
        allow(raw, t, good)
        refuse(raw, t, good.copy(identity = "com.tencent.mobileqq"))
        refuse(raw, t, good.copy(params = mapOf("name" to "微信")))
        refuse(raw, t.copy(params = mapOf("name" to "微信", "feature" to "付款码")),
            good.copy(params = mapOf("name" to "微信", "feature" to "付款码")))
    }

    private fun task(utterance: String): SemanticTask = SemanticParser.parse(utterance).first()

    private fun call(
        action: Action,
        target: Target,
        params: Map<String, String> = emptyMap(),
        identity: String? = null,
    ) = IntentGate.ToolCall(action, target, params, identity, apps = listOf(
        IntentGate.AppIdentity("com.tencent.mm", "微信"),
        IntentGate.AppIdentity("com.tencent.mobileqq", "QQ"),
        IntentGate.AppIdentity("com.android.vending", "Google Play", listOf("谷歌商店")),
        IntentGate.AppIdentity("com.sec.android.app.samsungapps", "Galaxy Store", listOf("三星商店")),
    ))

    private fun allow(utterance: String, t: SemanticTask, c: IntentGate.ToolCall) {
        // SemanticRunner now hands the gate the identity it derives for the target, so the gate has
        // something to compare on both sides; an explicit identity on the call still wins.
        val d = IntentGate.check(utterance, t, c.copy(identity = c.identity ?: t.targetIdentity))
        assertThat(d.allowed).isTrue()
        assertThat(d.reason).isEmpty()
    }

    private fun refuse(utterance: String, t: SemanticTask, c: IntentGate.ToolCall): IntentGate.Decision {
        val d = IntentGate.check(utterance, t, c)
        assertThat(d.allowed).isFalse()
        assertThat(d.ask).isNotNull()
        return d
    }

    // ── 1. 取消不得变成创建 ──────────────────────────────────────────────

    @Test
    fun `cancelling a non-existent 8 o'clock alarm never creates one`() {
        val u = "取消八点的闹钟"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.CANCEL)
        assertThat(t.target).isEqualTo(Target.ALARM)

        val d = refuse(u, t, call(Action.CREATE, Target.ALARM,
            mapOf("hour" to "8", "minute" to "0"), "alarm:8:0"))
        assertThat(d.reason).contains("inversion")
    }

    @Test
    fun `cancelling a timer never creates a timer`() {
        val u = "取消五分钟倒计时"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.CANCEL)

        refuse(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300")))
        // ...and the honest path is allowed.
        refuse(u, t, call(Action.CANCEL, Target.TIMER))
        allow(u, t, call(Action.CANCEL, Target.TIMER, mapOf("seconds" to "300")))
    }

    @Test
    fun `closing and resuming a timer never create one`() {
        for (u in listOf("关闭倒计时", "暂停倒计时", "恢复倒计时")) {
            val t = task(u)
            val d = IntentGate.check(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300")))
            assertThat(d.allowed).isFalse()
        }
    }

    // ── 2. 查询不得修改 ────────────────────────────────────────────────

    @Test
    fun `a query may never mutate`() {
        val u = "查询闹钟"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.QUERY)

        refuse(u, t, call(Action.CREATE, Target.ALARM, mapOf("hour" to "7", "minute" to "30")))
        refuse(u, t, call(Action.DELETE, Target.ALARM))
        allow(u, t, call(Action.QUERY, Target.ALARM))
    }

    // ── 3. 时长单位 ────────────────────────────────────────────────────

    @Test
    fun `five minutes is never five seconds`() {
        val u = "设一个五分钟的倒计时"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.CREATE)
        assertThat(t.params["seconds"]).isEqualTo("300")

        // The executor passing 5 s instead of 300 s is a block, not a rounding error.
        val d = refuse(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "5")))
        assertThat(d.reason).contains("drift")

        allow(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300")))
    }

    @Test
    fun `a dropped parameter is fine only while the argument still matches the utterance`() {
        // Planner lost the duration, but the executor passes exactly what the user said (300 s).
        val u = "设一个五分钟的倒计时"
        val t = task(u).copy(params = emptyMap())
        allow(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300")))

        // ...but the same dropped parameter with a different argument is refused.
        // The call now carries the identity the runner derives from its own args, so a 5 s call
        // names `timer:5` and is refused as an identity drift before the parameter check runs —
        // a stricter refusal than the old wording, not a weaker one.
        val d = refuse(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "5"), "timer:5"))
        assertThat(d.reason).contains("drift")
    }

    @Test
    fun `a timer without any stated duration is refused`() {
        // "定一个倒计时" — no unit at all; guessing 300 s is exactly the old `?: 300` behaviour.
        val u = "定一个倒计时"
        val t = task(u)
        val d = refuse(u, t, call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300")))
        assertThat(d.reason).contains("invented")
    }

    @Test
    fun `alarm hour must match the utterance`() {
        val u = "设置八点半的闹钟"
        val t = task(u)
        assertThat(t.params["hour"]).isEqualTo("8")
        assertThat(t.params["minute"]).isEqualTo("30")

        // Same hour, wrong minute -> identity mismatch.
        refuse(u, t, call(Action.CREATE, Target.ALARM,
            mapOf("hour" to "8", "minute" to "0"), "alarm:8:0"))
        allow(u, t, call(Action.CREATE, Target.ALARM,
            mapOf("hour" to "8", "minute" to "30"), "alarm:8:30"))
    }

    // ── 4. 目标身份 ────────────────────────────────────────────────────

    @Test
    fun `google play and galaxy store are not interchangeable`() {
        val u = "打开谷歌商店"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.OPEN)

        val d = refuse(u, t, call(Action.OPEN, Target.APP,
            mapOf("name" to "谷歌商店"), "com.sec.android.app.samsungapps"))
        assertThat(d.reason).contains("identity mismatch")

        allow(u, t, call(Action.OPEN, Target.APP,
            mapOf("name" to "谷歌商店"), "com.android.vending"))
    }

    @Test
    fun `samsung store request must not land on google play`() {
        val u = "打开三星商店"
        val t = task(u)
        refuse(u, t, call(Action.OPEN, Target.APP, mapOf("name" to "三星商店"), "com.android.vending"))
        allow(u, t, call(Action.OPEN, Target.APP,
            mapOf("name" to "三星商店"), "com.sec.android.app.samsungapps"))
    }

    @Test
    fun `an unresolved app identity is refused, never guessed`() {
        val u = "打开微信"
        val t = task(u)
        val d = refuse(u, t, call(Action.OPEN, Target.APP, mapOf("name" to "微信")))
        assertThat(d.reason).contains("unresolved")
    }

    // ── 5. 应用二级页面 ────────────────────────────────────────────────

    @Test
    fun `in-app feature request may not degrade to a plain launch`() {
        val u = "打开微信扫一扫"
        val t = task(u)
        assertThat(t.target).isEqualTo(Target.APP_FEATURE)
        assertThat(t.params["feature"]).isEqualTo("扫一扫")

        // Only launching WeChat is not "opening 扫一扫".
        refuse(u, t, call(Action.OPEN, Target.APP,
            mapOf("name" to "微信"), "com.tencent.mm"))
        allow(u, t, call(Action.OPEN, Target.APP_FEATURE,
            mapOf("name" to "微信", "feature" to "扫一扫"), "com.tencent.mm"))
    }

    // ── 6. 动作 / 目标漂移与歧义 ────────────────────────────────────────

    @Test
    fun `action drift is refused`() {
        val u = "打开手电筒"
        val t = task(u)
        assertThat(t.action).isEqualTo(Action.ENABLE)
        val d = refuse(u, t, call(Action.DISABLE, Target.TORCH, identity = "torch"))
        assertThat(d.reason).contains("action drift")
        allow(u, t, call(Action.ENABLE, Target.TORCH, identity = "torch"))
    }

    @Test
    fun `switch close-to-disable normalisation is the only accepted rewrite`() {
        val u = "关闭手电筒"
        val t = task(u).copy(action = Action.CLOSE)   // as written by a planner that keeps CLOSE
        allow(u, t, call(Action.DISABLE, Target.TORCH, identity = "torch"))
        // The same rewrite is not allowed for a timer.
        refuse("关闭倒计时", task("关闭倒计时"),
            call(Action.DISABLE, Target.TIMER))
    }

    @Test
    fun `target drift is refused`() {
        val u = "打开手电筒"
        val t = task(u)
        val d = refuse(u, t, call(Action.ENABLE, Target.WIFI, identity = "wifi"))
        assertThat(d.reason).contains("target drift")
    }

    @Test
    fun `ambiguity is a question, not an execution`() {
        val u = "取消"
        val t = SemanticTask(Action.CANCEL, Target.APP, ambiguity = "要取消哪个？", raw = u)
        val d = refuse(u, t, call(Action.CANCEL, Target.APP, identity = "com.tencent.mm"))
        assertThat(d.ask).isEqualTo("要取消哪个？")
    }

    @Test
    fun `multiple candidates are never resolved to the first one`() {
        val u = "打开商店"
        val t = task(u)
        val d = refuse(u, t, call(Action.OPEN, Target.APP,
            mapOf("name" to "商店", "candidates" to "3"), "com.android.vending"))
        assertThat(d.reason).contains("multiple candidates")
    }

    @Test
    fun `conditional mutation needs confirmation`() {
        val u = "如果蓝牙没开就打开蓝牙"
        val t = SemanticTask(
            Action.ENABLE, Target.BLUETOOTH,
            conditions = listOf("蓝牙没开"), raw = u,
        )
        val d = refuse(u, t, call(Action.ENABLE, Target.BLUETOOTH, identity = "bluetooth"))
        assertThat(d.reason).contains("conditional")
    }

    @Test
    fun `volume percent must match the utterance`() {
        val u = "音量调到 30%"
        val t = task(u)
        val d = refuse(u, t, call(Action.SET, Target.VOLUME, mapOf("percent" to "80")))
        assertThat(d.reason).contains("drift")
        allow(u, t, call(Action.SET, Target.VOLUME, mapOf("percent" to "30")))
    }

    // ── 7. 回归：闸门不是自我比较 ──────────────────────────────────────

    @Test
    fun `the gate is not a self-comparison regression`() {
        // The bug this replaces: `assertNotInverted(t.action, t.action)` compared a value with
        // itself, so it passed for every input. Each single-field drift below must be caught,
        // which is only possible because the gate compares independent intent and actual-call inputs.
        val u = "设一个五分钟的倒计时"
        val t = task(u)

        val baseline = call(Action.CREATE, Target.TIMER, mapOf("seconds" to "300"))
        allow(u, t, baseline)

        val drifts = listOf(
            baseline.copy(action = Action.CANCEL),
            baseline.copy(target = Target.ALARM),
            baseline.copy(params = mapOf("seconds" to "5")),
        )
        for (drift in drifts) {
            assertThat(IntentGate.check(u, t, drift).allowed).isFalse()
        }

        // Identity drift is checked where an identity exists: apps.
        val appU = "打开谷歌商店"
        val appTask = task(appU)
        val appCall = call(Action.OPEN, Target.APP,
            mapOf("name" to "谷歌商店"), "com.android.vending")
        allow(appU, appTask, appCall)
        assertThat(IntentGate.check(appU, appTask,
            appCall.copy(identity = "com.sec.android.app.samsungapps")).allowed).isFalse()
    }
}
