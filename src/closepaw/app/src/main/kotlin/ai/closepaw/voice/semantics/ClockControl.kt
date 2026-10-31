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
    private const val CLOCK_READ_ATTEMPTS = 3
    private const val CLOCK_READ_RETRY_MS = 400L

    // ── UI plumbing ──
    /**
     * How the clock tab was reached. Three facts are kept apart: the nav control exists, a click was
     * delivered, and the target tab actually reports selected. [reached] is true only when the click
     * was delivered to a tab that is not reported as unselected, or when the tab already reports
     * selected. A visible tab label is never enough: the clock keeps all four labels on every page.
     */
    internal data class TabReach(val reached: Boolean, val reason: String, val selected: Boolean? = null)

    /**
     * Click only an exact-label *navigation* control; missing controls never permit a containing-text
     * click.
     *
     * Device evidence (2026-10-31, build 54e4b77): `clickText("闹钟")` matched the alarm tab's hint
     * card (`对关联闹钟进行分组`) first, so the tap landed on a card while the read still came from
     * the clock package — the page looked readable but was not the tab. No loose matcher fallback.
     *
     * Review `6543364579`: the label must additionally be a tab *inside a navigation bar* (two or
     * more distinct clock tab labels under one ancestor), and the click is ACTION_CLICK only —
     * never `UiAutomator.click` (clickable-parent climb + coordinate gesture). `null` = no verified
     * tab control, `false` = the control exists but refused.
     */
    private fun clickClockTab(tab: String): Boolean? =
        runCatching { UiAutomator.clickClockNavTab(tab) }.getOrNull()

    private fun openClockTab(ctx: Context, tab: String): TabReach {
        val fg = UiAutomator.foregroundPackage()
        var launched = false
        var foregroundConfirmed: Boolean? = null
        if (fg != CLOCK_PKG) {
            val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟")
            if (clock != null) {
                AppRegistry.launch(ctx, clock)
                launched = true
                foregroundConfirmed = UiAutomator.waitForPackage(ctx, CLOCK_PKG, 3000)
            } else {
                return TabReach(false, "app-not-found")
            }
        }
        // Missing or failed exact controls never authorize a containing-text click.
        val clicked = clickClockTab(tab) == true
        val selected = UiAutomator.tabSelected(tab)
        // Only asked when the click was not delivered: it separates "control missing" from
        // "control visible but the tap did nothing".
        val controlPresent = !clicked && UiAutomator.waitForText(tab, 600)
        val reason = when {
            clicked && selected == false -> "clicked-not-selected"
            clicked -> "clicked"
            selected == true -> "selected"
            controlPresent -> "click-failed-label-only"
            launched && foregroundConfirmed == false -> "launch-not-foreground"
            else -> "label-not-found"
        }
        return TabReach(reason == "clicked" || reason == "selected", reason, selected)
    }

    /** Read-boundary fields shared by the clock queries; values are facts, not conclusions. */
    private fun describe(read: UiAutomator.ScreenRead, reach: TabReach): String =
        "tab=${reach.reason} nodes=${read.nodesVisited} nodesTruncated=${read.nodesTruncated}" +
            " textsTruncated=${read.textsTruncated} rootPkg=${read.rootPackage} a11y=${read.serviceAvailable}"

    /**
     * Device evidence (round 5): after the timer tab was reported as reached, the read came back from
     * `ai.closepaw`'s own voice window (3 nodes), not from the clock — a freshly created VoiceActivity
     * window attaches mid-turn and covers the app we just opened. rootInActiveWindow can therefore move
     * back to our own activity between the tap and the read, so a read only counts as a clock read when
     * the root package says so. Re-reading the same occluded screen cannot recover, so each retry puts
     * the clock back in front and re-selects [tab] before reading again.
     */
    private fun readClockScreen(ctx: Context, tab: String, limit: Int): UiAutomator.ScreenRead {
        var read = UiAutomator.readScreen(limit)
        var attempt = 1
        while (attempt < CLOCK_READ_ATTEMPTS && read.rootPackage != CLOCK_PKG) {
            runCatching {
                val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟") ?: return@runCatching
                AppRegistry.launch(ctx, clock)
                // The clock reopens on its remembered tab; re-select the one this query is about.
                clickClockTab(tab)
            }
            Thread.sleep(CLOCK_READ_RETRY_MS)
            read = UiAutomator.readScreen(limit)
            attempt++
        }
        return read
    }

    /**
     * Package and selected=true must come from the same ScreenRead root. Missing/null/false
     * evidence is not page identity; navigation-time selection is diagnostic only.
     */
    private fun onTargetPage(read: UiAutomator.ScreenRead, tab: String) =
        read.rootPackage == CLOCK_PKG && read.selectedTabs[tab] == true

    /**
     * Result of the single window gate used by every path that binds and clicks a node.
     *
     * [root] is the very tree the nodes are bound from and it was obtained as
     * `rootInActiveWindow` *and* checked to belong to [CLOCK_PKG] as one object, so there is no
     * separate "is the clock in front?" check that could pass while the tree used for binding comes
     * from somewhere else. When the window cannot be confirmed the root is null and the caller must
     * refuse: "cannot confirm the window" is never "the object does not exist".
     */
    private data class ClockWindow(
        val root: android.view.accessibility.AccessibilityNodeInfo?,
        val selected: Boolean?,
        val attempts: Int,
        val reason: String,
    ) {
        val confirmable: Boolean get() = root != null
    }

    /**
     * True only when this very node belongs to the clock. Every click is guarded by it, and every
     * binding starts from a root that passed it, so a node can never be acted on from a window the
     * tool did not verify. `packageName` is read from the node itself — no second screen query that
     * could disagree with the tree being used.
     */
    private fun clockOwned(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean =
        node != null && runCatching { node.packageName?.toString() }.getOrNull() == CLOCK_PKG

    /**
     * Every fact a click on [node] must satisfy *at the instant of the click*.
     *
     * Binding proves what the screen looked like when it was scanned; it says nothing about the
     * moment the `ACTION_CLICK` is delivered. In between, the card can be re-created, the tab can
     * change, the list can scroll — the held wrapper then describes a screen that no longer exists,
     * and clicking it is a blind click on whatever the framework now maps that node to.
     *
     * Order matters:
     *  1. `refresh()` — the framework's liveness test on this very object. False or throwing means
     *     the node is gone (or sealed); nothing else we could read from it would be true.
     *  2. package — read *after* the refresh, so it is the current owner, not the remembered one.
     *  3. visibility — a node scrolled out of the viewport is still in the tree; clicking it is not
     *     what the user asked for.
     *  4. `windowId` — must be the same window as the freshly read root. A clock window in front is
     *     not enough if the bound node came from another (replaced) window.
     *
     * Returns null when the click may proceed, otherwise the failing fact for `detail`.
     */
    private fun clickTargetProblem(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        freshRoot: android.view.accessibility.AccessibilityNodeInfo?,
    ): String? {
        if (node == null) return "no-node"
        if (!clockOwned(freshRoot)) return "window-not-clock"
        val alive = runCatching { node.refresh() }.getOrDefault(false)
        if (!alive) return "node-detached"
        val owner = runCatching { node.packageName?.toString() }.getOrNull()
        if (owner != CLOCK_PKG) return "node-owner=$owner"
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return "node-not-visible"
        if (!runCatching { node.isClickable }.getOrDefault(false)) return "node-not-clickable"
        val nodeWindow = runCatching { node.windowId }.getOrDefault(-1)
        val rootWindow = runCatching { freshRoot?.windowId }.getOrNull() ?: -1
        if (nodeWindow != rootWindow) return "node-foreign-window=$nodeWindow root-window=$rootWindow"
        return null
    }

    /**
     * Same recovery contract as [readClockScreen] — put the clock back in front and re-select [tab]
     * — but it hands back the window itself instead of texts, so the nodes bound later provably come
     * from the window that was verified. Retries are bounded; no coordinate fallback.
     */
    private fun verifiedClockWindow(ctx: Context, tab: String): ClockWindow {
        var root = UiAutomator.root().takeIf { clockOwned(it) }
        var attempt = 1
        while (root == null && attempt < CLOCK_READ_ATTEMPTS) {
            runCatching {
                val clock = AppRegistry.find(ctx, "时钟") ?: AppRegistry.find(ctx, "闹钟") ?: return@runCatching
                AppRegistry.launch(ctx, clock)
                clickClockTab(tab)
            }
            Thread.sleep(CLOCK_READ_RETRY_MS)
            root = UiAutomator.root().takeIf { clockOwned(it) }
            attempt++
        }
        if (root == null) return ClockWindow(null, null, attempt, "window-not-clock")
        // Only the binding tree can establish its own tab identity, on every attempt.
        val treeSelected = runCatching { UiAutomator.selectedState(root, tab) }.getOrNull()
        if (treeSelected != true) {
            // Both of these refuse, but they are different facts about the same window: the strip
            // exposed no selection state at all (`null`) versus another tab is the selected one
            // (`false`). Collapsing them into one reason made "cannot confirm" and "wrong page"
            // indistinguishable in the result detail.
            val why = if (treeSelected == false) "binding-tab-not-selected" else "binding-tab-unreported"
            return ClockWindow(null, treeSelected, attempt, why)
        }
        return ClockWindow(root, treeSelected, attempt, "window-clock-tab-selected")
    }

    /**
     * Number of running timers, but only as far as the page *states* it. -1 when unknown.
     *
     * Round 29 review P1: this used to answer from a viewport scan — a bare `添加定时器`, the create
     * picker, or a count of visible cards. None of those enumerate the running list: the list can
     * scroll, a card can leave the visible tree while still running, and `添加定时器` is only the
     * create entry point. Reporting such a number as a total let a *lower* viewport count be read
     * as "this timer is gone". So a total now comes from a sentence only:
     *
     *  - `N 个定时器正在运行` (including `0 个定时器正在运行`) — a header the page itself states;
     *  - an explicit empty sentence (`没有正在运行的定时器`, `暂无定时器`, …).
     *
     * Anything else is -1. A truncated tree invalidates the empty sentence too, but not the header:
     * a statement does not become false because the traversal budget ran out.
     */
    internal fun timerCountFrom(texts: List<String>, truncated: Boolean = false): Int {
        for (t in texts) {
            Regex("(\\d+)\\s*个定时器正在运行").find(t)?.let { return it.groupValues[1].toInt() }
        }
        if (truncated) return -1
        val trimmed = texts.map { it.trim() }
        if (trimmed.any { t -> emptyTimerSentences.any { s -> t.contains(s) } }) return 0
        return -1
    }

    private val emptyTimerSentences = listOf(
        "没有正在运行的定时器", "没有在运行的定时器", "暂无定时器", "无正在运行的定时器",
    )

    /**
     * Lower bound on the running timers visible in one read, or -1 when the shape is unreadable.
     *
     * This is safe to speak as "at least N": every counted card carries both an original-duration
     * marker and its own cancel/pause control in this very read. It is deliberately *not* a total —
     * cards below the fold are simply not seen — so callers must never hand it to a completion
     * claim about a specific object.
     */
    internal fun visibleTimerCards(texts: List<String>, truncated: Boolean = false): Int {
        if (truncated) return -1
        val trimmed = texts.map { it.trim() }
        val hasDuration = trimmed.any { Regex("\\d+\\s*(分钟|秒钟|小时)").containsMatchIn(it) }
        val cancels = trimmed.count { it == "取消" }
        val hasCardControl = trimmed.any { it == "暂停" || it == "继续" || it == "重置" }
        if (hasDuration && hasCardControl && cancels > 0) return cancels
        return -1
    }

    fun queryTimers(ctx: Context): ToolResult {
        val reach = openClockTab(ctx, "定时器")
        Thread.sleep(500)
        val read = readClockScreen(ctx, "定时器", 60)
        if (!reach.reached || !onTargetPage(read, "定时器")) {
            return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "timer_query",
                "没读到时钟的定时器页面，没法确认现在有没有倒计时", describe(read, reach) + " window=not-clock", false
            )
        }
        val truncated = read.textsTruncated || read.nodesTruncated
        val n = timerCountFrom(read.texts, truncated)
        // A viewport scan is only ever a lower bound: safe to speak as "at least N", never as a
        // total, and never as the basis for "that timer is gone".
        val floor = if (n >= 0) n else visibleTimerCards(read.texts, truncated)
        return when {
            n > 0 -> ToolResult.ok("timer_query", "现在有${n}个倒计时在运行", verified = true,
                detail = "count=$n ${describe(read, reach)}")
            n == 0 -> ToolResult.ok("timer_query", "现在没有正在运行的倒计时", verified = true, detail = describe(read, reach))
            floor > 0 -> ToolResult.ok(
                "timer_query", "页面上至少能看到${floor}个倒计时在运行，但没法确认一共有几个",
                detail = "lowerBound=$floor count=unparsed ${describe(read, reach)}",
            )
            else -> ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "timer_query",
                "打开了时钟的定时器页面，但没法确认现在有几个倒计时在运行",
                describe(read, reach) + " count=unparsed truncated=$truncated"
            )
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
        val reach = openClockTab(ctx, "定时器")
        if (!reach.reached) {
            return ToolResult.unavailable("timer_cancel", "打不开时钟，取消不了倒计时", "clock not reachable")
        }
        Thread.sleep(600)
        // Same window gate the read path uses: the tree this scan binds from must *be* the clock.
        // A separate foreground check is not enough — it can pass while the tree used below comes
        // from another window, which is exactly the blind-click this refuses to do.
        val window = verifiedClockWindow(ctx, "定时器")
        if (!window.confirmable) {
            return ToolResult.unavailable("timer_cancel", "无法确认当前是时钟窗口，取消不了倒计时",
                "window=not-clock attempts=${window.attempts} why=${window.reason}")
        }
        if (window.selected == false) {
            return ToolResult.unavailable("timer_cancel", "时钟没有停在定时器页面，取消不了倒计时",
                "window=clock tab=not-selected attempts=${window.attempts}")
        }
        val root = window.root
        val candidates = ArrayList<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        val boundTexts = ArrayList<String>()
        var visited = 0
        var complete = true
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): Pair<List<android.view.accessibility.AccessibilityNodeInfo>, List<Int>> {
            if (n == null || ++visited > 800) { complete = false; return emptyList<android.view.accessibility.AccessibilityNodeInfo>() to emptyList() }
            val text = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            boundTexts.addAll(text)
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
        // Facts about *this click*, checked on this very node now, not at scan time. The wrapper we
        // bound can outlive the screen it described: the card is re-created, the tab changes, the
        // list scrolls. `refresh()` is the framework's own liveness test, and a node that cannot be
        // refreshed must not be acted on. Everything after it is re-read from the refreshed node.
        val problem = clickTargetProblem(button, UiAutomator.root())
        if (problem != null) {
            // The refusal sentence names the fact that failed; they are different observations.
            val speak = when {
                problem.startsWith("node-owner") || problem == "window-not-clock" ->
                    "取消按钮不属于时钟窗口，没有执行取消"
                problem == "node-not-visible" -> "倒计时已经不在当前屏幕上，没有执行取消"
                else -> "要取消的倒计时已经无法确认，没有执行取消"
            }
            return ToolResult.unavailable("timer_cancel", speak, "duration=$seconds click-target=$problem")
        }
        // Never use UiAutomator.click: that helper may climb parents or inject coordinates.
        val clicked = button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) return ToolResult.error("timer_cancel", "已定位倒计时，但取消按钮不可操作", "node click failed")
        val bound = matching.single().second
        val identity = "timer:$bound"
        // Read back the same page the click was bound from. `ACTION_CLICK == true` is the gesture,
        // not the outcome, so the promise "no running timer" is only graded on what the clock shows
        // afterwards. Counts come from the same tree as the labels, so a shortened page cannot
        // report a decrease that the visible cards do not support.
        val beforeCount = timerCountFrom(boundTexts, truncated = !complete)
        Thread.sleep(700)
        val after = readClockScreen(ctx, "定时器", 60)
        val afterPage = onTargetPage(after, "定时器")
        val afterTruncated = after.textsTruncated || after.nodesTruncated
        val afterCount = if (afterPage && !afterTruncated) timerCountFrom(after.texts, false) else -1
        val boundStillListed = after.texts.any {
            val s = it.trim()
            (s.startsWith("初始时长") || s.startsWith("总时长")) &&
                ai.closepaw.voice.tools.ChineseTime.durationSeconds(s) == bound
        }
        val facts = "bound duration=$bound; ACTION_CLICK=$clicked; before=${beforeCount}" +
            " after=${afterCount} page=${if (afterPage) "timer-selected" else "unconfirmed"}" +
            " truncated=$afterTruncated stillListed=$boundStillListed"
        Log.i(TAG, "timer cancel readback: $facts")
        // Round 29 review P1: nothing here identifies *the* cancelled object. `after.texts` is one
        // viewport, `afterCount` is a counter that other timers can move on their own (one may have
        // just run out), and the duration label is not a stable id. So a lower count plus a missing
        // label is NOT evidence that the bound card is gone — the click may have missed while
        // another timer ended off-screen. The only object-level statement allowed is the negative
        // one: the very same card, with the same original duration, is still listed.
        val reason = when {
            !afterPage -> "page-unconfirmed"
            afterTruncated -> "truncated"
            beforeCount < 0 || afterCount < 0 -> "count-not-attributable"
            afterCount >= beforeCount -> "count-not-lower"
            else -> "count-not-attributable"
        }
        return when {
            // The card we bound is still on the page: the clock did not cancel it.
            boundStillListed && afterPage -> ToolResult(
                ToolStatus.SUCCESS, "timer_cancel", "已请求取消，但时钟仍显示这个倒计时", facts, false,
                ai.closepaw.voice.tools.ObservedState(identity, "running:$bound", "clock.timerPage"),
            )
            // Everything else: the request may well have landed, but no read-back can attribute the
            // change to this object. Reported as delivered-without-proof, never as cancelled.
            else -> ToolResult.sentWithoutProof("timer_cancel", "已请求取消指定倒计时，但无法确认最终状态",
                facts + "; readback=$reason")
        }
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
        val reach = openClockTab(ctx, "闹钟")
        Thread.sleep(500)
        val read = readClockScreen(ctx, "闹钟", 120)
        val pageOk = reach.reached && onTargetPage(read, "闹钟")
        // A clipped tree means the list may be missing rows: it cannot support a definite count.
        val truncated = read.textsTruncated || read.nodesTruncated
        val texts = if (pageOk) read.texts else emptyList()
        val listed = texts.map { it.trim() }.filter { Regex("^[0-9]{1,2}:[0-9]{2}$").matches(it) }
        // A missing nextAlarm is only a system scheduling observation, not enumeration.
        // Require an explicit empty-state marker; absent/truncated UI is unknown, not empty.
        val empty = listed.isEmpty() && texts.any { it.trim() in setOf("暂无闹钟", "没有闹钟", "无闹钟") }
        // Auxiliary switches/empty markers cannot establish business page identity.
        // Use exactly the same same-tree package + selected=true gate as pageOk.
        val alarmPage = onTargetPage(read, "闹钟")
        val readable = pageOk && alarmPage && !truncated && (listed.isNotEmpty() || empty)
        val speech = when {
            !pageOk && nextText != null -> "下一个闹钟是 $nextText；无法读取闹钟列表"
            !pageOk -> "系统没有下一个已启用的闹钟；无法读取闹钟列表，不能确认是否存在已关闭的闹钟"
            !alarmPage && nextText != null -> "下一个闹钟是 $nextText；无法确认当前是闹钟列表，不报可见时间的数量"
            !alarmPage -> "系统没有下一个已启用的闹钟；无法确认当前是闹钟列表，不能确认是否存在已关闭的闹钟"
            truncated && nextText != null -> "下一个闹钟是 $nextText；闹钟列表读取被截断，可能还有其它闹钟"
            truncated -> "闹钟列表读取被截断，无法确认闹钟列表"
            nextText != null && listed.isNotEmpty() ->
                "下一个闹钟是 $nextText，当前可见列表有 ${listed.size} 个时间显示（不代表完整列表）"
            nextText != null -> "下一个闹钟是 $nextText；无法读取闹钟列表"
            listed.isNotEmpty() -> "系统没有下一个已启用的闹钟；当前可见列表仍有闹钟，可能包含已关闭的闹钟，不能据此判断全部开关状态"
            empty -> "系统没有下一个已启用的闹钟；当前页面显示闹钟列表为空"
            else -> "系统没有下一个已启用的闹钟；无法读取闹钟列表，不能确认是否存在已关闭的闹钟"
        }
        // Query must not mutate anything: no clicks beyond opening the tab.
        return ToolResult.ok("alarm_query", speech, verified = readable,
            detail = "next=$nextText visible=$listed explicitEmpty=$empty alarmPage=$alarmPage" +
                " readable=$readable truncated=$truncated checkables=${read.checkableCount} ${describe(read, reach)}")
    }

    /**
     * Turn off the alarm at [hour]:[minute]. Never creates one: if the clock UI does not list that
     * time, the answer is "不存在".
     */
    fun closeAlarm(ctx: Context, hour: Int, minute: Int): ToolResult {
        val reach = openClockTab(ctx, "闹钟")
        if (!reach.reached) {
            return ToolResult.unavailable("alarm_close", "打不开时钟，关不了闹钟", "clock not reachable")
        }
        Thread.sleep(700)

        val target = "$hour:" + String.format(java.util.Locale.CHINA, "%02d", minute)
        // closeAlarm used to read the raw active window; our own voice window would then look like an
        // alarm list without the target time, i.e. "no such alarm" instead of "could not read".
        val read = readClockScreen(ctx, "闹钟", 120)
        if (!onTargetPage(read, "闹钟")) {
            return ToolResult.unavailable(
                "alarm_close",
                "没读到时钟的闹钟页面，没法确认${hour}点${minute}分有没有闹钟",
                describe(read, reach) + " window=not-clock"
            )
        }
        val truncated = read.textsTruncated || read.nodesTruncated
        val texts = read.texts
        val present = texts.any { it.trim() == target || it.trim() == "${hour}:${minute}" }
        Log.i(TAG, "closeAlarm present=$present ${describe(read, reach)} textCount=${texts.size}")
        if (!present) {
            // A clipped tree can hide the row: that is "cannot confirm", not "no such alarm".
            if (truncated) {
                return ToolResult.unavailable(
                    "alarm_close",
                    "闹钟列表读取被截断，没法确认${hour}点${minute}分有没有闹钟",
                    describe(read, reach) + " truncated=true"
                )
            }
            // Times on a clock page are not proof of the alarm tab (world clock shows `H:mm` too).
            if (read.selectedTabs["闹钟"] != true && read.checkableCount == 0) {
                return ToolResult.unavailable(
                    "alarm_close",
                    "无法确认当前是闹钟列表，没法确认${hour}点${minute}分有没有闹钟",
                    describe(read, reach) + " checkables=${read.checkableCount} alarmPage=unconfirmed"
                )
            }
            return ToolResult.unavailable(
                "alarm_close",
                "当前可见区域未找到${hour}点${minute}分的闹钟，不能确认不存在，没有执行关闭",
                                "target not visible; full list not enumerated; create NOT attempted"
            )
        }

        if (texts.count { it.trim() == target || it.trim() == "${hour}:${minute}" } != 1) {
            return ToolResult(ToolStatus.BLOCKED, "alarm_close", "无法唯一定位这个时间的闹钟，请确认具体对象", "duplicate time", false)
        }
        // Bind only from the window that is verified to be the clock, and only inside the smallest
        // subtree with exactly one clock time and one switch. Never click the time node (opens an
        // editor), an ancestor, or a coordinate fallback.
        val window = verifiedClockWindow(ctx, "闹钟")
        if (!window.confirmable) {
            return ToolResult.unavailable("alarm_close", "无法确认当前是时钟窗口，没有执行操作",
                "window=not-clock attempts=${window.attempts} why=${window.reason}")
        }
        if (window.selected == false) {
            return ToolResult.unavailable("alarm_close", "时钟没有停在闹钟页面，没有执行操作",
                "window=clock tab=not-selected attempts=${window.attempts}")
        }
        val matches = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        var visited = 0
        var complete = true
        var scrollable = false
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): Pair<List<String>, List<android.view.accessibility.AccessibilityNodeInfo>> {
            if (n == null || ++visited > 800) { complete = false; return emptyList<String>() to emptyList() }
            if (n.isScrollable) scrollable = true
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
        scan(window.root)
        // Loaded nodes are not a full enumeration of a scrollable alarm list.
        if (scrollable) return ToolResult(ToolStatus.BLOCKED, "alarm_close",
            "无法确认完整列表中这个闹钟唯一，没有执行关闭，请确认具体对象",
            "scrollable viewport; full list identity unproven", false)
        if (!complete || matches.size != 1) return ToolResult(ToolStatus.BLOCKED, "alarm_close",
            "无法唯一定位这个闹钟的开关，请确认具体对象", "switch candidates=${matches.size}", false)
        val switch = matches.single()
        // The node must still belong to the clock, and the clock must still be the window in front:
        // a switch that survived a window change must not be clicked.
        if (switch.packageName?.toString() != CLOCK_PKG) {
            return ToolResult.unavailable("alarm_close", "这个开关不属于时钟窗口，没有执行操作",
                "node owner=${switch.packageName} expected=$CLOCK_PKG")
        }
        if (!clockOwned(UiAutomator.root())) {
            return ToolResult.unavailable("alarm_close", "关闭前时钟窗口变了，没有执行操作",
                "window changed before click")
        }
        // Neither a complete traversal nor isScrollable=false proves the OEM loaded all alarms.
        // This adapter has no full-list enumeration or stable business-object identity. Do not
        // mutate (or claim the requested object is already off) using a viewport-only time match.
        return ToolResult(ToolStatus.BLOCKED, "alarm_close",
            "无法确认完整列表中的唯一对象身份，没有执行关闭，请在时钟中确认具体闹钟",
            "full list and business object identity unavailable; no switch click", false)
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
