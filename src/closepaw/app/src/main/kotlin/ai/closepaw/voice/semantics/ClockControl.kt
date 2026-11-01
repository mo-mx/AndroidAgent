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

    /** One timer card bound from a single read: its cancel control, its declared duration, its text. */
    private data class BoundTimerCard(
        val button: android.view.accessibility.AccessibilityNodeInfo,
        val duration: Int,
        val texts: List<String>,
    )

    /** What one node's subtree contributes to the cancel scan. */
    private data class TimerScan(
        val buttons: List<android.view.accessibility.AccessibilityNodeInfo>,
        val durations: List<Int>,
        val texts: List<String>,
    )

    /** Every text/contentDescription in [n]'s subtree, bounded by the same node budget as a scan. */
    private fun textsUnder(n: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
        if (n == null) return emptyList()
        val out = ArrayList<String>()
        var visited = 0
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.addLast(n)
        while (stack.isNotEmpty() && ++visited <= 800) {
            val cur = stack.removeLast()
            cur.text?.toString()?.let { out.add(it) }
            cur.contentDescription?.toString()?.let { out.add(it) }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    /** Card text that says the timer is *not* running any more (these keep their duration label). */
    private val stoppedTimerMarkers =
        listOf("已暂停", "暂停中", "已结束", "已响铃", "已完成", "已停止", "剩余 0 秒")

    /** Card control labels that only exist in one state. */
    private const val PAUSE_CONTROL = "暂停"
    private const val RESUME_CONTROL = "继续"

    /** Explicit statements that the timer is counting down. */
    private val runningTimerMarkers = listOf("正在运行", "计时中", "倒计时中")

    /**
     * Does this bound card *state* that its timer is running? `true` running, `false` stopped,
     * `null` no evidence either way.
     *
     * Round 44 review P1/P2: round 41 compared `N 个定时器正在运行` with `candidates.size`, but the
     * candidates were every card carrying an original-duration label and one cancel control — and a
     * paused card keeps both. The two numbers therefore counted different populations, so equality
     * proved nothing: one running card plus one paused card covers a stated total of 2 while a
     * running twin of the requested duration sits below the fold.
     *
     * Round 46 review P1: round 44 then took *any* remaining-time text as running evidence. A paused
     * Samsung card keeps its frozen readout and shows `继续` rather than an `已暂停` marker, so it was
     * counted as running and the same false equality came back. Text that both populations carry
     * cannot decide membership: only a state-specific control (`暂停` / `继续`) or an explicit
     * running marker does. Readout-only cards are therefore `null` — unproven — and an unproven card
     * makes the set incomparable, which fails closed. `初始时长` / `总时长` are original-duration
     * evidence, never running evidence.
     */
    internal fun cardRunningState(texts: List<String>): Boolean? {
        val t = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (t.any { s -> stoppedTimerMarkers.any { s.contains(it) } }) return false
        // A resume control exists only while the timer is paused; a pause control only while it runs.
        if (t.any { it == RESUME_CONTROL }) return false
        if (t.any { it == PAUSE_CONTROL }) return true
        if (t.any { s -> runningTimerMarkers.any { s.contains(it) } }) return true
        return null
    }

    /**
     * Round 47: the flat-text "at least N running timers" count is gone.
     *
     * It counted one `取消` per card while accepting `继续`/`重置` as generic card controls, so a
     * viewport of *paused* timers spoke "页面上至少能看到2个倒计时在运行" — the same error round 46 fixed
     * on the cancel path. It also never looked at a node, so a tree from another app answered with
     * the same sentence. A bound is now counted per card from the verified clock tree
     * ([runningBound]): only a state-specific control proves a card is running.
     */

    /** A card tree bound from one read, with the facts needed to know what that binding covers. */
    private data class TimerBinding(
        val cards: List<BoundTimerCard>,
        val texts: List<String>,
        val buttons: List<android.view.accessibility.AccessibilityNodeInfo>,
        val complete: Boolean,
        val scrollable: Boolean,
    )

    /**
     * Bind every timer card in [root] once: the smallest subtree holding exactly one
     * original-duration label and exactly one cancel control, plus every text below it.
     *
     * Shared by [cancelTimer] (which clicks one of them) and [runningBound] (which counts the ones
     * that prove they are running), so a count and a click cannot disagree about what a card is.
     * `complete` only means the walk stayed inside its node budget: a scrollable list can still hold
     * cards this walk never loaded.
     */
    private fun bindTimerCards(root: android.view.accessibility.AccessibilityNodeInfo?): TimerBinding {
        val candidates = ArrayList<BoundTimerCard>()
        val boundTexts = ArrayList<String>()
        var visited = 0
        var complete = true
        var scrollable = false
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): TimerScan {
            if (n == null || ++visited > 800) { complete = false; return TimerScan(emptyList(), emptyList(), emptyList()) }
            if (n.isScrollable) scrollable = true
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
                val sub = scan(n.getChild(i))
                buttons.addAll(sub.buttons); durations.addAll(sub.durations)
                if (candidates.size > before) childBound = true
            }
            // A bound card carries every text below it, so the caller can tell a running card from a
            // paused one instead of comparing a running header with all cards.
            val texts = textsUnder(n)
            // Do not bind a global container or widen an already-bound card to a sibling.
            if (n != root && !childBound && buttons.size == 1 && durations.size == 1)
                candidates.add(BoundTimerCard(buttons.single(), durations.single(), texts))
            return TimerScan(buttons, durations, texts)
        }
        val buttons = scan(root).buttons
        return TimerBinding(candidates, boundTexts, buttons, complete, scrollable)
    }

    /** Facts behind an "at least N are running" answer. [count] is -1 when even that is unproven. */
    private data class RunningBound(val count: Int, val why: String)

    /**
     * Lower bound on *running* timers, counted only over cards that prove their own state.
     *
     * Round 47: the query path answered "at least N" from a flat text list. A paused Samsung card
     * keeps its frozen readout and shows `继续`, so text both populations carry cannot decide
     * membership — and the flat count never looked at the window, so a tree from another app
     * produced the same sentence. A count of cards that each carry running-only evidence is a real
     * lower bound (each one *is* a running timer, and cards below the fold are simply absent from
     * it), and it is still never a total.
     *
     * Refused (count = -1) when the read was clipped, the tree cannot be confirmed as the clock
     * window, the clock is not on the timer tab, or the walk hit its node budget. There is no
     * coordinate, click or mutation on this path.
     */
    private fun runningBound(ctx: Context, truncated: Boolean): RunningBound {
        if (truncated) return RunningBound(-1, "running=-1 why=truncated")
        val window = runCatching { verifiedClockWindow(ctx, "定时器") }.getOrNull()
            ?: return RunningBound(-1, "running=-1 why=window-unreadable")
        if (!window.confirmable) return RunningBound(-1, "running=-1 why=window-not-clock")
        if (window.selected == false) return RunningBound(-1, "running=-1 why=timer-tab-not-selected")
        val tree = runCatching { bindTimerCards(window.root) }.getOrNull()
            ?: return RunningBound(-1, "running=-1 why=bind-failed")
        if (!tree.complete) return RunningBound(-1, "running=-1 why=walk-incomplete")
        val states = tree.cards.map { cardRunningState(it.texts) }
        val running = states.count { it == true }
        val stopped = states.count { it == false }
        val unproven = states.size - running - stopped
        return RunningBound(running, "running=$running stopped=$stopped unproven=$unproven cards=${states.size}")
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
        // A scan is only ever a lower bound, and only the cards that *prove* they are running may be
        // counted (round 47): a paused card keeps the same readout, so text cannot decide membership,
        // and the count has to come from the tree that was verified to be the clock. Never a total,
        // never the basis for "that timer is gone".
        val bound = if (n >= 0) RunningBound(0, "running=0 header") else runningBound(ctx, truncated)
        return when {
            n > 0 -> ToolResult.ok("timer_query", "现在有${n}个倒计时在运行", verified = true,
                detail = "count=$n ${describe(read, reach)}")
            n == 0 -> ToolResult.ok("timer_query", "现在没有正在运行的倒计时", verified = true, detail = describe(read, reach))
            bound.count > 0 -> ToolResult.ok(
                "timer_query", "页面上至少能看到${bound.count}个正在运行的倒计时，但没法确认一共有几个",
                detail = "lowerBound=${bound.count} count=unparsed ${bound.why} ${describe(read, reach)}",
            )
            else -> ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "timer_query",
                "打开了时钟的定时器页面，但没法确认现在有几个倒计时在运行",
                describe(read, reach) + " count=unparsed truncated=$truncated ${bound.why}"
            )
        }
    }

    /**
     * One complete read of the timer page, reduced to the two facts a creation can be attributed
     * against: the header's running count and how many cards declare *this* original duration.
     *
     * [readable] is false whenever the read cannot support a comparison at all (page identity,
     * truncation, unparsed count). The duration labels are counted as text, not bound to a node:
     * that is weaker than [cancelTimer]'s one-control-per-card binding, and it is exactly why a
     * same-duration twin disables the claim instead of being ignored.
     */
    private data class TimerDraft(
        val readable: Boolean,
        val count: Int,
        val ofDuration: Int,
        val why: String,
    ) {
        fun facts(seconds: Int) = "count=$count cards@${seconds}s=$ofDuration why=$why"
    }

    private fun timerDraft(ctx: Context, seconds: Int): TimerDraft {
        val reach = openClockTab(ctx, "定时器")
        Thread.sleep(400)
        val read = readClockScreen(ctx, "定时器", 60)
        if (!reach.reached || !onTargetPage(read, "定时器")) return TimerDraft(false, -1, -1, "page-unconfirmed")
        if (read.textsTruncated || read.nodesTruncated) return TimerDraft(false, -1, -1, "truncated")
        val count = timerCountFrom(read.texts, truncated = false)
        val ofDuration = read.texts.count {
            val s = it.trim()
            (s.startsWith("初始时长") || s.startsWith("总时长")) &&
                ai.closepaw.voice.tools.ChineseTime.durationSeconds(s) == seconds
        }
        if (count < 0) return TimerDraft(false, -1, ofDuration, "count-unparsed")
        return TimerDraft(true, count, ofDuration, "ok")
    }

    /**
     * Create a timer, then read the timer page back and report only what the read supports.
     *
     * Round 31: `IntentActions.setTimer` proves delivery (or that the page shows some countdown
     * wording) but nothing about the requested object, so this read-back was added. Round 33 review
     * P1 showed the read-back itself cannot carry a verdict: `timerDraft` counts duration labels in
     * *the loaded accessibility tree*, and `textsTruncated = false` only means the walk stayed inside
     * its budget — a scrollable/virtualised timer list still holds cards this read never saw. So
     * `before.ofDuration == 0` cannot prove the baseline had no same-duration timer, and the pair
     * (no such card before, count +1, one such card after) is equally consistent with a request that
     * never landed plus somebody else's creation. Every branch is therefore UNPROVEN; the facts are
     * reported, the conclusion is not.
     *
     * Attribution would need a stable object id in the read-back or a full enumeration of the list.
     * Neither exists yet, and inventing one from a viewport is how the old branch shipped a false
     * "已经设好".
     */
    fun createTimer(ctx: Context, seconds: Int): ToolResult {
        val before = timerDraft(ctx, seconds)
        val sent = IntentActions.setTimer(ctx, seconds)
        val human = if (seconds % 60 == 0) "${seconds / 60}分钟" else "${seconds}秒"
        // A refused Intent is not graded against the page: nothing was requested.
        if (sent.status != ToolStatus.SUCCESS) {
            ActionMemory.recordResult(Action.CREATE, Target.TIMER, mapOf("seconds" to "$seconds"), sent)
            return sent
        }
        val after = timerDraft(ctx, seconds)
        val facts = "before=${before.facts(seconds)}; after=${after.facts(seconds)}"
        Log.i(TAG, "timer create readback: $facts")
        val r = when {
            !before.readable -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但没有读到设置前的页面，没法确认它是否真的开始",
                "$facts; readback=before-${before.why}"
            )
            !after.readable -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但没法确认它是否真的开始",
                "$facts; readback=after-${after.why}"
            )
            // The same duration was already on the page: the new object cannot be told apart from
            // the old one, so a count of matching labels proves nothing about this request.
            before.ofDuration != 0 -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但页面上本来就有同样时长的倒计时，没法确认新的那个是否开始",
                "$facts; readback=same-duration-twin"
            )
            after.count != before.count + 1 -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但没法确认它是否真的开始",
                "$facts; readback=count-not-plus-one"
            )
            after.ofDuration == 0 -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但页面上没看到这个时长的倒计时",
                "$facts; readback=duration-card-not-found"
            )
            after.ofDuration > 1 -> ToolResult.sentWithoutProof(
                "timer", "已经请求设置${human}的倒计时，但页面上有多个这个时长的倒计时，没法确认新的那个",
                "$facts; readback=duration-card-not-single"
            )
            // Every check passed — and that still is not attribution. The pre-read only proves the
            // loaded tree held no such card, not that the timer list held none, so this shape is
            // reachable with a request that did nothing plus an off-screen twin plus a foreign
            // creation. Facts, not a verdict.
            else -> ToolResult.sentWithoutProof(
                "timer",
                "已经请求设置${human}的倒计时，页面上出现了这个时长的卡片，但没法确认是这次创建的，请在时钟里核对",
                "$facts; readback=count-plus-one-duration-card-unattributed"
            )
        }
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
        // Round 47: one binding helper, shared with `runningBound`, so the count the query speaks and
        // the card the cancel clicks can never disagree about what a card is.
        val tree = bindTimerCards(root)
        val candidates = tree.cards
        val boundTexts = tree.texts
        val allButtons = tree.buttons
        val complete = tree.complete
        val scrollable = tree.scrollable
        val matching = candidates.filter { seconds == null || it.duration == seconds }
        // Round 41: `complete` only means the walk stayed inside its node budget. A scrollable
        // container can hold timer cards this walk never loaded, so the bound set is a *viewport*,
        // not the list — and one visible 5-minute card is exactly what a phone running two of them
        // looks like from here. Uniqueness therefore needs the page's own statement: a header total
        // (`N 个定时器正在运行`) that the fully bound cards actually cover. Without it (or with fewer
        // bound cards than it reports) the set is unproven and nothing is clicked.
        // Round 44: "covers the total" only means something when both numbers count the same
        // population. The header counts *running* timers; a paused card keeps its original duration
        // and its cancel control, so it was being counted against a running total. Equality of two
        // different populations proves nothing: one running plus one paused card covers a stated
        // total of 2 while a running twin of the requested duration sits below the fold. The
        // comparison is therefore made only over cards that prove they are running.
        val runningStates = candidates.map { cardRunningState(it.texts) }
        val runningProven = runningStates.count { it == true }
        val stoppedBound = runningStates.count { it == false }
        val unprovenBound = runningStates.size - runningProven - stoppedBound
        val comparableSet = stoppedBound == 0 && unprovenBound == 0
        val statedTotal = timerCountFrom(boundTexts, truncated = !complete)
        val viewportComplete = complete && !scrollable
        val coveredByPageTotal = statedTotal >= 0 && comparableSet && candidates.size >= statedTotal
        if (!viewportComplete && !coveredByPageTotal) {
            val speak = when {
                scrollable && !comparableSet ->
                    "倒计时列表可以滚动，而且没法确认视口里每张卡片都在运行，没法确认还有没有同样时长的倒计时，没有执行取消"
                scrollable ->
                    "倒计时列表可以滚动，没法确认视口外还有没有同样时长的倒计时，没有执行取消"
                else ->
                    "屏幕读取被截断，没法确认还有没有同样时长的倒计时，没有执行取消"
            }
            return ToolResult(ToolStatus.BLOCKED, "timer_cancel", speak,
                "duration=$seconds candidates=${candidates.size} complete=$complete" +
                    " scrollable=$scrollable stated=$statedTotal running=$runningProven" +
                    " stopped=$stoppedBound unproven=$unprovenBound" +
                    (if (comparableSet) " viewport-is-not-the-list" else " set-mixed viewport-is-not-the-list"),
                false)
        }
        // An unreadable second card might be the same requested duration, so it cannot be ignored.
        if (!complete || allButtons.size != candidates.size || matching.size != 1) {
            return ToolResult(ToolStatus.BLOCKED, "timer_cancel",
                "无法唯一定位你说的倒计时，请在时钟中确认具体对象", "duration=$seconds candidates=${matching.size} complete=$complete", false)
        }
        val button = matching.single().button
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
        val bound = matching.single().duration
        // Read back the same page the click was bound from. `ACTION_CLICK == true` is the gesture,
        // not the outcome, so nothing is claimed from it. Counts come from the same tree as the
        // labels, so a shortened page cannot report a decrease that the visible cards do not support.
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
        // Rounds 29 and 33 reviews: nothing in this read identifies *the* cancelled object. One
        // viewport is not an enumeration, the header count can be moved by other timers, and a
        // declared original duration is not a stable id.
        //   - lower count + label gone  → could be this click, or another timer ending off-screen;
        //   - label still listed        → could be a *different* timer of the same duration that
        //                                 scrolled in after this one was cancelled.
        // Neither direction supports an object-level verdict, so `stillListed` no longer produces a
        // CONTRADICTED "时钟仍显示这个倒计时" (round 33 review P2), and it is checked only after the
        // page/truncation gates instead of before them.
        val reason = when {
            !afterPage -> "page-unconfirmed"
            afterTruncated -> "truncated"
            boundStillListed -> "same-duration-still-listed"
            beforeCount < 0 || afterCount < 0 -> "count-not-attributable"
            afterCount >= beforeCount -> "count-not-lower"
            else -> "count-not-attributable"
        }
        val speak = if (reason == "same-duration-still-listed") {
            "已请求取消指定倒计时，页面上仍有一个同样时长的倒计时，无法确认是不是刚才那个"
        } else {
            "已请求取消指定倒计时，但无法确认最终状态"
        }
        return ToolResult.sentWithoutProof("timer_cancel", speak, facts + "; readback=$reason")
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
