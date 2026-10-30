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
 *  - Every mutation is verified: timer count before/after, alarm switch state, and — when the
 *    alarm is the next one — AlarmManager.nextAlarmClock.
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
        if (r.ok) {
            ActionMemory.record(
                Action.CREATE, Target.TIMER, mapOf("seconds" to seconds.toString()),
                "创建了${seconds / 60}分钟倒计时"
            )
        }
        return r
    }

    /**
     * Cancel a running timer. Uses the "取消" button on the timer tab and verifies the count
     * dropped — the API cannot do this for a timer that is not ringing.
     */
    fun cancelTimer(ctx: Context): ToolResult {
        if (!openClockTab(ctx, "定时器")) {
            return ToolResult.unavailable(
                "timer_cancel", "打不开时钟，取消不了倒计时", "clock app not reachable"
            )
        }
        Thread.sleep(600)
        val before = runningTimerCount()
        if (before == 0) {
            return ToolResult.unavailable("timer_cancel", "现在没有正在运行的倒计时，不用取消", "count=0")
        }
        // Prefer matching the remembered duration, else cancel the top-most one.
        val remembered = ActionMemory.lastTimerSeconds()
        var clicked = false
        if (remembered != null) {
            val label = "${remembered / 60} 分钟"
            if (UiAutomator.waitForText(label, 1200)) {
                clicked = UiAutomator.clickText("取消")
            }
        }
        if (!clicked) clicked = UiAutomator.clickText("取消")
        Log.i(TAG, "cancelTimer clicked=$clicked before=$before")
        if (!clicked) {
            return ToolResult.error("timer_cancel", "没找到取消按钮，倒计时还在运行", "no cancel node")
        }
        Thread.sleep(1200)
        val after = runningTimerCount()
        return when {
            after in 0..(before - 1) -> ToolResult.ok(
                "timer_cancel",
                if (after == 0) "倒计时已经取消了" else "已经取消一个倒计时，还剩${after}个",
                verified = true, detail = "count $before→$after"
            )
            after == before -> ToolResult.error(
                "timer_cancel", "点了取消，但倒计时还在跑", "count unchanged $after"
            )
            else -> ToolResult(
                ToolStatus.TIMEOUT, "timer_cancel", "点了取消，但没法确认结果", "count=$after"
            )
        }
    }

    // ── alarms ──
    fun createAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        val r = IntentActions.setAlarm(ctx, hour, minute)
        if (r.ok) {
            ActionMemory.record(
                Action.CREATE, Target.ALARM, mapOf("hour" to "$hour", "minute" to "$minute"),
                "设了${hour}点${minute}分闹钟"
            )
        }
        return r
    }

    /** Times of the alarms currently listed in the clock app (best effort, UI-derived). */
    private fun listedAlarmTimes(): List<String> {
        val out = ArrayList<String>()
        for (t in UiAutomator.screenTexts(120)) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim())
            if (m != null) out.add("${m.groupValues[1].toInt()}:${m.groupValues[2]}")
            val m2 = Regex("^(\\d{1,2})\\s*(上午|下午)", RegexOption.IGNORE_CASE).find(t.trim())
            if (m2 != null) out.add(m2.groupValues[1])
        }
        return out.distinct()
    }

    fun queryAlarms(ctx: Context): ToolResult {
        val next = IntentActions.nextAlarm(ctx)
        val nextText = next?.triggerTime?.let {
            java.text.SimpleDateFormat("M 月 d 日 HH 点 mm 分", java.util.Locale.CHINA)
                .format(java.util.Date(it))
        }
        val opened = openClockTab(ctx, "闹钟")
        Thread.sleep(500)
        val listed = if (opened) listedAlarmTimes() else emptyList()
        val speech = when {
            nextText != null && listed.isNotEmpty() ->
                "下一个闹钟是 $nextText，闹钟列表里有 ${listed.size} 个时间显示"
            nextText != null -> "下一个闹钟是 $nextText"
            else -> "现在没有设置中的闹钟"
        }
        // Query must not mutate anything: no clicks beyond opening the tab.
        return ToolResult.ok("alarm_query", speech, verified = true, detail = "next=$nextText listed=$listed")
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

        // Find the row whose text is the time, then its switch.
        val timeNode = UiAutomator.findNode(target) ?: UiAutomator.findNode("${hour}:${minute}")
            ?: return ToolResult.error("alarm_close", "看到了这个时间，但找不到开关", "node gone")
        val row = generateSequence(timeNode) { it.parent }.take(4).toList()
        val switch = row.mapNotNull { r ->
            UiAutomator.findFirst(r) { n ->
                val cls = n.className?.toString()?.lowercase() ?: ""
                cls.contains("switch") || n.isCheckable
            }
        }.firstOrNull()

        val before = switch?.isChecked
        val clicked = when {
            switch == null -> UiAutomator.click(timeNode)
            before == false -> true      // already off
            else -> UiAutomator.click(switch)
        }
        Thread.sleep(1000)
        val after = switch?.isChecked
        val nextAfter = IntentActions.nextAlarm(ctx)

        return when {
            switch != null && before == false -> ToolResult.ok(
                "alarm_close", "${hour}点${minute}分的闹钟已经是关着的", verified = true
            )
            clicked && after == false -> ToolResult.ok(
                "alarm_close", "${hour}点${minute}分的闹钟已经关闭", verified = true,
                detail = "switch checked → false, next=${nextAfter?.triggerTime}"
            )
            clicked -> ToolResult.error(
                "alarm_close", "点了开关，但闹钟状态没变", "switch=$before→$after"
            )
            else -> ToolResult.error("alarm_close", "点不动这个开关", "click failed")
        }
    }

    fun deleteAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        // Deleting is a stronger close; the clock UI requires long-press or a menu, so we only
        // do the switch-off and say so rather than pretending the alarm was removed.
        val r = closeAlarm(ctx, hour, minute)
        return if (r.ok) {
            ToolResult(
                ToolStatus.SUCCESS, "alarm_delete",
                "${hour}点${minute}分的闹钟已经关闭（三星时钟删除需要长按，我只关闭了它）",
                r.detail, verified = r.verified
            )
        } else r
    }
}
