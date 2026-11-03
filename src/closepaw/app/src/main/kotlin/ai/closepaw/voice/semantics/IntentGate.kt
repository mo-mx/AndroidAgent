package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ChineseTime

/**
 * Final-boundary semantic gate.
 *
 * The bug this exists to kill: the previous "safety net" compared a task against itself
 * (`assertNotInverted(t.action, t.action)`), which is a no-op — it produced "pass" for every
 * input, including a cancel that ends up creating something. This gate instead compares three
 * *different* things:
 *
 *  1. what the user said            → [utterance]
 *  2. what the planner decided     → [SemanticTask] (`requested`)
 *  3. what the tool boundary is about to call → [ToolCall] (`call`), built from the **effective
 *     arguments** (defaults applied), not from the task
 * `expectedState` is descriptive metadata only; this gate does NOT verify post-execution state.
 *
 * It is deliberately pure Kotlin (no Android imports) so the whole rule set is unit-testable on
 * the JVM, and it runs *before* the capability is invoked: a failed check never executes.
 *
 * Rules (all must hold):
 *  - A cancel/close/delete/disable request may never become a create/enable call.
 *  - A query may never carry a mutation.
 *  - action, target, target identity and every parsed parameter must survive unchanged, except
 *    for the documented CLOSE→DISABLE / OPEN→ENABLE normalisation on switches.
 *  - A duration/clock-time/percent present in the utterance must match the argument exactly.
 *    "五分钟" is 300 s; 5 s is a block, not a rounding error. If the utterance has no duration
 *    at all, we refuse to invent one (no `?: 300`).
 *  - Ambiguity, multiple candidates and unresolved targets are questions, never executions, and
 *    we never fall back to the first candidate.
 */
object IntentGate {

    data class AppIdentity(val packageName: String, val label: String, val aliases: List<String> = emptyList())

    /** What the tool boundary is actually about to do, with the arguments it will really pass. */
    data class ToolCall(
        val action: Action,
        val target: Target,
        val params: Map<String, String> = emptyMap(),
        /** Concrete identity: package name for apps, "alarm:H:M" for alarms, etc. */
        val identity: String? = null,
        val label: String = "",
        /** Independent installed-app inventory, not a claim copied from the chosen result. */
        val apps: List<AppIdentity> = emptyList(),
    )

    data class Decision(val allowed: Boolean, val reason: String = "", val ask: String? = null) {
        companion object {
            val OK = Decision(true)
            fun block(reason: String, ask: String? = null) = Decision(false, reason, ask)
        }
    }

    private val cancelish = setOf(Action.CANCEL, Action.CLOSE, Action.DELETE, Action.DISABLE)
    private val creating = setOf(Action.CREATE, Action.ENABLE)
    private val switches = setOf(Target.TORCH, Target.WIFI, Target.BLUETOOTH)

    /** Utterance hints that pin a specific app when two stores/aliases would otherwise tie. */
    private val storeHints: List<Pair<List<String>, String>> = listOf(
        listOf("谷歌商店", "谷歌市场", "play商店", "google play", "googleplay", "play 商店") to "com.android.vending",
        listOf("三星商店", "galaxy store", "galaxy商店", "应用商店", "应用市场", "软件商店") to "com.sec.android.app.samsungapps",
    )

    fun check(utterance: String, requested: SemanticTask, call: ToolCall): Decision {
        val said = utterance.trim()

        // 0) Ambiguity is a question, not an execution.
        requested.ambiguity?.let { return Decision.block("ambiguity: $it", it) }
        call.params["candidates"]?.toIntOrNull()?.let {
            if (it > 1) return Decision.block("multiple candidates ($it)", "匹配到多个目标，要哪一个？")
        }

        // 1) Inversion: destructive request must not reach a creating call.
        if (requested.action in cancelish && call.action in creating) {
            return Decision.block(
                "inversion: requested ${requested.action}/${requested.target} but call is " +
                    "${call.action}/${call.target}",
                "这个操作和你说的是反的，我没有执行"
            )
        }

        // 2) A query must not mutate.
        if (requested.action == Action.QUERY && call.action.mutates) {
            return Decision.block(
                "query mutated: ${call.action}/${call.target}",
                "这是查询，我不会改任何状态"
            )
        }

        // 3) Action drift.
        if (!sameAction(requested.action, call.action, requested.target)) {
            return Decision.block(
                "action drift: requested ${requested.action}, call ${call.action}",
                "我理解成了「${requested.action}」，但工具要做「${call.action}」，已停止"
            )
        }

        // 4) Target drift.
        if (requested.target != call.target) {
            return Decision.block(
                "target drift: requested ${requested.target}, call ${call.target}",
                "目标对不上（${requested.target} vs ${call.target}），已停止"
            )
        }

        // 5) Identity: the concrete object must be the one the user named.
        requested.targetIdentity?.let { identity ->
            if (identity != call.identity) return Decision.block("planned identity drift", "目标对象和规划对不上，我没有执行")
        }
        expectedIdentity(requested, said)?.let { expected ->
            if (call.identity != expected) {
                return Decision.block(
                    "identity mismatch: expected $expected, call ${call.identity ?: "<unresolved>"}",
                    "你说的是另一个对象，我没有执行"
                )
            }
        }
        if (requested.target in setOf(Target.APP, Target.APP_FEATURE) && requested.action != Action.QUERY) {
            if (call.identity.isNullOrBlank()) return Decision.block("unresolved app identity", "没确认是哪个应用，先不开")
            val original = SemanticParser.parse(said).singleOrNull()
                ?: return Decision.block("app utterance ambiguous", "要打开哪个应用？")
            val name = original.params["name"]
                ?: return Decision.block("app name missing from utterance", "要打开哪个应用？")
            if (name != requested.params["name"] || original.params["feature"] != requested.params["feature"]) {
                return Decision.block("app name/feature drift from utterance", "应用或功能和原话对不上，我没有执行")
            }
            fun norm(s: String) = s.lowercase().replace(" ", "")
            val q = norm(name)
            val pinned = storeHints.firstOrNull { (keys, _) -> keys.any { norm(it) == q } }?.second
            val exact = call.apps.filter { a ->
                if (pinned != null) a.packageName == pinned
                else norm(a.label) == q || a.aliases.any { norm(it) == q } || norm(a.packageName) == q
            }
            // Partial names are deliberately not proof; a unique exact name/alias/package is required.
            if (exact.size != 1 || exact.single().packageName != call.identity) {
                return Decision.block("app identity not proven: name=$name matches=${exact.size} package=${call.identity}",
                    "无法唯一核对你指定的应用，请确认应用名称")
            }
        }

        // 6) Parameters must survive verbatim (this is where 5 min → 5 s used to slip through).
        for ((k, v) in requested.params) {
            val actual = call.params[k] ?: return Decision.block(
                "missing call parameter: $k", "工具丢失了参数（$k），我没有执行")
            if (actual != v) {
                return Decision.block(
                    "param drift: $k expected $v, call $actual",
                    "参数对不上（$k：$v 不是 $actual），我没有执行"
                )
            }
        }

        // 7) Units that come from the utterance itself.
        paramsFromUtterance(requested, said)?.let { (k, expected) ->
            val actual = call.params[k]
            if (expected == null) {
                // The utterance states no such value, so the tool must not supply one.
                if (actual != null) {
                    return Decision.block(
                        "invented $k=$actual (utterance states none)",
                        "你没说具体数值，我没有执行"
                    )
                }
            } else if (actual != expected) {
                return Decision.block(
                    "utterance/argument mismatch: $k said $expected, call ${actual ?: "<missing>"}",
                    "时间和参数对不上，我没有执行"
                )
            }
        }

        // 8) Conditions attached to a mutating task need an explicit confirmation.
        if (requested.conditions.isNotEmpty() && requested.action.mutates) {
            return Decision.block(
                "conditional mutation not confirmed: ${requested.conditions}",
                "你加了条件，要我直接改吗？"
            )
        }

        // A planner and tool agreeing with each other is not evidence they agree with the user.
        val original = SemanticParser.parse(said).singleOrNull()
            ?: return Decision.block("original intent ambiguous", "请一次明确一个操作")
        if (original.target != requested.target || !sameAction(original.action, requested.action, original.target)) {
            return Decision.block("original intent drift: ${original.action}/${original.target} vs ${requested.action}/${requested.target}",
                "规划和你原话的动作或对象对不上，我没有执行")
        }
        return Decision.OK
    }

    /** CLOSE→DISABLE / OPEN→ENABLE on switches is the only accepted rewrite. */
    private fun sameAction(requested: Action, actual: Action, target: Target): Boolean {
        if (requested == actual) return true
        if (target !in switches) return false
        return (requested == Action.CLOSE && actual == Action.DISABLE) ||
            (requested == Action.OPEN && actual == Action.ENABLE) ||
            (requested == Action.DISABLE && actual == Action.CLOSE)
    }

    /** The identity the utterance itself implies, when it pins one. Null = the planner decides. */
    private fun expectedIdentity(requested: SemanticTask, said: String): String? {
        if (requested.target == Target.APP || requested.target == Target.APP_FEATURE) {
            val q = said.lowercase().replace(" ", "")
            storeHints.firstOrNull { (keys, _) -> keys.any { q.contains(it.replace(" ", "")) } }
                ?.let { (_, pkg) -> return pkg }
        }
        if (requested.target == Target.ALARM) {
            val named = ChineseTime.clockTime(said) ?: clockFromParams(requested.params)
            if (named != null) return "alarm:${named.first}:${named.second}"
        }
        return requested.targetIdentity
    }

    private fun clockFromParams(p: Map<String, String>): Pair<Int, Int>? {
        val h = p["hour"]?.toIntOrNull() ?: return null
        return h to (p["minute"]?.toIntOrNull() ?: 0)
    }

    /**
     * The one argument that must equal what the utterance said.
     * Returns null when the task carries no unit-bearing value (e.g. "取消倒计时");
     * returns (key, null) when the utterance states no value for a unit that must be explicit.
     */
    private fun paramsFromUtterance(requested: SemanticTask, said: String): Pair<String, String?>? = when {
        requested.target == Target.TIMER && (requested.action == Action.CREATE || ChineseTime.durationSeconds(said) != null) -> {
            val secs = ChineseTime.durationSeconds(said)
            // No duration in the utterance → we refuse to guess one.
            "seconds" to secs?.toString()
        }
        requested.target == Target.ALARM && requested.action != Action.QUERY -> {
            val t = ChineseTime.clockTime(said) ?: return "hour" to null
            // Compare the hour; minute is checked through identity ("alarm:H:M").
            "hour" to t.first.toString()
        }
        requested.target == Target.VOLUME && requested.action == Action.SET -> {
            val pct = Regex("(\\d{1,3})\\s*%").find(said)?.groupValues?.get(1)
            "percent" to pct
        }
        else -> null
    }
}
