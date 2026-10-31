package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.AppRegistry
import ai.closepaw.voice.tools.IntentActions
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.UiAutomator
import android.content.Context
import android.util.Log

/**
 * Timer and alarm lifecycle.
 *
 * Principles taken from the failing cases:
 *  - CANCEL/DELETE never falls back to CREATE. If the target does not exist we say so.
 *  - The clock app's UI is the source of truth, because Android exposes no API to enumerate or
 *    cancel arbitrary timers (ACTION_DISMISS_TIMER only dismisses a *ringing* timer).
 *  - A scoped click is not cancellation verification. Mutations without fresh business-state
 *    read-back return verified=false; expectedState is not evaluated here.
 */
object ClockControl {
    private const val TAG = "ClockControl"
    private const val CLOCK_PKG = "com.sec.android.app.clockpackage"

    // ── UI plumbing ──
    private fun openClockTab(ctx: Context, tab: String): Boolean {
        val fg = UiAutomator.foregroundPackage()
        if (fg != CLOCK_PKG) {
            val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟")
            if (clock != null) {
                AppRegistry.launch(ctx, clock)
            } else {
                return false
            }
            UiAutomator.waitForPackage(ctx, CLOCK_PKG, 3000)
        }
        return UiAutomator.clickText(tab) || UiAutomator.waitForText(tab, 800)
    }

    /** Number of running timers, parsed from the timer tab header. -1 when unreadable. */
    fun runningTimerCount(): Int {
        val texts = UiAutomator.screenTexts(60)
        for (t in texts) {
            Regex("(\\d+)\\s*个定时器正在运行").find(t)?.let { return it.groupValues[1].toInt() }
            if (t.contains("添加定时器") && !t.contains("正在运行")) return 0
        }
        return -1
    }

    fun queryTimers(ctx: Context): ToolResult {
        openClockTab(ctx, "定时器")
        Thread.sleep(500)
        val n = runningTimerCount()
        return when {
            n < 0 -> ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "timer_query",
                "打不开时钟的定时器页面，没法确认现在有没有倒计时", "ui unavailable"
            )
            n == 0 -> ToolResult.ok("timer_query", "现在没有正在运行的倒计时", verified = true)
            else -> ToolResult.ok("timer_query", "现在有${n}个倒计时在运行", verified = true, detail = "count=$n")
        }
    }

    fun createTimer(ctx: Context, seconds: Int): ToolResult {
        val r = IntentActions.setTimer(ctx, seconds)
        ActionMemory.recordResult(Action.CREATE, Target.TIMER, mapOf("seconds" to "$seconds"), r)
        return r
    }

    /**
     * Cancel only a uniquely bound timer card. A countdown/remaining-time label is NOT original
     * duration evidence. Unknown Samsung UI shapes fail closed (no global button or coordinates).
     * Supported evidence is an explicit 初始时长/总时长 label in the same smallest subtree as
     * exactly one cancel control. This adapter has JVM fixtures only, not device acceptance.
     */
    fun cancelTimer(ctx: Context, seconds: Int? = null): ToolResult {
        if (!openClockTab(ctx, "定时器")) {
            return ToolResult.unavailable("timer_cancel", "打不开时钟，取消不了倒计时", "clock not reachable")
        }
        Thread.sleep(600)
        val root = UiAutomator.root()
        val candidates = ArrayList<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        var visited = 0
        var complete = true
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): Pair<List<android.view.accessibility.AccessibilityNodeInfo>, List<Int>> {
            if (n == null || ++visited > 800) { complete = false; return emptyList<android.view.accessibility.AccessibilityNodeInfo>() to emptyList() }
            val text = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            val buttons = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            if (text.any { it.trim() == "取消" }) buttons.add(n)
            val durations = ArrayList<Int>()
            text.filter { it.trim().startsWith("初始时长") || it.trim().startsWith("总时长") }
                .mapNotNull { ai.closepaw.voice.tools.ChineseTime.durationSeconds(it) }.distinct().let { durations.addAll(it) }
            var childBound = false
            for (i in 0 until n.childCount) {
                val before = candidates.size
                val (bs, ds) = scan(n.getChild(i))
                buttons.addAll(bs); durations.addAll(ds)
                if (candidates.size > before) childBound = true
            }
            // Do not bind a global container or widen an already-bound card to a sibling.
            if (n != root && !childBound && buttons.size == 1 && durations.size == 1) candidates.add(buttons.single() to durations.single())
            return buttons to durations
        }
        val (allButtons, _) = scan(root)
        val matching = candidates.filter { seconds == null || it.second == seconds }
        // An unreadable second card might be the same requested duration, so it cannot be ignored.
        if (!complete || allButtons.size != candidates.size || matching.size != 1) {
            return ToolResult(ToolStatus.BLOCKED, "timer_cancel",
                "无法唯一定位你说的倒计时，请在时钟中确认具体对象", "duration=$seconds candidates=${matching.size} complete=$complete", false)
        }
        val button = matching.single().first
        // Never use UiAutomator.click: that helper may climb parents or inject coordinates.
        val clicked = button.isClickable && button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        return if (!clicked) ToolResult.error("timer_cancel", "已定位倒计时，但取消按钮不可操作", "node click failed")
        else ToolResult(ToolStatus.SUCCESS, "timer_cancel", "已请求取消指定倒计时，但无法确认最终状态",
            "bound duration=${matching.single().second}; ACTION_CLICK=$clicked; business state not read back", false)
    }

    // ── alarms ──
    fun createAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        val r = IntentActions.setAlarm(ctx, hour, minute)
        ActionMemory.recordResult(Action.CREATE, Target.ALARM, mapOf("hour" to "$hour", "minute" to "$minute"), r)
        return r
    }



    fun queryAlarms(ctx: Context): ToolResult {
        val next = IntentActions.nextAlarm(ctx)
        val nextText = next?.triggerTime?.let {
            java.text.SimpleDateFormat("M 月 d 日 HH 点 mm 分", java.util.Locale.CHINA)
                .format(java.util.Date(it))
        }
        val opened = openClockTab(ctx, "闹钟")
        Thread.sleep(500)
        val texts = if (opened) UiAutomator.screenTexts(120) else emptyList()
        val listed = texts.map { it.trim() }.filter { Regex("^[0-9]{1,2}:[0-9]{2}$").matches(it) }
        // A missing nextAlarm is only a system scheduling observation, not enumeration.
        // Require an explicit empty-state marker; absent/truncated UI is unknown, not empty.
        val empty = listed.isEmpty() && texts.any { it.trim() in setOf("暂无闹钟", "没有闹钟", "无闹钟") }
        val readable = opened && (listed.isNotEmpty() || empty)
        val speech = when {
            nextText != null && listed.isNotEmpty() ->
                "下一个闹钟是 $nextText，当前可见列表有 ${listed.size} 个时间显示（不代表完整列表）"
            nextText != null -> "下一个闹钟是 $nextText；无法读取闹钟列表"
            listed.isNotEmpty() -> "系统没有下一个已启用的闹钟；当前可见列表仍有闹钟，可能包含已关闭的闹钟，不能据此判断全部开关状态"
            empty -> "系统没有下一个已启用的闹钟；当前页面显示闹钟列表为空"
            else -> "系统没有下一个已启用的闹钟；无法读取闹钟列表，不能确认是否存在已关闭的闹钟"
        }
        // Query must not mutate anything: no clicks beyond opening the tab.
        return ToolResult.ok("alarm_query", speech, verified = readable,
            detail = "next=$nextText visible=$listed explicitEmpty=$empty readable=$readable")
    }

    /**
     * Turn off the alarm at [hour]:[minute]. Never creates one: if the clock UI does not list that
     * time, the answer is "不存在".
     */
    fun closeAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        if (!openClockTab(ctx, "闹钟")) {
            return ToolResult.unavailable("alarm_close", "打不开时钟，关不了闹钟", "clock not reachable")
        }
        Thread.sleep(700)

        val target = "$hour:" + String.format(java.util.Locale.CHINA, "%02d", minute)
        val texts = UiAutomator.screenTexts(120)
        val present = texts.any { it.trim() == target || it.trim() == "${hour}:${minute}" }
        Log.i(TAG, "closeAlarm target=$target present=$present texts=$texts")
        if (!present) {
            return ToolResult.unavailable(
                "alarm_close",
                "没有${hour}点${minute}分的闹钟，不用关闭",
                "target time not listed → nothing to close (create NOT attempted)"
            )
        }

        if (texts.count { it.trim() == target || it.trim() == "${hour}:${minute}" } != 1) {
            return ToolResult(ToolStatus.BLOCKED, "alarm_close", "无法唯一定位这个时间的闹钟，请确认具体对象", "duplicate time", false)
        }
        // Bind only within the smallest subtree with exactly one clock time and one switch.
        // Never click the time node (opens an editor), an ancestor, or a coordinate fallback.
        val matches = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        var visited = 0
        var complete = true
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): Pair<List<String>, List<android.view.accessibility.AccessibilityNodeInfo>> {
            if (n == null || ++visited > 800) { complete = false; return emptyList<String>() to emptyList() }
            val times = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                .map { it.trim() }.filter { Regex("^[0-9]{1,2}:[0-9]{2}$").matches(it) }.distinct().toMutableList()
            val controls = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            if (n.isCheckable) controls.add(n)
            var childBound = false
            for (i in 0 until n.childCount) {
                val before = matches.size
                val (ts, cs) = scan(n.getChild(i)); times.addAll(ts); controls.addAll(cs)
                if (matches.size > before) childBound = true
            }
            if (!childBound && times.size == 1 && times.single() in setOf(target, "${hour}:${minute}") && controls.size == 1) matches.add(controls.single())
            return times to controls
        }
        scan(UiAutomator.root())
        if (!complete || matches.size != 1) return ToolResult(ToolStatus.BLOCKED, "alarm_close",
            "无法唯一定位这个闹钟的开关，请确认具体对象", "switch candidates=${matches.size}", false)
        val switch = matches.single()
        if (!switch.isChecked) return ToolResult.ok("alarm_close", "${hour}点${minute}分的闹钟已经是关着的", verified = true)
        val clicked = switch.isClickable && switch.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        // No business-state re-read yet: a successful click is not successful cancellation.
        return if (clicked) ToolResult(ToolStatus.SUCCESS, "alarm_close",
            "已请求关闭${hour}点${minute}分的闹钟，但无法确认最终状态", "bound switch clicked; no fresh state read-back", false)
        else ToolResult.error("alarm_close", "点不动这个开关", "node click failed")
    }

    fun deleteAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        // Deleting is a stronger close; the clock UI requires long-press or a menu, so we only
        // do the switch-off and say so rather than pretending the alarm was removed.
        val r = closeAlarm(ctx, hour, minute)
        return if (r.ok) {
            ToolResult(
                ToolStatus.SUCCESS, "alarm_delete",
                "${r.speak}；未删除闹钟（此路径仅请求关闭）",
                r.detail, verified = r.verified
            )
        } else r
    }
}
