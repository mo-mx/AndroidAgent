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
 * Every task goes through [CapabilityGuard] first, and the dispatcher is a table of
 * (action, target) → capability, so a CANCEL request has no code path that can reach a creating
 * call. Verification lives in the capabilities themselves (they re-read device state), and their
 * `verified` flag is what the assistant reports.
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
                    Outcome(t, ToolResult(ToolStatus.DEVICE_UNAVAILABLE, t.action.name.lowercase(),
                        verdict.ask ?: "这个操作我做不了", verdict.reason, null))
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

    private fun dispatch(ctx: Context, t: SemanticTask): ToolResult {
        // Hard safety net: a cancel/close must never produce a create call.
        if (!CapabilityGuard.assertNotInverted(t.action, t.action)) {
            return ToolResult.error(t.action.name.lowercase(), "语义与动作不一致，已阻止执行", "guard")
        }
        return when (t.target) {
            Target.TIMER -> when (t.action) {
                Action.CREATE -> ClockControl.createTimer(ctx, t.params["seconds"]?.toIntOrNull() ?: 300)
                Action.CANCEL, Action.CLOSE, Action.DELETE -> ClockControl.cancelTimer(ctx)
                Action.QUERY -> ClockControl.queryTimers(ctx)
                else -> unsupported(t)
            }
            Target.ALARM -> {
                val h = t.params["hour"]?.toIntOrNull()
                val m = t.params["minute"]?.toIntOrNull() ?: 0
                when (t.action) {
                    Action.CREATE -> if (h == null) ask(t, "闹钟设在几点？") else ClockControl.createAlarm(ctx, h, m)
                    Action.CLOSE -> if (h == null) ask(t, "关哪个闹钟？") else ClockControl.closeAlarm(ctx, h, m)
                    Action.DELETE -> if (h == null) ask(t, "删哪个闹钟？") else ClockControl.deleteAlarm(ctx, h, m)
                    Action.QUERY -> ClockControl.queryAlarms(ctx)
                    else -> unsupported(t)
                }
            }
            Target.APP -> when (t.action) {
                Action.OPEN -> AppNavigator.open(ctx, t.params["name"] ?: t.raw)
                Action.QUERY -> ToolResult.ok("app", ai.closepaw.voice.tools.AppRegistry.listSpeech(ctx), verified = true)
                else -> unsupported(t)
            }
            Target.APP_FEATURE -> AppNavigator.openFeature(
                ctx, t.params["name"] ?: "", t.params["feature"] ?: ""
            )
            Target.TORCH -> when (t.action) {
                Action.ENABLE -> TorchControl.set(ctx, true)
                Action.DISABLE -> TorchControl.set(ctx, false)
                else -> LocalCapabilities.torchState(ctx)
            }
            Target.WIFI -> when (t.action) {
                Action.ENABLE -> escalate(ctx, WifiControl.enable(ctx), SystemPanelAutomator.Target.WIFI, true)
                Action.DISABLE -> escalate(ctx, WifiControl.disable(ctx), SystemPanelAutomator.Target.WIFI, false)
                else -> LocalCapabilities.wifiState(ctx)
            }
            Target.BLUETOOTH -> when (t.action) {
                Action.ENABLE -> escalate(ctx, LocalCapabilities.btSet(ctx, true), SystemPanelAutomator.Target.BLUETOOTH, true)
                Action.DISABLE -> escalate(ctx, LocalCapabilities.btSet(ctx, false), SystemPanelAutomator.Target.BLUETOOTH, false)
                else -> LocalCapabilities.btState(ctx)
            }
            Target.VOLUME -> if (t.action == Action.SET) {
                LocalCapabilities.volumeSet(ctx, t.params["percent"]?.toIntOrNull() ?: 50)
            } else {
                LocalCapabilities.volumeState(ctx)
            }
            Target.BATTERY -> LocalCapabilities.battery(ctx)
            Target.TIME -> LocalCapabilities.time()
            Target.DEVICE -> LocalCapabilities.deviceState(ctx)
            Target.NETWORK -> LocalCapabilities.networkState(ctx)
            Target.CAMERA -> LocalCapabilities.cameraOpen(ctx, video = t.raw.contains("录像") || t.raw.contains("录制"))
            Target.CALL -> LocalCapabilities.call(ctx, t.params["name"] ?: t.raw)
            Target.SETTINGS_PAGE -> ai.closepaw.voice.tools.IntentActions.openSettingsPage(ctx, t.params["name"] ?: t.raw)
            Target.MAPS -> ai.closepaw.voice.tools.IntentActions.maps(ctx, t.params["name"] ?: t.raw)
            Target.WEB -> ai.closepaw.voice.tools.IntentActions.web(ctx, t.params["name"] ?: t.raw)
            Target.LAST_ACTION -> ActionMemory.undo(ctx)
            Target.UNKNOWN -> ask(t, t.ambiguity ?: "没听明白，能再说一次吗")
        }
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
        ToolStatus.DEVICE_UNAVAILABLE, t.action.name.lowercase(), question,
        "needs clarification: ${t.raw}", null
    )

    private fun unsupported(t: SemanticTask) = ToolResult(
        ToolStatus.DEVICE_UNAVAILABLE, t.action.name.lowercase(),
        "这个操作我做不到", "unsupported ${t.action}/${t.target}", null
    )
}
