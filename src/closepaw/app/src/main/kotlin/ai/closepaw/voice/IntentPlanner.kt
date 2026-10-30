package ai.closepaw.voice

import ai.closepaw.voice.tools.LocalCapabilities
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.AppRegistry
import ai.closepaw.voice.tools.ChineseTime
import ai.closepaw.voice.tools.IntentActions
import ai.closepaw.voice.tools.SystemPanelAutomator
import ai.closepaw.voice.tools.TorchControl
import ai.closepaw.voice.tools.WifiControl
import android.content.Context
import android.util.Log

/**
 * Splits one spoken sentence into an ordered list of concrete device tasks and runs them.
 *
 * This replaces "continuous conversation": one wake-up, one utterance, N sub-tasks, one merged
 * spoken answer, then the session closes.
 *
 * Segmentation is by connectors *and* by meaning — a comma alone is not treated as a task
 * boundary when the clause continues the previous verb ("打开手电筒，然后关闭它" is two tasks;
 * "看看蓝牙有没有开，如果没开就告诉我" is one query plus a conditional follow-up that must not
 * run as an independent command).
 */
object IntentPlanner {
    private const val TAG = "IntentPlanner"

    enum class Kind {
        BATTERY, TIME, TORCH_ON, TORCH_OFF, TORCH_STATE,
        WIFI_STATE, WIFI_ON, WIFI_OFF,
        BT_STATE, BT_ON, BT_OFF,
        NETWORK, VOLUME_STATE, VOLUME_SET,
        DEVICE, CAMERA_PHOTO, CAMERA_VIDEO, CALL,
        TIMER, ALARM, SHOW_ALARMS, SETTINGS_PAGE, OPEN_APP, MAPS, WEB, APP_LIST,
        HELP, STOP, UNKNOWN,
    }

    data class Task(
        val kind: Kind,
        val raw: String,
        val arg: String = "",
        /** true → only speak if the condition holds (e.g. "如果没开就告诉我"). */
        val conditional: Boolean = false,
    )

    private val connectors = listOf(
        "然后", "接着", "随后", "再", "并且", "同时", "还要", "顺便", "另外", "以及", "最后"
    )
    private val splitChars = charArrayOf('，', ',', '。', '；', ';', '、', '\n', '！', '!', '？', '?')

    /** Split into clauses: punctuation and connector words, but keep connector order intact. */
    fun split(text: String): List<String> {
        var s = text.replace(" ", "").replace("　", "")
        for (c in connectors) s = s.replace(c, "|")
        // punctuation is also a boundary
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in splitChars) '|' else ch)
        return sb.toString().split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun plan(text: String): List<Task> {
        val clauses = split(text)
        val out = ArrayList<Task>(clauses.size)
        var lastKind: Kind? = null
        for (raw in clauses) {
            if (raw.length <= 1 && (raw == "它" || raw == "他")) continue
            val t = classify(raw, lastKind)
            if (t.kind != Kind.UNKNOWN) lastKind = t.kind
            out.add(t)
        }
        // A single clause can still contain two commands joined by "和"/"跟" ("查电量和打开手电筒")
        return resolveDependencies(expandConjunctions(out))
    }

    private fun expandConjunctions(tasks: List<Task>): List<Task> {
        val out = ArrayList<Task>(tasks.size)
        for (t in tasks) {
            if (t.kind != Kind.UNKNOWN) {
                out.add(t); continue
            }
            if (t.raw.contains("和") || t.raw.contains("跟")) {
                val parts = t.raw.split("和", "跟").filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    var last: Kind? = out.lastOrNull()?.kind
                    for (p in parts) {
                        val sub = classify(p, last)
                        if (sub.kind != Kind.UNKNOWN) last = sub.kind
                        out.add(sub)
                    }
                    continue
                }
            }
            out.add(t)
        }
        return out
    }

    /**
     * "打开相机，录五秒视频" is one dependent action chain, not two independent launches:
     * opening the camera and then immediately launching the video intent would throw away the
     * first one. The generic "open camera" step is merged into the recording step.
     */
    private fun resolveDependencies(tasks: List<Task>): List<Task> {
        val out = ArrayList<Task>(tasks.size)
        var i = 0
        while (i < tasks.size) {
            val t = tasks[i]
            val next = tasks.getOrNull(i + 1)
            if (t.kind == Kind.CAMERA_PHOTO && next?.kind == Kind.CAMERA_VIDEO) {
                out.add(next.copy(raw = t.raw + "，" + next.raw))
                i += 2
                continue
            }
            out.add(t)
            i++
        }
        return out
    }

    private fun classify(raw: String, prev: Kind?): Task {
        val t = raw
        val low = t.lowercase()
        val prevWasTorch = prev == Kind.TORCH_ON || prev == Kind.TORCH_OFF

        // stop / help first
        if (listOf("退出", "结束", "不用了", "再见").any { t.contains(it) }) return Task(Kind.STOP, t)
        if (listOf("帮助", "能做什么", "会什么").any { t.contains(it) }) return Task(Kind.HELP, t)

        // conditional follow-up ("如果没开就告诉我") — attaches to the previous query
        if (t.contains("如果") && (t.contains("就告诉") || t.contains("就提醒") || t.contains("就说"))) {
            return Task(Kind.UNKNOWN, t, conditional = true)
        }

        // ── standard-intent capabilities (checked before the older specific handlers) ──
        val durSec = ChineseTime.durationSeconds(t)
        if (t.contains("倒计时") || t.contains("计时器") || (t.contains("闹钟") && durSec != null && !t.contains("几点"))) {
            return Task(Kind.TIMER, t, (durSec ?: 300).toString())
        }
        if (t.contains("闹钟")) {
            val clock = ChineseTime.clockTime(t)
            val ask = t.contains("查看") || t.contains("有没有") || t.contains("有什么") ||
                t.contains("看看") || t.contains("下一个")
            return when {
                clock != null -> Task(Kind.ALARM, t, "${clock.first}:${clock.second}")
                ask -> Task(Kind.SHOW_ALARMS, t)
                else -> Task(Kind.SHOW_ALARMS, t)
            }
        }
        if (t.contains("打开设置") || (t.contains("设置") && t.contains("打开")) ||
            (t.contains("打开") && t.contains("页"))
        ) {
            val page = t.replace(Regex("(打开|进入|设置|页面|一下|的)"), "").trim()
            return Task(Kind.SETTINGS_PAGE, t, page)
        }
        if (t.contains("导航") || t.contains("地图") || t.contains("路线")) {
            val dest = t.replace(Regex("(导航|去|到|地图|路线|打开|查一下|查)"), "").trim()
            if (dest.isNotBlank()) return Task(Kind.MAPS, t, dest)
        }
        if (t.contains("搜索") || t.contains("查一下") || t.contains("搜一下")) {
            val q = t.replace(Regex("(搜索|搜一下|查一下|帮我|一下)"), "").trim()
            if (q.isNotBlank()) return Task(Kind.WEB, t, q)
        }
        if (t.contains("有哪些应用") || t.contains("能打开什么") || t.contains("装了哪些")) {
            return Task(Kind.APP_LIST, t)
        }
        // "打开X" where X is not one of the built-ins above → app registry
        if (t.startsWith("打开") || t.startsWith("启动") || t.startsWith("运行")) {
            val name = t.replace(Regex("^(打开|启动|运行)"), "").replace("应用", "").trim()
            if (name.isNotBlank()) return Task(Kind.OPEN_APP, t, name)
        }

        // torch: pronoun "关闭它" refers back to the torch when that was the previous task
        val pronoun = t.contains("它") || t.contains("他")
        if (torchOff(t) || (pronoun && (t.contains("关") || t.contains("关掉")) && prevWasTorch)) {
            return Task(Kind.TORCH_OFF, t)
        }
        if (torchOn(t)) return Task(Kind.TORCH_ON, t)
        if (t.contains("手电") && (t.contains("开") || t.contains("亮"))) {
            return if (t.contains("有没有") || t.contains("开着") || t.contains("是不是") || t.contains("吗"))
                Task(Kind.TORCH_STATE, t) else Task(Kind.TORCH_ON, t)
        }
        if (t.contains("手电")) return Task(Kind.TORCH_STATE, t)

        // bluetooth
        if (t.contains("蓝牙")) {
            val asking = t.contains("有没有") || t.contains("开着") || t.contains("开了") ||
                t.contains("是不是") || t.contains("状态") || t.contains("连了") || t.contains("吗")
            return when {
                t.contains("关闭") || t.contains("关掉") || (t.contains("关") && !asking) -> Task(Kind.BT_OFF, t)
                t.contains("打开") || t.contains("开启") || t.contains("开一下") -> Task(Kind.BT_ON, t)
                else -> Task(Kind.BT_STATE, t)
            }
        }

        // wifi
        if (low.contains("wifi") || low.contains("wi-fi") || t.contains("无线网")) {
            val asking = t.contains("有没有") || t.contains("开着") || t.contains("连上") ||
                t.contains("状态") || t.contains("吗")
            return when {
                (t.contains("关闭") || t.contains("关掉") || t.contains("断开")) && !asking -> Task(Kind.WIFI_OFF, t)
                (t.contains("打开") || t.contains("开启") || t.contains("连接") || t.contains("连一下")) && !asking ->
                    Task(Kind.WIFI_ON, t)
                else -> Task(Kind.WIFI_STATE, t)
            }
        }

        // network
        if (t.contains("网络") || t.contains("联网") || t.contains("上网") || t.contains("有网")) {
            return Task(Kind.NETWORK, t)
        }

        // volume
        if (t.contains("音量") || t.contains("静音")) {
            val m = Regex("(\\d+)").find(t)
            return if (m != null || t.contains("调到") || t.contains("调小") || t.contains("调大")) {
                Task(Kind.VOLUME_SET, t, m?.value ?: "")
            } else {
                Task(Kind.VOLUME_STATE, t)
            }
        }

        // device info
        if (t.contains("存储") || t.contains("空间") || t.contains("内存") ||
            t.contains("版本") || t.contains("锁着") || t.contains("解锁") || t.contains("锁屏")
        ) {
            val focus = when {
                t.contains("存储") || t.contains("空间") || t.contains("内存") -> "storage"
                t.contains("锁") || t.contains("解锁") -> "lock"
                t.contains("版本") -> "version"
                else -> "all"
            }
            return Task(Kind.DEVICE, t, focus)
        }

        // camera
        if (t.contains("相机") || t.contains("拍照") || t.contains("录") || t.contains("拍摄") || t.contains("视频")) {
            return if (t.contains("录") || t.contains("视频") || t.contains("拍摄")) {
                Task(Kind.CAMERA_VIDEO, t)
            } else {
                Task(Kind.CAMERA_PHOTO, t)
            }
        }

        // phone
        if (t.contains("打电话") || t.contains("拨打") || t.contains("呼叫") || t.contains("拨给")) {
            val name = t.replace(Regex("(给|帮我|请|打|拨|呼叫|电话|一下|个|联系)"), "").trim()
            return Task(Kind.CALL, t, name)
        }

        // battery / time (late so "还有多少电" doesn't steal "打开手电筒")
        if (listOf("电量", "电池", "多少电", "剩多少电", "还有电", "电够不够", "百分之多少").any { t.contains(it) }) {
            return Task(Kind.BATTERY, t)
        }
        if (listOf("几点", "时间", "日期", "几号", "星期").any { t.contains(it) }) {
            return Task(Kind.TIME, t)
        }

        return Task(Kind.UNKNOWN, t)
    }

    private fun torchOff(t: String): Boolean =
        t.contains("手电") && (t.contains("关闭") || t.contains("关掉") || t.contains("关一下")) ||
            t.contains("关灯") || (t.contains("把手电") && t.contains("关"))
            || (t.contains("手电") && t.contains("别亮"))

    private fun torchOn(t: String): Boolean =
        t.contains("手电") && (t.contains("打开") || t.contains("开启") || t.contains("开一下") || t.contains("亮起来")) ||
            t.contains("开灯") || t.contains("打开灯") || (t.contains("把手电") && t.contains("开"))

    // ── execution ──

    data class Outcome(val task: Task, val result: ToolResult?)

    /** Runs every task in order and returns the merged spoken sentence. */
    fun executeAll(ctx: Context, tasks: List<Task>, sid: Long): String {
        val outcomes = ArrayList<Outcome>()
        for (task in tasks) {
            if (!VoiceSession.isCurrent(sid)) {
                Log.i(TAG, "session #$sid superseded, stop executing (${task.kind})")
                break
            }
            val r = runCatching { execute(ctx, task) }.getOrElse {
                Log.e(TAG, "task ${task.kind} threw", it)
                ToolResult.error(task.kind.name.lowercase(), "这个操作出错了", it.javaClass.simpleName)
            }
            Log.i(
                TAG,
                "task=${task.kind} raw=\"${task.raw}\" status=${r.status} verified=${r.verified} detail=${r.detail}"
            )
            outcomes.add(Outcome(task, r))
        }
        val speech = outcomes.joinToString("，") { it.result?.speak ?: "" }.trim('，', ' ')
        Log.i(TAG, "merged speech: $speech")
        return speech.ifBlank { "没有可以执行的指令" }
    }

    private fun execute(ctx: Context, task: Task): ToolResult = when (task.kind) {
        Kind.BATTERY -> LocalCapabilities.battery(ctx)
        Kind.TIME -> LocalCapabilities.time()
        Kind.TORCH_ON -> TorchControl.set(ctx, true)
        Kind.TORCH_OFF -> TorchControl.set(ctx, false)
        Kind.TORCH_STATE -> LocalCapabilities.torchState(ctx)
        Kind.WIFI_STATE -> LocalCapabilities.wifiState(ctx)
        // Direct API first; if the platform refuses, fall back to driving the real panel
        // through the AccessibilityService (and re-verify against the platform afterwards).
        Kind.WIFI_ON -> escalateIfRestricted(ctx, WifiControl.enable(ctx), SystemPanelAutomator.Target.WIFI, true)
        Kind.WIFI_OFF -> escalateIfRestricted(ctx, WifiControl.disable(ctx), SystemPanelAutomator.Target.WIFI, false)
        Kind.BT_STATE -> LocalCapabilities.btState(ctx)
        Kind.BT_ON -> escalateIfRestricted(
            ctx, LocalCapabilities.btSet(ctx, true), SystemPanelAutomator.Target.BLUETOOTH, true
        )
        Kind.BT_OFF -> escalateIfRestricted(
            ctx, LocalCapabilities.btSet(ctx, false), SystemPanelAutomator.Target.BLUETOOTH, false
        )
        Kind.NETWORK -> LocalCapabilities.networkState(ctx)
        Kind.VOLUME_STATE -> LocalCapabilities.volumeState(ctx)
        Kind.VOLUME_SET -> {
            val p = task.arg.toIntOrNull() ?: 50
            LocalCapabilities.volumeSet(ctx, p)
        }
        Kind.DEVICE -> LocalCapabilities.deviceState(ctx, task.arg.ifBlank { "all" })
        Kind.CAMERA_PHOTO -> LocalCapabilities.cameraOpen(ctx, video = false)
        Kind.CAMERA_VIDEO -> LocalCapabilities.cameraOpen(ctx, video = true)
        Kind.CALL -> LocalCapabilities.call(ctx, task.arg)
        Kind.TIMER -> IntentActions.setTimer(ctx, task.arg.toIntOrNull() ?: 300)
        Kind.ALARM -> {
            val (h, m) = task.arg.split(":").mapNotNull { it.toIntOrNull() }
                .let { if (it.size >= 2) it[0] to it[1] else 7 to 0 }
            IntentActions.setAlarm(ctx, h, m)
        }
        Kind.SHOW_ALARMS -> IntentActions.showAlarms(ctx)
        Kind.SETTINGS_PAGE -> IntentActions.openSettingsPage(ctx, task.arg.ifBlank { task.raw })
        Kind.OPEN_APP -> {
            val app = AppRegistry.find(ctx, task.arg)
            if (app == null) {
                ToolResult.unavailable("app", "没有找到「${task.arg}」这个应用", "not installed / not visible")
            } else {
                AppRegistry.launch(ctx, app)
            }
        }
        Kind.MAPS -> IntentActions.maps(ctx, task.arg)
        Kind.WEB -> IntentActions.web(ctx, task.arg)
        Kind.APP_LIST -> ToolResult.ok("apps", AppRegistry.listSpeech(ctx), verified = true)
        Kind.HELP -> ToolResult.ok(
            "help",
            "离线能查：电量、时间、网络、Wi-Fi、蓝牙、音量、存储和锁定状态。能操作：手电筒开关、打开相机、拨号。其他问题需要联网。",
            verified = true
        )
        Kind.STOP -> ToolResult.ok("stop", "好的，已退出", verified = true)
        Kind.UNKNOWN -> ToolResult(ToolStatus.DEVICE_UNAVAILABLE, "unknown", "", "not a device task", null)
    }

    /**
     * When a direct toggle is blocked by the platform, use accessibility to press the actual
     * switch in the system panel — but only if the service is connected, and always verify the
     * outcome through the platform API rather than trusting the click.
     */
    private fun escalateIfRestricted(
        ctx: Context,
        direct: ToolResult,
        target: SystemPanelAutomator.Target,
        on: Boolean,
    ): ToolResult {
        if (direct.status != ToolStatus.SYSTEM_RESTRICTED) return direct
        // Do NOT gate on available(): right after a cold start the service may still be binding.
        // setEnabled() waits for the connection itself and reports a clear failure if it never comes.
        Log.i(TAG, "direct toggle restricted → accessibility panel fallback for $target")
        val r = SystemPanelAutomator.setEnabled(ctx, target, on)
        Log.i(TAG, "accessibility fallback result: ${r.status} verified=${r.verified} ${r.detail}")
        return r
    }

    /** True when at least one task can be served entirely on-device. */
    fun hasLocalTask(tasks: List<Task>): Boolean =
        tasks.any { it.kind != Kind.UNKNOWN && it.kind != Kind.HELP && it.kind != Kind.STOP }
}
