package ai.closepaw.voice.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ai.closepaw.app.AgentService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Generic UI operations on top of the AccessibilityService.
 *
 * This is the *fallback* layer: the registry always prefers an Intent/API call, and only routes
 * here when the platform offers no interface. Everything is state-driven (node text, class,
 * bounds) — no screen coordinates are ever hard-coded.
 */
object UiAutomator {
    private const val TAG = "UiAutomator"

    fun available(): Boolean = AgentService.instance != null

    private fun svc(): AccessibilityService? = AgentService.instance

    fun root(): AccessibilityNodeInfo? = runCatching { svc()?.rootInActiveWindow }.getOrNull()

    fun foregroundPackage(): String? =
        runCatching { root()?.packageName?.toString() }.getOrNull()

    /** Wait until [pkg] owns the active window — the honest way to verify "app opened". */
    fun waitForPackage(ctx: android.content.Context, pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (foregroundPackage() == pkg) return true
            Thread.sleep(120)
        }
        // No accessibility → cannot verify; report "unknown" rather than a false success.
        Log.i(TAG, "waitForPackage($pkg) timeout, a11y=${available()}")
        return false
    }

    fun findNode(text: String, timeoutMs: Long = 2000): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findFirst(root()) { n ->
                val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
                t.contains(text.lowercase())
            }?.let { return it }
            Thread.sleep(120)
        }
        return null
    }

    fun waitForText(text: String, timeoutMs: Long): Boolean = findNode(text, timeoutMs) != null

    /**
     * Selected state of the node whose text/content-description names [text] (or of its nearest
     * ancestors), i.e. whether that navigation tab is the active one.
     *
     * Three outcomes, deliberately not collapsed: `true` = selected; `false` = the tree exposes
     * selection state and this tab is not the selected one; `null` = the tab is absent or this tree
     * exposes no selection state at all, so "reached" cannot be decided from it.
     */
    /** Tab labels the caller considers navigation peers; a tab is only identified among these. */
    fun tabSelected(text: String, peers: List<String> = CLOCK_TABS): Boolean? =
        selectedState(root(), text, peers)

    /**
     * The exact-label navigation control for [tab] together with the container that proves it is a tab.
     *
     * A same-package node that merely reads "闹钟" (a section header, a settings row, a hint card) is
     * not navigation identity. The control counts only when one of its ancestors shows at least two
     * distinct tab [peers] in its own subtree — i.e. a navigation bar — so both the click target and
     * the selection evidence stay on a real tab.
     */
    internal data class NavControl(
        val control: AccessibilityNodeInfo,
        val container: AccessibilityNodeInfo,
    )

    internal fun navControl(
        r: AccessibilityNodeInfo?,
        tab: String,
        peers: List<String> = CLOCK_TABS,
    ): NavControl? {
        if (r == null) return null
        val target = tab.trim()
        if (target.isEmpty()) return null
        val wanted = peers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.size < 2) return null
        // Round 19: every exact-label candidate is considered, not just the first one in the tree.
        // A `闹钟` section header above the strip used to make the real tab strip unreachable.
        // Round 22: a truncated candidate set is not "every candidate". If the budget or the node
        // cap cut the scan short, ambiguity cannot be decided, so navigation is refused instead of
        // clicking the first strip that happened to fit in the budget.
        val scan = collectExactLabels(r, target)
        if (!scan.complete) return null
        var found: NavControl? = null
        for (control in scan.nodes) {
            val nav = barFor(control, r, wanted) ?: continue
            val seen = found
            // Two *different* strips that both qualify cannot be told apart → refuse, don't pick one.
            // "Different" is logical identity: the platform may hand back another wrapper object for
            // the same strip, which must not be mistaken for a rival strip (round 22).
            if (seen != null && !sameNode(seen.container, nav.container)) return null
            if (seen == null) found = nav
        }
        return found
    }

    /** Walk up from [control] (bounded) to the first ancestor that passes [isNavBar]. */
    private fun barFor(
        control: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        wanted: Set<String>,
    ): NavControl? {
        var cur: AccessibilityNodeInfo? = runCatching { control.parent }.getOrNull()
        var hops = 0
        while (cur != null && hops++ < NAV_MAX_HOPS) {
            if (isNavBar(cur, root, wanted)) return NavControl(control, cur)
            cur = runCatching { cur!!.parent }.getOrNull()
        }
        return null
    }

    /** Exact-label candidates plus whether the scan is provably exhaustive. */
    private class CandidateScan(
        val nodes: List<AccessibilityNodeInfo>,
        val complete: Boolean,
    )

    /**
     * Bounded collection of every exact-label node for [target] below [root].
     *
     * Round 22: the previous version stopped *collecting* at [NAV_MAX_CANDIDATES] but kept scanning,
     * silently dropping the rest, so callers believed they had seen every candidate. Overflow or an
     * unfinished walk (node cap) now reports `complete = false` — partial evidence is never treated
     * as the full set.
     */
    private fun collectExactLabels(
        root: AccessibilityNodeInfo,
        target: String,
    ): CandidateScan {
        val out = ArrayList<AccessibilityNodeInfo>()
        var matched = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val n = queue.removeFirst()
            if (exactLabel(n, target)) {
                matched++
                if (out.size < NAV_MAX_CANDIDATES) out.add(n)
            }
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        val walkedToEnd = queue.isEmpty()
        return CandidateScan(out, walkedToEnd && matched <= NAV_MAX_CANDIDATES)
    }

    /**
     * Structure-only test for "this ancestor is a navigation bar".
     *
     * Review round 18: the previous rule was "climb until an ancestor holds two clock tab labels",
     * which the page root always satisfies once the body carries a `闹钟` header and a `定时器` row.
     * The rule is now bounded and fails closed:
     *  - the window root is never a bar — a page body always shows tab labels somewhere;
     *  - the peer labels must sit at the same depth (±1), as tabs in one strip do;
     *  - the bar must be small; an ancestor that swallowed the page content is not a bar;
     *  - at least two distinct peer labels must be present;
     *  - the bar must not live inside a scrolling surface: page content scrolls, a tab strip does
     *    not (round 20 — a tab-shaped strip inside a list is content, not navigation);
     *  - each peer label must own a click target below the bar, and two peer labels must never
     *    share one. A `闹钟` section header next to a tappable `定时器` settings row has no per-label
     *    unit, so it is refused instead of clicked (round 20: it used to deliver ACTION_CLICK to
     *    that row).
     *
     * This is still a heuristic — absence of a bar returns null, never a guess.
     */
    private fun isNavBar(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        wanted: Set<String>,
    ): Boolean {
        // Logical, not referential: the platform may return another wrapper for the same root
        // (round 22, review P1).
        if (sameNode(node, root)) return false
        if (insideScrollable(node, root)) return false
        val labels = labelNodes(node, wanted)
        if (labels.size < 2) return false
        val depths = labels.values.map { depthBelow(node, it) }
        // Round 22: [UNREACHABLE_DEPTH] means "the parent chain never reached this bar". Two labels
        // that are both unreachable used to share that sentinel, so the spread check below passed
        // without any proven membership; refuse instead.
        if (depths.any { it >= UNREACHABLE_DEPTH }) return false
        if (depths.max() - depths.min() > NAV_MAX_LABEL_SPREAD) return false
        // One tab = one click target, and never one target shared by two tabs.
        // Identity semantics: two units are "the same target" when they are the same logical node,
        // even through different wrappers (round 22). Only ≤4 labels, so a list is exact.
        val units = ArrayList<AccessibilityNodeInfo>()
        for ((label, labelNode) in labels) {
            val unit = clickUnit(labelNode, node) ?: return false
            if (units.any { sameNode(it, unit) }) return false
            units.add(unit)
        }
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        while (queue.isNotEmpty() && count <= NAV_MAX_BAR_NODES) {
            val n = queue.removeFirst()
            count++
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return count <= NAV_MAX_BAR_NODES
    }

    /** The first exact-label node for each peer label inside [node]'s subtree. */
    private fun labelNodes(node: AccessibilityNodeInfo, wanted: Set<String>): Map<String, AccessibilityNodeInfo> {
        val found = HashMap<String, AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_NODES) {
            val n = queue.removeFirst()
            for (label in wanted) if (label !in found && exactLabel(n, label)) found[label] = n
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return found
    }

    /**
     * Distance from [node] up to [bar]; [UNREACHABLE_DEPTH] when the parent chain does not lead
     * there. Walking parents — not a subtree scan — is what proves the label sits inside this bar.
     */
    private fun depthBelow(bar: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Int {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < UNREACHABLE_DEPTH) {
            if (sameNode(cur, bar)) return depth
            cur = runCatching { cur!!.parent }.getOrNull()
            depth++
        }
        return UNREACHABLE_DEPTH
    }

    /**
     * The click target a tab label owns: itself or its nearest clickable ancestor, never the bar
     * container. `null` = this label is not an interactive tab, so the container is not a strip.
     */
    private fun clickUnit(label: AccessibilityNodeInfo, bar: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = label
        var hops = 0
        while (cur != null && hops++ <= NAV_MAX_HOPS + 2) {
            val node = cur
            if (sameNode(node, bar)) return null
            if (runCatching { node.isClickable }.getOrDefault(false)) return node
            cur = runCatching { node.parent }.getOrNull()
        }
        return null
    }

    /**
     * A tab strip lives outside the scrolling surface: anything inside a scrollable ancestor is page
     * content (list rows, cards) and is never navigation.
     */
    private fun insideScrollable(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops++ <= NAV_MAX_HOPS + 2) {
            val n = cur
            if (runCatching { n.isScrollable }.getOrDefault(false)) return true
            if (sameNode(n, root)) return false
            cur = runCatching { n.parent }.getOrNull()
        }
        return false
    }

    /**
     * Click a tab control with ACTION_CLICK only, on the control or on a clickable ancestor that is
     * still inside its navigation container. No generic parent climb past the nav bar and no
     * coordinate gesture: `UiAutomator.click`/`tap` are deliberately not reachable from here.
     *
     * Review round 18: the bar container itself is never a click target — clicking it would land on
     * the whole strip (or the page, if the heuristic ever mis-identified it) instead of one tab.
     */
    internal fun clickTabControl(nav: NavControl): Boolean {
        var cur: AccessibilityNodeInfo? = nav.control
        var hops = 0
        while (cur != null && hops++ < NAV_MAX_HOPS) {
            val node = cur
            if (sameNode(node, nav.container)) return false
            if (runCatching { node.isClickable }.getOrDefault(false) &&
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            ) return true
            cur = runCatching { node.parent }.getOrNull()
        }
        return false
    }

    /**
     * Logical node identity for the navigation walk.
     *
     * Review round 22 (P1): `getParent()`/`getChild()` hand back `AccessibilityNodeInfo` wrapper
     * objects, and the same logical node is not guaranteed to arrive as the same instance. Reference
     * identity (`===`, `IdentityHashMap`) therefore cannot decide "same strip", "reached the bar
     * boundary" or "same click target": with a re-wrapped node the old code either refused a real
     * strip as ambiguous or stopped enforcing the container/root click boundaries. The framework's
     * own `equals` compares window id + source node id, i.e. logical identity, so it is used first
     * and reference identity is kept as the fallback when `equals` is unavailable (a recycled or
     * detached node can throw).
     *
     * This is a JVM-level identity contract only; whether Samsung's UI really re-wraps nodes during
     * one tree walk is unverified without a device.
     */
    private fun sameNode(a: AccessibilityNodeInfo?, b: AccessibilityNodeInfo?): Boolean {
        if (a == null || b == null) return a === b
        if (a === b) return true
        return runCatching { a.equals(b) }.getOrDefault(false)
    }

    private fun exactLabel(n: AccessibilityNodeInfo, target: String): Boolean =
        listOfNotNull(
            runCatching { n.text?.toString() }.getOrNull(),
            runCatching { n.contentDescription?.toString() }.getOrNull(),
        ).any { it.trim() == target }

    /**
     * Selection evidence must come from the tab control or its ancestors *inside* the bar — never
     * from the bar container itself (round 18: a selected container is not a selected tab).
     */
    internal fun selectedState(
        r: AccessibilityNodeInfo?,
        text: String,
        peers: List<String> = CLOCK_TABS,
    ): Boolean? {
        if (r == null) return null
        val target = text.trim()
        if (target.isEmpty()) return null
        val nav = navControl(r, target, peers) ?: return null
        var cur: AccessibilityNodeInfo? = nav.control
        var hops = 0
        while (cur != null && hops++ < NAV_MAX_HOPS) {
            val node = cur
            if (sameNode(node, nav.container)) break
            if (runCatching { node.isSelected }.getOrDefault(false)) return true
            cur = runCatching { node.parent }.getOrNull()
        }
        // Round 19: selection is judged inside the identified strip only. A selected card elsewhere on
        // the page is not evidence that "this tab is not selected" — it used to produce that verdict.
        // Anything in the strip subtree that reports selection (including the strip container itself,
        // which some layouts use for container-level reporting) means this tab is not the selected one;
        // when nothing inside the strip reports selection, the answer is "unknown", never `false`.
        var stripSelected = false
        val targets = ArrayList<AccessibilityNodeInfo>()
        targets.add(nav.container)
        var visited = 0
        while (targets.isNotEmpty() && visited++ < MAX_NODES) {
            val n = targets.removeAt(targets.size - 1)
            if (runCatching { n.isSelected }.getOrDefault(false)) { stripSelected = true; break }
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { targets.add(it) }
            }
        }
        if (!stripSelected) return null
        return false
    }

    fun clickText(text: String): Boolean {
        val n = findNode(text) ?: return false
        return click(n)
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = node.parent
        var hops = 0
        while (p != null && hops++ < 4) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
        }
        val b = bounds(node) ?: return false
        return tap(b.centerX().toFloat(), b.centerY().toFloat())
    }

    /** Type into the first editable node (ACTION_SET_TEXT, no keystroke simulation). */
    fun inputText(text: String): Boolean {
        val editable = findFirst(root()) { it.isEditable } ?: return false
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val s = svc() ?: return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return runCatching { s.dispatchGesture(g, null, null) }.getOrDefault(false)
    }

    fun tap(x: Float, y: Float): Boolean {
        val s = svc() ?: return false
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = CountDownLatch(1)
        val ok = runCatching {
            s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
        }.getOrDefault(false)
        latch.await(1200, TimeUnit.MILLISECONDS)
        return ok
    }

    fun back(): Boolean = runCatching {
        svc()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }.getOrDefault(false) == true

    fun home(): Boolean = runCatching {
        svc()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }.getOrDefault(false) == true

    fun bounds(n: AccessibilityNodeInfo): Rect? =
        runCatching { Rect().also { n.getBoundsInScreen(it) } }.getOrNull()

    internal fun findFirst(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 800) {
            val n = queue.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        return null
    }

    /** All visible texts, used by diagnostics and by "read the screen" fallbacks. */
    fun screenTexts(limit: Int = 60): List<String> = readScreen(limit).texts

    /**
     * A bounded breadth-first read of the active window.
     *
     * Why this exists: "no text matched" and "the tree was cut short" are different facts, and the
     * device run of the alarm/timer query could not tell them apart. [nodesVisited], [nodesTruncated]
     * and [textsTruncated] are reported so a caller can say which boundary it hit instead of
     * collapsing everything into "unreadable". This is diagnostics only: what counts as a
     * successful read is unchanged.
     */
    data class ScreenRead(
        val texts: List<String> = emptyList(),
        val nodesVisited: Int = 0,
        val nodesTruncated: Boolean = false,
        val textsTruncated: Boolean = false,
        val rootPackage: String? = null,
        val serviceAvailable: Boolean = false,
        /**
         * How many nodes in this read advertise a checkable control. The alarm list rows carry
         * switches and the other clock tabs (world clock, stopwatch) carry none, so this is the
         * structural evidence that separates "this is the alarm list" from "this is a clock page
         * that happens to show `H:mm` text".
         */
        val checkableCount: Int = 0,
        val selectedTabs: Map<String, Boolean?> = emptyMap(),
    )

    const val MAX_NODES = 600

    /** Bounded climb from a label to its navigation bar; deeper than any real tab structure. */
    private const val NAV_MAX_HOPS = 5

    /**
     * A navigation bar is small. An ancestor that reaches this many nodes has swallowed page content
     * and is not a tab strip (review round 18: the page root satisfied the old two-label rule).
     */
    private const val NAV_MAX_BAR_NODES = 40

    /** Depth that can never be a real label depth; also the "not inside this bar" marker. */
    private const val UNREACHABLE_DEPTH = 1_000

    /** Peer tab labels in one strip sit at the same depth; allow one level of icon-label nesting. */
    private const val NAV_MAX_LABEL_SPREAD = 1

    /**
     * Exact-label nodes considered when looking for the tab control (round 19). Two or more *distinct*
     * strips that both qualify are refused as ambiguous instead of picking the first match.
     */
    private const val NAV_MAX_CANDIDATES = 8

    /**
     * The clock's bottom-navigation labels. Two or more of them under one ancestor is the structural
     * evidence that identifies a navigation bar, so a lone same-package "闹钟" is never a tab.
     */
    val CLOCK_TABS: List<String> = listOf("闹钟", "定时器", "世界时钟", "秒表")

    /** Package that owns the clock UI we are allowed to interpret as a business page. */
    const val CLOCK_PKG: String = "com.sec.android.app.clockpackage"

    /**
     * The only entry point allowed to touch the clock's tab bar.
     *
     * Review `6546660320` P2: the timer-creation read path reached the timer tab with
     * `clickText("定时器")`, a *containing-text* match whose [click] climbs to a clickable parent and
     * finally taps coordinates. That could land on a content row (`定时器设置`) or a hint card while
     * the read still came from the clock package. This is the strict version, shared by every caller:
     * the label must be an exact tab inside a navigation bar, the tab control and its container must
     * both belong to the clock, and the only action delivered is ACTION_CLICK on the control (or a
     * clickable ancestor still inside that bar) — never a coordinate gesture.
     *
     * `null` = no verified tab control, `false` = a control exists but the click was refused.
     */
    internal fun clickClockNavTab(tab: String): Boolean? = runCatching {
        val r = root() ?: return@runCatching null
        if (r.packageName?.toString() != CLOCK_PKG) return@runCatching false
        val nav = navControl(r, tab, CLOCK_TABS) ?: return@runCatching null
        if (nav.control.packageName?.toString() != CLOCK_PKG) return@runCatching false
        if (nav.container.packageName?.toString() != CLOCK_PKG) return@runCatching false
        clickTabControl(nav)
    }.getOrNull()

    fun readScreen(limit: Int = 60): ScreenRead = readTree(root(), limit, available())

    /**
     * Pure tree read, separated from the service lookup so the truncation contract is testable.
     * [nodesTruncated] is true only when the node cap stopped the walk with nodes still queued;
     * [textsTruncated] is true only when non-blank text was actually dropped by [limit].
     */
    internal fun readTree(r: AccessibilityNodeInfo?, limit: Int, serviceAvailable: Boolean): ScreenRead {
        if (r == null) return ScreenRead(serviceAvailable = serviceAvailable)
        val out = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        var visited = 0
        var dropped = false
        var checkables = 0
        fun take(s: CharSequence?) {
            val t = s?.toString()
            if (t.isNullOrBlank()) return
            if (out.size < limit) out.add(t) else dropped = true
        }
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            if (runCatching { n.isCheckable }.getOrDefault(false)) checkables++
            take(n.text)
            take(n.contentDescription)
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        val pkg = runCatching { r.packageName?.toString() }.getOrNull()
        return ScreenRead(out, visited, queue.isNotEmpty(), dropped, pkg, serviceAvailable, checkables,
            CLOCK_TABS.associateWith { selectedState(r, it, CLOCK_TABS) })
    }
}
