package ai.closepaw.voice.semantics

/**
 * Structured task model — the only thing the executor is allowed to act on.
 *
 * Design rule that fixes the reported bugs: nothing may be executed straight from a keyword.
 * Free text is first turned into (action, target, params), then checked by [CapabilityGuard]
 * against what the corresponding tool can actually do. A noun ("倒计时") never implies a verb:
 * "取消五分钟倒计时" parses to (CANCEL, TIMER), and the CANCEL path has no way to reach the
 * creating API, so it is structurally impossible to create a timer while cancelling one.
 */
enum class Action {
    CREATE, OPEN, CLOSE, CANCEL, DELETE, QUERY, PAUSE, RESUME, ENABLE, DISABLE, SET, UNKNOWN;

    val mutates: Boolean
        get() = this in setOf(CREATE, CLOSE, CANCEL, DELETE, PAUSE, RESUME, ENABLE, DISABLE, SET)
}

enum class Target {
    TIMER, ALARM, APP, APP_FEATURE, TORCH, WIFI, BLUETOOTH, VOLUME, BATTERY, TIME,
    DEVICE, NETWORK, CAMERA, CALL, SETTINGS_PAGE, MAPS, WEB, LAST_ACTION, UNKNOWN
}

data class SemanticTask(
    val action: Action,
    val target: Target,
    val params: Map<String, String> = emptyMap(),
    val conditions: List<String> = emptyList(),
    val expectedState: String = "",
    /** Concrete identity: package for apps, "timer#id" for timers, "alarm:HH:MM" for alarms. */
    val targetIdentity: String? = null,
    val confidence: Double = 1.0,
    /** Non-null → the planner must ask instead of executing. */
    val ambiguity: String? = null,
    val raw: String = "",
) {
    fun describe(): String =
        "$action/$target${if (params.isEmpty()) "" else params.toString()}" +
            (targetIdentity?.let { " id=$it" } ?: "") + (ambiguity?.let { " ?=$it" } ?: "")
}

object CapabilityGuard {
    data class Verdict(val ok: Boolean, val reason: String = "", val ask: String? = null)

    private val allowed: Map<Target, Set<Action>> = mapOf(
        Target.TIMER to setOf(Action.CREATE, Action.CANCEL, Action.CLOSE, Action.QUERY, Action.PAUSE, Action.RESUME, Action.DELETE),
        Target.ALARM to setOf(Action.CREATE, Action.CLOSE, Action.DELETE, Action.QUERY, Action.ENABLE, Action.DISABLE),
        Target.APP to setOf(Action.OPEN, Action.CLOSE, Action.QUERY),
        Target.APP_FEATURE to setOf(Action.OPEN),
        Target.TORCH to setOf(Action.ENABLE, Action.DISABLE, Action.SET, Action.QUERY),
        Target.WIFI to setOf(Action.ENABLE, Action.DISABLE, Action.QUERY),
        Target.BLUETOOTH to setOf(Action.ENABLE, Action.DISABLE, Action.QUERY),
        Target.VOLUME to setOf(Action.SET, Action.QUERY, Action.ENABLE, Action.DISABLE),
        Target.BATTERY to setOf(Action.QUERY),
        Target.TIME to setOf(Action.QUERY),
        Target.DEVICE to setOf(Action.QUERY),
        Target.NETWORK to setOf(Action.QUERY),
        Target.CAMERA to setOf(Action.OPEN, Action.CREATE),
        Target.CALL to setOf(Action.OPEN, Action.CREATE),
        Target.SETTINGS_PAGE to setOf(Action.OPEN),
        Target.MAPS to setOf(Action.OPEN),
        Target.WEB to setOf(Action.OPEN, Action.QUERY),
        Target.LAST_ACTION to setOf(Action.CANCEL),
    )

    /**
     * Rejects semantically impossible tasks before any tool runs.
     * Two kinds of failure are caught here:
     *  1. the (action, target) pair does not exist in the capability table;
     *  2. a mutating action is missing the parameters it needs, or the task is ambiguous.
     */
    fun check(t: SemanticTask): Verdict {
        if (t.ambiguity != null) {
            return Verdict(false, "ambiguous: ${t.ambiguity}", ask = t.ambiguity)
        }
        val permitted = allowed[t.target]
            ?: return Verdict(false, "unsupported target ${t.target}")
        if (t.action !in permitted) {
            return Verdict(
                false,
                "action ${t.action} not available for ${t.target}",
                ask = "这个操作我做不到（${t.action} ${t.target}），换个说法？"
            )
        }
        // Parameter requirements for creation
        if (t.action == Action.CREATE && t.target == Target.TIMER && t.params["seconds"] == null) {
            return Verdict(false, "timer without duration", ask = "要计时多久？")
        }
        if (t.action == Action.CREATE && t.target == Target.ALARM &&
            (t.params["hour"] == null || t.params["minute"] == null)
        ) {
            return Verdict(false, "alarm without time", ask = "闹钟设在几点？")
        }
        if (t.action == Action.OPEN && t.target == Target.APP_FEATURE && t.params["feature"].isNullOrBlank()) {
            return Verdict(false, "feature without name", ask = "要打开哪个功能？")
        }
        // A QUERY must never carry a mutating side effect.
        if (t.action == Action.QUERY && t.expectedState.startsWith("mutate:")) {
            return Verdict(false, "query must not mutate")
        }
        return Verdict(true)
    }
}
