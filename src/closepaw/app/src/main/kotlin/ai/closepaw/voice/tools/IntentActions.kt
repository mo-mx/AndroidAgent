package ai.closepaw.voice.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Standard Android intents exposed as capabilities, with verification of the *actual* effect.
 *
 * The distinction this file enforces (asked for explicitly): "startActivity succeeded" is not
 * "the alarm exists". Next-alarm and timer-screen observations cannot uniquely identify a
 * newly created object, so creation remains unverified until target-specific read-back exists.
 */
object IntentActions {
    private const val TAG = "IntentActions"

    private fun resolvable(ctx: Context, i: Intent): Boolean =
        runCatching { ctx.packageManager.resolveActivity(i, 0) != null }.getOrDefault(false)

    // ── timers ──
    fun setTimer(ctx: Context, seconds: Int): ToolResult {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "语音助手")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!resolvable(ctx, i)) {
            return ToolResult.unavailable("timer", "这台手机没有能设置倒计时的应用")
        }
        var reason = ""
        var sent = runCatching { ctx.startActivity(i); true }.getOrElse {
            reason = "${it.javaClass.simpleName}: ${it.message}"
            Log.e(TAG, "setTimer(SKIP_UI) failed: $reason", it)
            false
        }
        if (!sent) {
            // Some OEM clocks refuse EXTRA_SKIP_UI; retry with the UI shown.
            val i2 = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            sent = runCatching { ctx.startActivity(i2); true }.getOrElse {
                reason += " | retry: ${it.javaClass.simpleName}: ${it.message}"
                Log.e(TAG, "setTimer retry failed: $reason", it)
                false
            }
        }
        if (!sent) {
            return ToolResult.error("timer", "倒计时没能设置，系统拒绝了请求", reason)
        }

        // Verification: timers have no public read API, so confirm through the clock app's own
        // UI. Our own screen must never count as evidence, hence the package check.
        Thread.sleep(1000)
        var fg = UiAutomator.foregroundPackage()
        var clockForeground = fg != null && fg.contains("clock")
        if (!clockForeground) {
            val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟")
            if (clock != null) {
                AppRegistry.launch(ctx, clock)
                clockForeground = UiAutomator.waitForPackage(ctx, clock.packageName, 2500)
                fg = UiAutomator.foregroundPackage()
            }
        }
        // Open the timer tab, otherwise the clock lands on alarms and any "7:30" text would be
        // mistaken for a countdown (this produced a false positive in testing).
        //
        // Review `6546660320` P2: this used to be `UiAutomator.clickText("定时器")` — a
        // containing-text match whose click climbs to a clickable parent and finally taps
        // coordinates, so a content row ("定时器设置") or a hint card could be tapped while the read
        // still came from the clock package. The tab bar is now reachable only through the exact
        // navigation control; when that control is missing or refuses, nothing is clicked and no
        // page on screen is read as this creation's evidence.
        var tab = "not-attempted"
        if (clockForeground) {
            tab = when (UiAutomator.clickClockNavTab("定时器")) {
                true -> "clicked"
                false -> "refused"
                null -> "no-verified-tab-control"
            }
            Thread.sleep(700)
        }
        // Review `6547564219` P2: a delivered ACTION_CLICK proves the gesture, never the page.
        // Between the tap and the read our own voice window can take the foreground (observed on
        // device in earlier rounds), and a clock left on the alarm tab is not the timer page. So the
        // read must prove its own identity — clock package *and* the timer tab selected in the very
        // same tree — before any countdown wording is allowed to mean anything.
        val read = if (clockForeground) UiAutomator.readScreen(80) else null
        val pageOk = tab == "clicked" && read?.rootPackage == UiAutomator.CLOCK_PKG &&
            read?.selectedTabs?.get("定时器") == true
        // A bounded read is a sample of the page: `readTree` stops on the 600-node cap or the text
        // budget. So "no countdown text in this read" is only "not found", and only a *complete*
        // read can turn that into "this page does not show a countdown". An explicit zero count is
        // affirmative evidence about the page, so clipping cannot erase it.
        val readClipped = read != null && (read.textsTruncated || read.nodesTruncated)
        // Review `6547564219` P2: `contains("定时器正在运行")` also matched "0 个定时器正在运行",
        // so the empty state was reported as a running countdown. The count is parsed instead.
        val countdown: Boolean? = when {
            !pageOk || read == null -> null
            read.texts.any { countdownWording(it) } -> true
            read.texts.any { explicitZeroCountdown(it) } -> false
            readClipped -> null
            else -> false
        }
        val mins = seconds / 60
        val human = if (seconds % 60 == 0) "${mins}分钟" else "${seconds}秒"
        Log.i(
            TAG,
            "timer verify: fg=$fg clockFg=$clockForeground tab=$tab page=${if (pageOk) "timer-selected" else "unconfirmed"}" +
                " countdown=${countdown ?: "unknown"} readClipped=$readClipped textCount=${read?.texts?.size ?: 0}"
        )

        val pageDetail = if (pageOk) "page=timer-selected" else "page=unconfirmed"
        val countdownDetail = when (countdown) {
            true -> "countdown-ui=true"
            false -> "countdown-ui=false"
            null -> "countdown-ui=unknown"
        }
        return when {
            pageOk && countdown == true -> ToolResult.sentWithoutProof(
                "timer",
                "${human}的倒计时请求已经发送，但无法确认新倒计时是否开始，请在时钟里核对",
                "clockFg=true tab=$tab $pageDetail $countdownDetail; no newly created object identity"
            )
            pageOk && countdown == null -> ToolResult.sentWithoutProof(
                "timer",
                "${human}的倒计时请求已经发送，但屏幕读取不完整，没法确认新倒计时是否开始，请在时钟里核对",
                "clockFg=true tab=$tab $pageDetail $countdownDetail" +
                    " readClipped=$readClipped texts=${read?.texts?.size ?: 0}; partial read cannot be read as absence"
            )
            pageOk -> ToolResult.sentWithoutProof(
                "timer",
                "${human}的倒计时请求已经交给时钟应用，但我在时钟界面里没看到倒计时",
                "clockFg=true tab=$tab $pageDetail $countdownDetail, countdown text not found in a complete read"
            )
            clockForeground -> ToolResult.sentWithoutProof(
                "timer",
                "${human}的倒计时请求已经发送，但无法确认是否真的开始，请在时钟里看一下",
                "clockFg=true tab=$tab $pageDetail $countdownDetail; " +
                    "read not proven to be the selected timer page"
            )
            else -> ToolResult.sentWithoutProof(
                "timer",
                "${human}的倒计时请求已经发送，但没法确认是否真的开始，请在时钟里看一下",
                "fg=$fg clockFg=false"
            )
        }
    }

    /** Digits in front of the running-timers sentence; the sentence alone also matches "0 个". */
    private val RUNNING_TIMERS = Regex("""(\d+)\s*个\s*定时器正在运行""")

    /** A duration readout such as "4 分钟 58 秒". */
    private val DURATION = Regex("""\d+\s*分钟\s*\d+\s*秒""")

    /**
     * True only when this line says a countdown is *running*, never when it says there are none.
     * A bare "定时器正在运行" without a count is still running evidence; an explicit `0 个` is the
     * empty state and must be reported as `countdown-ui=false`.
     */
    private fun countdownWording(text: String): Boolean {
        val count = RUNNING_TIMERS.find(text)
        if (count != null) return (count.groupValues[1].toIntOrNull() ?: 0) > 0
        if (text.contains("定时器正在运行")) return true
        return text.contains("剩余") || text.contains("倒计时") || DURATION.containsMatchIn(text)
    }

    /**
     * An affirmative "there are none" from the page's own header, e.g. 「0 个定时器正在运行」.
     * Unlike "no countdown wording found", this is evidence and survives a clipped read.
     */
    private fun explicitZeroCountdown(text: String): Boolean =
        RUNNING_TIMERS.find(text)?.groupValues?.get(1)?.toIntOrNull() == 0

    // ── alarms ──
    fun setAlarm(
        ctx: Context,
        hour: Int,
        minute: Int,
        label: String? = null,
        // Injected so the bounded wait can be tested without spending six real seconds.
        now: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: (Long) -> Unit = { Thread.sleep(it) },
        alarmWaitMs: Long = ALARM_WAIT_MS,
    ): ToolResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)   // create silently, then verify via AlarmManager
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "语音助手")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!resolvable(ctx, i)) return ToolResult.unavailable("alarm", "这台手机没有闹钟应用")

        val before = nextAlarmProbe(ctx).info
        val sent = runCatching { ctx.startActivity(i); true }.getOrElse {
            Log.e(TAG, "setAlarm failed", it); false
        }
        if (!sent) return ToolResult.error("alarm", "闹钟请求没能发出去")

        // Samsung's clock may open a confirmation screen; give it a beat, then read the system
        // truth: AlarmManager.nextAlarmClock.triggerTime. Deriving hour/minute from the trigger
        // timestamp avoids relying on AlarmClockInfo accessors and works on every API level.
        val wait = awaitNextAlarm({ nextAlarmProbe(ctx) }, alarmWaitMs, now, pause)
        val trigger = wait.trigger
        val cal = trigger?.let { Calendar.getInstance().apply { timeInMillis = it } }
        val gotH = cal?.get(Calendar.HOUR_OF_DAY)
        val gotM = cal?.get(Calendar.MINUTE)
        val hhmm = String.format(Locale.CHINA, "%d点%02d分", hour, minute)
        val exact = gotH == hour && gotM == minute
        val why = "reads=${wait.reads} nextKnown=${wait.lastKnown} why=${wait.why}"
        return when {
            exact -> ToolResult.sentWithoutProof(
                "alarm",
                "已发出${hhmm}的闹钟请求，但无法确认本次是否新增或更新，请在时钟里核对",
                "before=${before?.triggerTime} next=$trigger; matching next alarm is not creation proof; $why"
            )
            trigger != null -> ToolResult.sentWithoutProof(
                "alarm",
                "已发出${hhmm}的闹钟请求，但无法确认本次结果；系统下一个闹钟为${gotH}点${gotM}分，不能据此判断目标是否已创建",
                "next=${gotH}:${gotM} (mismatch); $why"
            )
            // Round 49: an unreadable AlarmManager is not "nothing was registered". The old answer
            // ("已经打开闹钟界面，可能还需要你按一下保存") asserted two things nothing had observed:
            // the alarm registration, and a save UI — the intent is sent with EXTRA_SKIP_UI=true,
            // so on most clocks there is no UI at all.
            !wait.lastKnown -> ToolResult.sentWithoutProof(
                "alarm",
                "已发出${hhmm}的闹钟请求，但读不到系统的闹钟状态，没能确认它是否创建成功",
                "next=null nextKnown=false; $why"
            )
            else -> ToolResult.sentWithoutProof(
                "alarm",
                "已发出${hhmm}的闹钟请求，但系统里没有登记下一个闹钟，没能确认它是否创建成功",
                "next=null; $why"
            )
        }
    }

    fun nextAlarm(ctx: Context): AlarmManager.AlarmClockInfo? = nextAlarmProbe(ctx).info

    /** What `AlarmManager.nextAlarmClock` said, and whether it could be read at all. */
    internal data class NextAlarm(val info: AlarmManager.AlarmClockInfo?, val known: Boolean)

    /** One bounded wait for a registered next alarm: the trigger it saw and why it stopped. */
    internal data class AlarmWait(
        val trigger: Long?,
        val lastKnown: Boolean,
        val reads: Int,
        val why: String,
    )

    private const val ALARM_WAIT_MS = 6_000L
    private const val ALARM_STEP_MS = 300L

    /**
     * Wait, bounded, for the system to register a next alarm, and report both what was seen and
     * whether the last read was readable at all.
     *
     * Round 49: two facts the old loop merged. (a) `deadline = System.currentTimeMillis() + 6000` is
     * not a bound — a backwards clock correction keeps `now` below it (in the limit the window is
     * unbounded, and the loop spins on `Thread.sleep`), a forward jump ends it early. The deadline
     * is now taken from the injected monotonic source (default `System.nanoTime()`), the same
     * contract round 48 gave `LocalCapabilities.awaitState`, and the loop carries a hard read cap so
     * a frozen clock cannot become a spin. (b) The old loop read through `nextAlarm(ctx)`, which
     * drops `NextAlarm.known`, so an `AlarmManager` that threw was indistinguishable from a healthy
     * system with no alarm — the caller then spoke as if a save UI were waiting. [AlarmWait.why]
     * separates `found` / `timeout` (readable, nothing registered) / `read-failed` (nothing was
     * observed) / `read-cap` (bounded stop).
     */
    internal fun awaitNextAlarm(
        probe: () -> NextAlarm,
        timeoutMs: Long = ALARM_WAIT_MS,
        now: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: (Long) -> Unit = { Thread.sleep(it) },
    ): AlarmWait {
        val deadline = now() + timeoutMs
        val maxReads = (timeoutMs / ALARM_STEP_MS).toInt() + 2
        var reads = 0
        var last = probe()
        reads++
        while (true) {
            last.info?.triggerTime?.let { return AlarmWait(it, true, reads, "found") }
            val known = last.known
            // "Nothing was readable" outranks *why* the loop stopped: the caller needs that fact
            // whichever bound ended the wait.
            if (!known) {
                if (reads >= maxReads || now() >= deadline) return AlarmWait(null, false, reads, "read-failed")
            } else if (reads >= maxReads) {
                return AlarmWait(null, true, reads, "read-cap")
            } else if (now() >= deadline) {
                return AlarmWait(null, true, reads, "timeout")
            }
            pause(ALARM_STEP_MS)
            last = probe()
            reads++
        }
    }

    /**
     * `nextAlarmClock == null` and "the call failed" are different facts that `runCatching{}.getOrNull()`
     * used to merge into one null. [known] keeps them apart so a failed read is never spoken as an
     * empty phone.
     */
    internal fun nextAlarmProbe(ctx: Context): NextAlarm = runCatching {
        NextAlarm((ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock, true)
    }.getOrElse {
        Log.e(TAG, "nextAlarm unreadable: ${it.javaClass.simpleName}", it)
        NextAlarm(null, false)
    }

    fun showAlarms(ctx: Context): ToolResult {
        val probe = nextAlarmProbe(ctx)
        val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = resolvable(ctx, i) && runCatching { ctx.startActivity(i); true }.getOrDefault(false)
        val when_ = probe.info?.triggerTime?.let {
            SimpleDateFormat("M 月 d 日 HH 点 mm 分", Locale.CHINA).format(java.util.Date(it))
        }
        // SHOW_ALARMS is not handled by every OEM clock; fall back to opening the clock app.
        var launchedClock = false
        if (!opened) {
            val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟")
            if (clock != null) launchedClock = AppRegistry.launch(ctx, clock).ok
        }
        // `nextAlarmClock == null` means "no alarm is enabled right now". It does not enumerate the
        // alarms the user created (a phone full of disabled alarms reads exactly the same), so the
        // wording says which fact was actually observed. An unreadable probe says nothing at all.
        val speech = when {
            !probe.known -> "读不到系统的闹钟状态，没法确认有没有闹钟"
            when_ != null -> "下一个闹钟是 $when_"
            !opened && !launchedClock -> "没有找到闹钟应用，系统里也没有下一个已启用的闹钟"
            launchedClock -> "已经打开时钟，系统里没有下一个已启用的闹钟"
            else -> "系统里没有下一个已启用的闹钟"
        }
        return ToolResult.ok(
            "alarms", speech, verified = probe.known,
            detail = "next=$when_ nextKnown=${probe.known} opened=$opened clock=$launchedClock;" +
                " nextAlarmClock is not an enumeration"
        )
    }

    // ── settings pages ──
    private val settingsPages = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "蓝牙" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "显示" to Settings.ACTION_DISPLAY_SETTINGS,
        "声音" to Settings.ACTION_SOUND_SETTINGS,
        "电池" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "存储" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "应用" to Settings.ACTION_APPLICATION_SETTINGS,
        "位置" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "无障碍" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "网络" to Settings.ACTION_WIRELESS_SETTINGS,
        "语言" to Settings.ACTION_LOCALE_SETTINGS,
        "日期" to Settings.ACTION_DATE_SETTINGS,
    )

    fun openSettingsPage(ctx: Context, spoken: String): ToolResult {
        // "Wi-Fi" / "wifi" / "无线网" must all hit the same entry, so normalise punctuation and case.
        val norm = spoken.lowercase().replace("-", "").replace(" ", "").replace("_", "")
        fun normKey(k: String) = k.lowercase().replace("-", "").replace(" ", "")
        val hit = settingsPages.entries.firstOrNull { norm.contains(normKey(it.key)) }
            ?: when {
                norm.contains("wi") && norm.contains("fi") -> settingsPages.entries.first { it.key == "wifi" }
                norm.contains("无线") -> settingsPages.entries.first { it.key == "wifi" }
                else -> null
            }
        val action = hit?.value ?: Settings.ACTION_SETTINGS
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = resolvable(ctx, i) && runCatching { ctx.startActivity(i); true }.getOrDefault(false)
        val fg = if (ok) UiAutomator.waitForPackage(ctx, "com.android.settings", 2000) else false
        return if (ok) {
            ToolResult.ok("settings", "已经打开${hit?.key?.let { if (it == "wifi") "Wi-Fi" else it } ?: ""}设置", verified = fg, detail = "action=$action fg=$fg")
        } else {
            ToolResult.error("settings", "打不开设置页面", action)
        }
    }

    // ── misc intents ──
    fun dial(ctx: Context, number: String, direct: Boolean = false): ToolResult {
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (resolvable(ctx, i) && runCatching { ctx.startActivity(i); true }.getOrDefault(false)) {
            ToolResult.sentWithoutProof("dial", if (direct) "正在拨号" else "已经打开拨号界面，按一下拨号键就能打出去", "$action $number")
        } else {
            ToolResult.error("dial", "打不开拨号界面", number)
        }
    }

    fun maps(ctx: Context, destination: String, navigate: Boolean = true): ToolResult {
        val uri = if (navigate) {
            Uri.parse("google.navigation:q=" + Uri.encode(destination))
        } else {
            Uri.parse("geo:0,0?q=" + Uri.encode(destination))
        }
        var i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!resolvable(ctx, i)) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (resolvable(ctx, i) && runCatching { ctx.startActivity(i); true }.getOrDefault(false)) {
            ToolResult.sentWithoutProof("maps", "已经打开地图，目的地是$destination", i.dataString ?: "")
        } else {
            ToolResult.unavailable("maps", "这台手机没有能处理地图的应用")
        }
    }

    fun web(ctx: Context, query: String): ToolResult {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!resolvable(ctx, i)) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (resolvable(ctx, view)) {
                runCatching { ctx.startActivity(view); true }.getOrDefault(false)
                ToolResult.sentWithoutProof("web", "已经用浏览器搜索$query")
            } else {
                ToolResult.unavailable("web", "没有可以搜索的浏览器")
            }
        }
        return if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) {
            ToolResult.sentWithoutProof("web", "已经搜索$query")
        } else {
            ToolResult.error("web", "搜索没有打开")
        }
    }
}
