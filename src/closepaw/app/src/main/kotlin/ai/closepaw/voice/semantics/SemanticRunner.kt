package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.LocalCapabilities
import ai.closepaw.voice.tools.SystemPanelAutomator
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.TorchControl
import ai.closepaw.voice.tools.WifiControl
import android.content.Context
import android.util.Log

/**
 * Executes [SemanticTask]s.
 *
 * Every task goes through two independent checkpoints before a capability is invoked:
 *  1. [CapabilityGuard.check] — is (action, target) a real capability with the parameters it needs?
 *  2. [IntentGate.check] at the *final tool boundary* — with the arguments that will really be
 *     passed (defaults applied, app resolved to a concrete package). This is what catches drift:
 *     a cancel that becomes a create, a query that turns into a write, "五分钟" arriving as 5 s,
 *     "谷歌商店" landing on Galaxy Store, an app launch reported as an in-app feature.
 *
 * The previous version compared a task to itself (`assertNotInverted(t.action, t.action)`), which
 * could never fail; that call is gone. If the gate refuses, nothing executes and the reply says so.
 *
 * Verification lives in the capabilities themselves (they re-read device state); their `verified`
 * flag is what the assistant reports.
 */
object SemanticRunner {
    private const val TAG = "SemanticRunner"

    data class Outcome(val task: SemanticTask, val result: ToolResult)

    fun run(ctx: Context, text: String, sid: Long): String {
        val tasks = SemanticParser.parse(text)
        Log.i(TAG, "parsed: " + tasks.joinToString(" | ") { it.describe() })
        val outs = ArrayList<Outcome>()
        for (t in tasks) {
            if (!ai.closepaw.voice.VoiceSession.isCurrent(sid)) {
                Log.i(TAG, "session $sid superseded, stop")
                break
            }
            val verdict = CapabilityGuard.check(t)
            if (!verdict.ok) {
                Log.w(TAG, "blocked ${t.describe()}: ${verdict.reason}")
                outs.add(
                    Outcome(t, ToolResult(ToolStatus.BLOCKED, t.action.name.lowercase(),
                        verdict.ask ?: "这个操作我做不了", verdict.reason, false))
                )
                continue
            }
            val r = runCatching { dispatch(ctx, t) }.getOrElse {
                Log.e(TAG, "dispatch failed for ${t.describe()}", it)
                ToolResult.error(t.action.name.lowercase(), "这个操作出错了", it.javaClass.simpleName)
            }
            Log.i(
                TAG,
                "exec ${t.describe()} → status=${r.status} verified=${r.verified} detail=${r.detail}"
            )
            // remember successful mutations so "取消刚才的操作" works
            if (r.ok && t.action.mutates && t.target != Target.LAST_ACTION) {
                ActionMemory.record(t.action, t.target, t.params, r.speak)
            }
            outs.add(Outcome(t, r))
        }
        val merged = outs.joinToString("，") { it.result.speak }.trim('，', ' ')
        Log.i(TAG, "merged: $merged")
        return merged.ifBlank { "没有可以执行的指令" }
    }

    /**
     * Gate at the final boundary. [call] describes the capability invocation *with the arguments we
     * are about to pass*, not a copy of the task. A refused call never executes.
     */
    private fun guarded(
        t: SemanticTask,
        call: IntentGate.ToolCall,
        body: () -> ToolResult,
    ): ToolResult {
        val d = IntentGate.check(t.raw, t, call)
        if (!d.allowed) {
            Log.w(TAG, "gate refused ${t.describe()} → ${call.action}/${call.target}: ${d.reason}")
            return ToolResult(
                ToolStatus.BLOCKED, t.action.name.lowercase(),
                d.ask ?: "这个操作和你说的是反的，我没有执行",
                "gate: ${d.reason}", false
            )
        }
        return body()
    }

    private fun dispatch(ctx: Context, t: SemanticTask): ToolResult = when (t.target) {
        Target.TIMER -> when (t.action) {
            Action.CREATE -> {
                // No silent 300 s default: an unknown duration is a question, not a guess.
                val secs = t.params["seconds"]?.toIntOrNull()
                    ?: return ask(t, "要计时多久？")
                guarded(t, IntentGate.ToolCall(Action.CREATE, Target.TIMER, mapOf("seconds" to "$secs"))) {
                    ClockControl.createTimer(ctx, secs)
                }
            }
            Action.CANCEL, Action.CLOSE, Action.DELETE ->
                guarded(t, IntentGate.ToolCall(t.action, Target.TIMER)) { ClockControl.cancelTimer(ctx) }
            Action.PAUSE, Action.RESUME -> unsupported(t)
            else -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.TIMER)) {
                ClockControl.queryTimers(ctx)
            }
        }

        Target.ALARM -> {
            val h = t.params["hour"]?.toIntOrNull()
            val m = t.params["minute"]?.toIntOrNull() ?: 0
            val identity = h?.let { "alarm:$it:$m" }
            when (t.action) {
                Action.CREATE -> if (h == null) ask(t, "闹钟设在几点？") else guarded(
                    t, IntentGate.ToolCall(Action.CREATE, Target.ALARM,
                        mapOf("hour" to "$h", "minute" to "$m"), identity)
                ) { ClockControl.createAlarm(ctx, h, m) }
                Action.CLOSE, Action.CANCEL -> if (h == null) ask(t, "关哪个闹钟？") else guarded(
                    t, IntentGate.ToolCall(t.action, Target.ALARM,
                        mapOf("hour" to "$h", "minute" to "$m"), identity)
                ) { ClockControl.closeAlarm(ctx, h, m) }
                Action.DELETE -> if (h == null) ask(t, "删哪个闹钟？") else guarded(
                    t, IntentGate.ToolCall(Action.DELETE, Target.ALARM,
                        mapOf("hour" to "$h", "minute" to "$m"), identity)
                ) { ClockControl.deleteAlarm(ctx, h, m) }
                Action.QUERY -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.ALARM)) {
                    ClockControl.queryAlarms(ctx)
                }
                else -> unsupported(t)
            }
        }

        Target.APP -> when (t.action) {
            Action.OPEN, Action.CREATE -> {
                val name = t.params["name"] ?: t.raw
                // Resolve first so the gate can compare a concrete package, not a spoken label.
                val res = AppNavigator.resolve(ctx, name)
                res.ambiguity?.let {
                    return ToolResult(ToolStatus.BLOCKED, "app", it, res.why, false)
                }
                val app = res.app
                    ?: return ToolResult.unavailable("app", "没有找到这个应用", res.why)
                guarded(t, IntentGate.ToolCall(Action.OPEN, Target.APP,
                    mapOf("name" to name), app.packageName, app.label)) {
                    AppNavigator.openResolved(ctx, app, res.why)
                }
            }
            Action.QUERY -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.APP)) {
                ToolResult.ok("app", ai.closepaw.voice.tools.AppRegistry.listSpeech(ctx), verified = true)
            }
            else -> unsupported(t)
        }

        Target.APP_FEATURE -> {
            val name = t.params["name"] ?: ""
            val feature = t.params["feature"] ?: ""
            val res = AppNavigator.resolve(ctx, name)
            res.ambiguity?.let { return ToolResult(ToolStatus.BLOCKED, "app_feature", it, res.why, false) }
            val app = res.app ?: return ToolResult.unavailable("app_feature", "没有找到「$name」", res.why)
            guarded(t, IntentGate.ToolCall(Action.OPEN, Target.APP_FEATURE,
                mapOf("name" to name, "feature" to feature), app.packageName, app.label)) {
                AppNavigator.openFeatureResolved(ctx, app, feature, res.why)
            }
        }

        Target.TORCH -> when (t.action) {
            Action.ENABLE, Action.CREATE -> guarded(
                t, IntentGate.ToolCall(Action.ENABLE, Target.TORCH, identity = "torch")
            ) { TorchControl.set(ctx, true) }
            Action.DISABLE, Action.CLOSE, Action.CANCEL -> guarded(
                t, IntentGate.ToolCall(Action.DISABLE, Target.TORCH, identity = "torch")
            ) { TorchControl.set(ctx, false) }
            else -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.TORCH)) {
                LocalCapabilities.torchState(ctx)
            }
        }

        Target.WIFI -> when (t.action) {
            Action.ENABLE, Action.CREATE -> guarded(
                t, IntentGate.ToolCall(Action.ENABLE, Target.WIFI, identity = "wifi")
            ) { escalate(ctx, WifiControl.enable(ctx), SystemPanelAutomator.Target.WIFI, true) }
            Action.DISABLE, Action.CLOSE, Action.CANCEL -> guarded(
                t, IntentGate.ToolCall(Action.DISABLE, Target.WIFI, identity = "wifi")
            ) { escalate(ctx, WifiControl.disable(ctx), SystemPanelAutomator.Target.WIFI, false) }
            else -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.WIFI)) {
                LocalCapabilities.wifiState(ctx)
            }
        }

        Target.BLUETOOTH -> when (t.action) {
            Action.ENABLE, Action.CREATE -> guarded(
                t, IntentGate.ToolCall(Action.ENABLE, Target.BLUETOOTH, identity = "bluetooth")
            ) { escalate(ctx, LocalCapabilities.btSet(ctx, true), SystemPanelAutomator.Target.BLUETOOTH, true) }
            Action.DISABLE, Action.CLOSE, Action.CANCEL -> guarded(
                t, IntentGate.ToolCall(Action.DISABLE, Target.BLUETOOTH, identity = "bluetooth")
            ) { escalate(ctx, LocalCapabilities.btSet(ctx, false), SystemPanelAutomator.Target.BLUETOOTH, false) }
            else -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.BLUETOOTH)) {
                LocalCapabilities.btState(ctx)
            }
        }

        Target.VOLUME -> if (t.action == Action.SET) {
            val pct = t.params["percent"]?.toIntOrNull() ?: return ask(t, "音量调到多少？")
            guarded(t, IntentGate.ToolCall(Action.SET, Target.VOLUME, mapOf("percent" to "$pct"))) {
                LocalCapabilities.volumeSet(ctx, pct)
            }
        } else {
            guarded(t, IntentGate.ToolCall(Action.QUERY, Target.VOLUME)) {
                LocalCapabilities.volumeState(ctx)
            }
        }

        Target.BATTERY -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.BATTERY)) {
            LocalCapabilities.battery(ctx)
        }
        Target.TIME -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.TIME)) {
            LocalCapabilities.time()
        }
        Target.DEVICE -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.DEVICE)) {
            LocalCapabilities.deviceState(ctx)
        }
        Target.NETWORK -> guarded(t, IntentGate.ToolCall(Action.QUERY, Target.NETWORK)) {
            LocalCapabilities.networkState(ctx)
        }

        Target.CAMERA -> guarded(t, IntentGate.ToolCall(t.action, Target.CAMERA, identity = "camera")) {
            LocalCapabilities.cameraOpen(
                ctx, video = t.raw.contains("录像") || t.raw.contains("录制")
            )
        }
        Target.CALL -> guarded(
            t, IntentGate.ToolCall(t.action, Target.CALL, identity = t.params["name"] ?: t.raw)
        ) {
            LocalCapabilities.call(ctx, t.params["name"] ?: t.raw)
        }

        Target.SETTINGS_PAGE -> {
            val page = t.params["name"] ?: t.raw
            guarded(t, IntentGate.ToolCall(Action.OPEN, Target.SETTINGS_PAGE, mapOf("name" to page))) {
                ai.closepaw.voice.tools.IntentActions.openSettingsPage(ctx, page)
            }
        }
        Target.MAPS -> {
            val place = t.params["name"] ?: t.raw
            guarded(t, IntentGate.ToolCall(Action.OPEN, Target.MAPS, mapOf("name" to place))) {
                ai.closepaw.voice.tools.IntentActions.maps(ctx, place)
            }
        }
        Target.WEB -> {
            val q = t.params["name"] ?: t.raw
            guarded(t, IntentGate.ToolCall(t.action, Target.WEB, mapOf("name" to q))) {
                ai.closepaw.voice.tools.IntentActions.web(ctx, q)
            }
        }

        Target.LAST_ACTION -> guarded(t, IntentGate.ToolCall(Action.CANCEL, Target.LAST_ACTION)) {
            ActionMemory.undo(ctx)
        }
        Target.UNKNOWN -> ask(t, t.ambiguity ?: "没听明白，能再说一次吗")
    }

    private fun escalate(
        ctx: Context,
        direct: ToolResult,
        target: SystemPanelAutomator.Target,
        on: Boolean,
    ): ToolResult {
        if (direct.status != ToolStatus.SYSTEM_RESTRICTED) return direct
        Log.i(TAG, "restricted → accessibility panel fallback for $target")
        return SystemPanelAutomator.setEnabled(ctx, target, on)
    }

    private fun ask(t: SemanticTask, question: String) = ToolResult(
        ToolStatus.BLOCKED, t.action.name.lowercase(), question,
        "needs clarification: ${t.raw}", false
    )

    private fun unsupported(t: SemanticTask) = ToolResult(
        ToolStatus.BLOCKED, t.action.name.lowercase(),
        "这个操作我做不到", "unsupported ${t.action}/${t.target}", false
    )
}
