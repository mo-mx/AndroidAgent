package ai.closepaw.voice

import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.TorchControl
import ai.closepaw.voice.tools.WifiControl
import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.IntentGate
import ai.closepaw.voice.semantics.SemanticTask
import ai.closepaw.voice.semantics.Target as SemanticTarget
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline command router — the "no network at all" path.
 *
 * Deliberately rule-based, not model-based: when the phone is locked, offline, or the main
 * agent is unreachable, these commands must still work and must answer in well under a second.
 * Anything not matched here is handed to the agent (which needs network + unlock).
 *
 * Every handler returns a [Reply] carrying the spoken sentence *and* the structured
 * [ToolResult] (status / verified / detail), and logs the full chain
 * intent → tool → status so a failure can be attributed instead of guessed at.
 */
object LocalCommandRouter {
    private const val TAG = "LocalCmd"

    data class Reply(
        val handled: Boolean,
        val speech: String,
        /** true → keep listening for another command; false → close the voice UI */
        val keepListening: Boolean = true,
        val result: ToolResult? = null,
    )

    private val stopWords = listOf("退出", "结束", "关闭助手", "不用了", "再见", "停")
    private val batteryWords = listOf(
        "电量", "电池", "多少电", "剩多少电", "还有电", "电够不够", "百分之多少", "剩几格"
    )
    private val timeWords = listOf("几点", "时间", "现在钟点")
    private val helpWords = listOf("帮助", "能做什么", "会什么")
    private val cancelTaskWords = listOf("取消", "停止任务", "算了")
    /** Words that mean the user is cancelling a *thing*, not the assistant session. */
    private val thingWords = listOf("倒计时", "计时", "定时器", "闹钟", "闹铃")

    private val torchOff = listOf(
        "关闭手电筒", "关手电筒", "关掉手电筒", "手电筒关", "把灯关", "关灯", "别亮了", "关掉灯"
    )
    private val torchOn = listOf(
        "打开手电筒", "开启手电筒", "开手电筒", "手电筒打开", "把手电筒", "开一下手电筒",
        "开灯", "打开灯", "手电筒", "照亮"
    )

    private val wifiOn = listOf("打开wifi", "开启wifi", "打开wi-fi", "连接wifi", "开wifi", "无线网络")
    private val wifiOff = listOf("关闭wifi", "关掉wifi", "关闭wi-fi", "关wifi")

    fun shouldStop(text: String): Boolean = stopWords.any { text.contains(it) }

    fun handle(ctx: Context, raw: String): Reply {
        val text = raw.replace(" ", "").replace("　", "")
        val low = text.lowercase()

        if (shouldStop(text)) {
            return log("stop", Reply(true, "好的，已退出", keepListening = false))
        }
        if (batteryWords.any { text.contains(it) }) {
            return log("battery", Reply(true, batterySpeech(ctx)))
        }
        if (timeWords.any { text.contains(it) }) {
            val now = SimpleDateFormat("HH 点 mm 分", Locale.CHINA).format(Date())
            return log("time", Reply(true, "现在是 $now"))
        }
        if (helpWords.any { text.contains(it) }) {
            return log(
                "help",
                Reply(
                    true,
                    "离线可用：电量、时间、手电筒开关、打开 Wi-Fi 设置、打开相机、打开设置。其他任务需要联网。"
                )
            )
        }
        // "取消五分钟倒计时" is a clock command, not "stop talking": let it fall through to the
        // semantic layer instead of answering 已取消 while nothing was cancelled.
        if (cancelTaskWords.any { text.contains(it) } && thingWords.none { text.contains(it) }) {
            return log("cancel", Reply(true, "已取消，还有什么要做的"))
        }

        // Torch: off must be tested before on — "关闭手电筒" also contains "手电筒".
        if (torchOff.any { text.contains(it) }) {
            return gated(ctx, text, Action.DISABLE, SemanticTarget.TORCH, keepListeningOnSuccess = true) {
                TorchControl.set(ctx, false)
            }.also { log("torch", it) }
        }
        if (torchOn.any { text.contains(it) }) {
            return gated(ctx, text, Action.ENABLE, SemanticTarget.TORCH, keepListeningOnSuccess = true) {
                TorchControl.set(ctx, true)
            }.also { log("torch", it) }
        }

        // Turning Wi-Fi *off* must never call enable(). Android 10+ blocks both directions for
        // normal apps, so each direction reports SYSTEM_RESTRICTED with its own panel fallback.
        if (wifiOff.any { low.contains(it) }) {
            return gated(ctx, text, Action.DISABLE, SemanticTarget.WIFI, keepListeningOnSuccess = false) {
                WifiControl.disable(ctx)
            }.also { log("wifi_off", it) }
        }
        if (wifiOn.any { low.contains(it) }) {
            return gated(ctx, text, Action.ENABLE, SemanticTarget.WIFI, keepListeningOnSuccess = false) {
                WifiControl.enable(ctx)
            }.also { log("wifi_on", it) }
        }

        if (text.contains("打开设置") || text.contains("设置")) {
            return log("open_settings", launch(ctx, "com.android.settings", "正在打开设置"))
        }
        if (text.contains("打开相机") || text.contains("相机")) {
            return log("open_camera", launch(ctx, "com.sec.android.app.camera", "正在打开相机"))
        }
        return log("unhandled", Reply(false, ""))
    }

    private fun log(intent: String, reply: Reply): Reply {
        val r = reply.result
        Log.i(
            TAG,
            "intent=$intent tool=${r?.tool ?: "-"} status=${r?.status ?: "-"} " +
                "verified=${r?.verified} detail=${r?.detail ?: "-"} speech=\"${reply.speech.take(40)}\""
        )
        if (r != null && r.status != ToolStatus.SUCCESS) {
            Log.w(TAG, "intent=$intent did NOT succeed: ${r.status} — ${r.detail}")
        }
        return reply
    }

    private fun launch(ctx: Context, pkg: String, okSpeech: String): Reply {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return Reply(
                true, "这台手机没有找到对应的应用", keepListening = true,
                result = ToolResult.unavailable("launch", "找不到应用", pkg)
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Reply(
                true, okSpeech, keepListening = false,
                result = ToolResult.ok("launch", okSpeech, verified = true, detail = pkg)
            )
        } catch (t: Throwable) {
            Reply(
                true, "现在打不开这个应用，可能屏幕已锁定", keepListening = true,
                result = ToolResult.error("launch", "打不开应用", t.javaClass.simpleName)
            )
        }
    }

    /**
     * Same final-boundary check as the semantic runner, so this offline path cannot drift from the
     * online one: the requested action must match the call before it runs.
     */
    private fun gated(
        ctx: Context,
        utterance: String,
        action: Action,
        target: SemanticTarget,
        keepListeningOnSuccess: Boolean,
        body: () -> ToolResult,
    ): Reply {
        val task = SemanticTask(action, target, raw = utterance)
        val call = IntentGate.ToolCall(action, target, identity = target.name.lowercase())
        val d = IntentGate.check(utterance, task, call)
        if (!d.allowed) {
            Log.w(TAG, "gate refused $action/$target: ${d.reason}")
            val r = ToolResult.blocked(action.name.lowercase(), d.ask ?: "这个操作和你说的是反的，我没有执行", d.reason)
            return Reply(true, r.speak, keepListening = true, result = r)
        }
        val r = body()
        return Reply(true, r.speak, keepListening = keepListeningOnSuccess || !r.ok, result = r)
    }

    /** Battery in a human sentence, including charging state. */
    fun batterySpeech(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val saver = if (pm.isPowerSaveMode) "，省电模式开着" else ""
        val screen = if (pm.isInteractive) "" else "，屏幕是熄的"
        val chargeText = if (charging) "，正在充电" else ""
        return "还剩百分之$pct$chargeText$saver$screen"
    }

    fun isAccessibilityForAgent(ctx: Context): Boolean = runCatching {
        (Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "")
            .contains("ai.closepaw")
    }.getOrDefault(false)
}
