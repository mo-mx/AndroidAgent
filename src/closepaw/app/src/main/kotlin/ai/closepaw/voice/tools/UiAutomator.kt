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

    /** `AccessibilityWindowInfo` reports no id (`INVALID_WINDOW_ID`) for detached/unknown nodes. */
    internal const val INVALID_WINDOW = -1

    /**
     * Node budget for the page-structure signature. Larger than a control walk on purpose (the
     * skeleton has to cover the page), but still bounded: a walk that hits it yields `null` rather
     * than a fingerprint of a prefix.
     */
    private const val PAGE_SIGNATURE_NODES = 600

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
     * A control that was found *inside the target app's window* and can be acted on.
     */
    data class FeatureControl(
        val node: AccessibilityNodeInfo,
        val label: String,
        /** `viewIdResourceName` of the clicked node (null/empty when the app exposes none). */
        val viewId: String? = null,
        /** Class name of the clicked node — the only role signal accessibility reliably exposes. */
        val className: String? = null,
    )

    /**
     * Why a feature control was not found. A bare null cannot separate "the app's window is not in
     * front" from "the label is not in the tree", and those need different answers.
     */
    data class FeatureLookup(
        val control: FeatureControl?,
        val why: String?,
        /** The window snapshot [control] was resolved from (null = the lookup failed). */
        val window: AccessibilityNodeInfo? = null,
    )

    /**
     * Find the clickable control whose label contains [text], but only inside a window that belongs
     * to [pkg].
     *
     * `findNode` matches any label in the *active* window: a permission sheet, a system dialog or a
     * same-text hint card from another app would be matched and clicked, and the foreground check
     * after the click cannot undo the side effect. Binding is therefore done per window: the root
     * package must be [pkg], the label node must report the same package, and the clicked node is
     * the label itself or its nearest clickable ancestor inside that same package — never a generic
     * ancestor climb and never a coordinate tap.
     */
    fun featureControl(pkg: String, text: String, timeoutMs: Long = 4000): FeatureLookup =
        featureControl(pkg, listOf(text), timeoutMs)

    /**
     * Same lookup, accepting every label that may stand for the feature (its identity plus spoken
     * aliases, see `AppFeatureAliases`). A screen says 「扫一扫」 while the user said 「扫码」: the
     * labels are the same feature, so any of them identifies the control.
     */
    fun featureControl(pkg: String, wantedLabels: List<String>, timeoutMs: Long = 4000): FeatureLookup {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = featureControlOnce(pkg, wantedLabels)
        while (last.control == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(120)
            last = featureControlOnce(pkg, wantedLabels)
        }
        return last
    }

    /**
     * One non-retrying read of the active window.
     *
     * Round 40 (review 6551941593, item 3): the caller uses this for the *action boundary*. The node
     * from an earlier read is never clicked; the control is re-resolved against the window as it is
     * at the moment of the click, so a popup or a page jump in between cannot land the click on a
     * stale node.
     */
    fun featureControlOnce(pkg: String, wantedLabels: List<String>): FeatureLookup {
        val wanted = wantedLabels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return FeatureLookup(null, "not-found")
        val r = root()
        val rootPkg = r?.let { runCatching { it.packageName?.toString() }.getOrNull() }
        if (r == null) return FeatureLookup(null, "no-window")
        // The app was launched, but something else owns the front window right now.
        if (rootPkg != pkg) return FeatureLookup(null, "window-not-target($rootPkg)")

        // Round 39 (review 6551563018): the old version took the *first* BFS node whose label
        // merely contained the name. That clicked a 「扫一扫说明」 card listed above the real entry.
        // Round 40 (review 6551941593): the follow-up fix preferred an *exactly* labelled node and
        // dropped every containing match — which clicked a help card whose child simply read
        // 「扫一扫」 while the real button read 「扫一扫，打开相机」. Exact text is not business
        // identity, so both tiers stay candidates.
        // Round 42 (review 6552469328, item 1): "exactly labelled" stayed a way to *win*, and that is
        // the same mistake one level down — a clickable help card whose own text is 「扫一扫」 beat
        // the real entry labelled 「扫一扫，打开相机」. Nothing in the tree says which of two matched
        // controls is the feature: text, resource id and class carry no business meaning without
        // per-app evidence we do not have. So a click requires the *only* clickable target; two
        // different clickable controls answering to the name is `ambiguous`, zero clicks.
        val scan = collectFeatureCandidates(r, pkg, wanted)
        // Same rule as navigation: a candidate set cut short by the node budget is not
        // "every candidate", so uniqueness cannot be decided from it.
        if (!scan.complete) return FeatureLookup(null, "candidates-truncated")

        val targets = ArrayList<FeatureControl>()
        for (n in scan.exact + scan.loose) {
            val target = clickableWithin(n, r, pkg) ?: continue
            if (targets.any { sameNode(it.node, target) }) continue
            targets.add(controlOf(target, labelOf(n)))
        }
        return when {
            targets.isEmpty() -> FeatureLookup(null, "label-not-clickable")
            targets.size == 1 -> FeatureLookup(targets[0], null, r)
            else -> FeatureLookup(null, "ambiguous(${targets.size})")
        }
    }

    /** The window id [n] was read from, or [INVALID_WINDOW] when the tree does not report one. */
    internal fun windowOf(n: AccessibilityNodeInfo?): Int =
        n?.let { runCatching { it.windowId }.getOrNull() } ?: INVALID_WINDOW

    internal fun viewIdOf(n: AccessibilityNodeInfo): String? =
        runCatching { n.viewIdResourceName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    internal fun classOf(n: AccessibilityNodeInfo): String? =
        runCatching { n.className?.toString() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    internal fun controlOf(node: AccessibilityNodeInfo, label: String): FeatureControl =
        FeatureControl(node, label, viewIdOf(node), classOf(node))

    /**
     * A structural fingerprint of the page [r] was read from: class + resource id of every node, in
     * walk order, with all *text* left out.
     *
     * Text cannot be part of it (a clock, a message list and a counter all change text without the
     * page changing), but the layout skeleton does change when the app swaps to another page of the
     * same package. `null` = the walk hit its budget, so the fingerprint would only describe a
     * prefix of the page and cannot be compared as if it described the page.
     *
     * Limits, stated plainly: this is a *structure* signature. Two pages built from the same
     * skeleton with the same resource ids would collide, and it says nothing about which control on
     * the page is the right one. It is evidence against a page jump, not proof of page identity.
     */
    internal fun pageSignature(r: AccessibilityNodeInfo?): String? {
        if (r == null) return null
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited++ >= PAGE_SIGNATURE_NODES) return null
            val n = queue.removeFirst()
            sb.append(classOf(n) ?: "?").append('#').append(viewIdOf(n) ?: "-").append(';')
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /**
     * Round 42 (review 6552469328, item 2): why the control re-resolved at the action boundary is not
     * provably the same *business target* as the one found by the first scan, or null when it is.
     *
     * The package check alone (already done by [featureControlOnce]) lets a page jump inside the same
     * app through: page B with its own unique 「扫一扫」 satisfies "same package, one candidate" while
     * the click lands on a control the request never named. Node objects are never compared — the
     * identity is rebuilt from signals the tree reports: window id, the target's resource id and
     * class, and the page's structural signature. Anything unprovable is refused, not guessed.
     */
    internal fun clickIdentityMiss(
        first: FeatureControl,
        firstRoot: AccessibilityNodeInfo?,
        fresh: FeatureControl,
        freshRoot: AccessibilityNodeInfo?,
    ): String? {
        // 1) The target itself: resource id is the strongest identity an app exposes; when both
        //    reads report one it must be the same widget.
        val v1 = first.viewId
        val v2 = fresh.viewId
        if (v1 != null && v2 != null && v1 != v2) return "identity-target-changed($v1→$v2)"
        // 2) Role: the class of the clicked node must not change. A button that became a card is a
        //    different control even if it kept the label and the id.
        if (first.className != null && fresh.className != null && first.className != fresh.className) {
            return "identity-target-changed(${first.className}→${fresh.className})"
        }
        // 3) With no resource id on either side, label + role is all that is left; if even the role
        //    is missing, identity is unprovable and nothing is clicked.
        if (v1 == null && v2 == null && first.className == null) return "identity-target-unknown"
        if (first.label.trim() != fresh.label.trim()) return "identity-target-changed(label)"

        // 4) The window must be the same window.
        val w1 = windowOf(firstRoot)
        val w2 = windowOf(freshRoot)
        if (w1 == INVALID_WINDOW || w2 == INVALID_WINDOW) return "identity-window-unknown"
        if (w1 != w2) return "identity-window-changed($w1→$w2)"

        // 5) And it must still be the same page: same package in a new page of the same window is
        //    exactly the case a "unique same-named control" cannot distinguish.
        val s1 = pageSignature(firstRoot) ?: return "identity-structure-unknown"
        val s2 = pageSignature(freshRoot) ?: return "identity-structure-unknown"
        if (s1 != s2) return "identity-page-changed"
        return null
    }

    /**
     * Whether [target] is still part of the tree [r]. `null` = the walk hit its budget, so presence
     * cannot be decided (a partial walk finding nothing is not proof of absence).
     */
    internal fun containsNode(r: AccessibilityNodeInfo?, target: AccessibilityNodeInfo): Boolean? {
        if (r == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited++ >= MAX_NODES) return null
            val n = queue.removeFirst()
            if (sameNode(n, target)) return true
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return false
    }

    /** Feature-label candidates plus whether the scan is provably exhaustive. */
    private class FeatureScan(
        /** Labels that are exactly the asked-for name (text or content-description). */
        val exact: List<AccessibilityNodeInfo>,
        /** Labels that merely contain it — only consulted when no exact label exists. */
        val loose: List<AccessibilityNodeInfo>,
        val complete: Boolean,
    )

    /**
     * Bounded collection of same-package, visible nodes whose label matches [wanted].
     *
     * `complete = false` when the node budget or the candidate cap cut the walk short, because an
     * unfinished walk cannot prove that a second control does not exist further down the tree.
     */
    private fun collectFeatureCandidates(
        root: AccessibilityNodeInfo,
        pkg: String,
        wanted: Set<String>,
    ): FeatureScan {
        val exact = ArrayList<AccessibilityNodeInfo>()
        val loose = ArrayList<AccessibilityNodeInfo>()
        var matched = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var cut = false
        while (queue.isNotEmpty()) {
            if (visited++ >= MAX_NODES) { cut = true; break }
            val n = queue.removeFirst()
            val ownPkg = runCatching { n.packageName?.toString() }.getOrNull()
            val visible = runCatching { n.isVisibleToUser }.getOrDefault(false)
            if (ownPkg == pkg && visible) {
                val textLabel = runCatching { n.text?.toString() }.getOrNull()?.trim()?.lowercase()
                val descLabel = runCatching { n.contentDescription?.toString() }.getOrNull()?.trim()?.lowercase()
                val isExact = (textLabel != null && textLabel in wanted) ||
                    (descLabel != null && descLabel in wanted)
                val isLoose = !isExact && wanted.any { w ->
                    textLabel?.contains(w) == true || descLabel?.contains(w) == true
                }
                if (isExact || isLoose) {
                    matched++
                    if (matched > NAV_MAX_CANDIDATES) { cut = true; break }
                    if (isExact) exact.add(n) else loose.add(n)
                }
            }
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        if (queue.isNotEmpty()) cut = true
        return FeatureScan(exact, loose, !cut)
    }

    /** Both labels of [n] joined and lowercased — the phrase a refusal reports back to the caller. */
    private fun labelOf(n: AccessibilityNodeInfo): String =
        ((runCatching { n.text?.toString() }.getOrNull() ?: "") + " " +
            (runCatching { n.contentDescription?.toString() }.getOrNull() ?: "")).lowercase()

    /**
     * The label itself when clickable, else its nearest clickable ancestor, but never anything
     * outside the app's own window ([root]) or outside [pkg].
     *
     * Round 39: the root check now runs *before* `isClickable`. Previously a clickable window root
     * was returned as the click target when neither the label nor its intermediate parents were
     * clickable — the whole window is not the control the user named, and clicking it is exactly the
     * blind click the class contract forbids (review 6551563018).
     */
    private fun clickableWithin(
        label: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        pkg: String,
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = label
        var hops = 0
        while (cur != null && hops <= NAV_MAX_HOPS) {
            val node = cur
            if (sameNode(node, root)) return null
            if (runCatching { node.isClickable }.getOrDefault(false) &&
                runCatching { node.packageName?.toString() }.getOrNull() == pkg
            ) {
                return node
            }
            cur = runCatching { node.parent }.getOrNull()
            hops++
        }
        return null
    }

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
     * Poll [tabSelected] until it reports `true` or the budget runs out, then return the last verdict
     * (which may still be `false`/`null`). The clock's tab strip is animated: on One UI 9 a single
     * read right after a delivered ACTION_CLICK still saw the old selection, while the page had in
     * fact switched. This helper only *reads* — it never issues another click.
     */
    internal fun awaitTabSelected(
        text: String,
        timeoutMs: Long,
        peers: List<String> = CLOCK_TABS,
    ): Boolean? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Boolean? = tabSelected(text, peers)
        while (last != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(120)
            last = tabSelected(text, peers)
        }
        return last
    }

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
            val unit = clickUnit(labelNode, node)
            if (unit == null) {
                // Device evidence (2026-11-03, One UI 9 / Clock 12.5.60.27): the selected tab is
                // not clickable (clickable=false, selected=true) while the others are. Demanding a
                // click unit from every label rejected the whole strip whenever one tab was
                // selected, so every clock query became `tab=click-failed-label-only` even on the
                // correct page. Unclickable is only acceptable when the strip reports selection;
                // a plain label without selection evidence still refuses (content-page guard).
                if (!selectedBelow(labelNode, node)) return false
                continue
            }
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
     * True when [label] or one of its ancestors below [bar] reports `isSelected`, i.e. the strip
     * itself says this label is the current tab. Used only to allow an unclickable *selected* tab
     * (One UI 9 device evidence); a label without selection evidence never qualifies.
     */
    private fun selectedBelow(label: AccessibilityNodeInfo, bar: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = label
        var hops = 0
        while (cur != null && hops++ <= NAV_MAX_HOPS + 2) {
            val node = cur
            if (sameNode(node, bar)) return false
            if (runCatching { node.isSelected }.getOrDefault(false)) return true
            cur = runCatching { node.parent }.getOrNull()
        }
        return false
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
        if (r.packageName?.toString() != CLOCK_PKG) {
            Log.i(TAG, "navTab($tab): refused, rootPkg=${r.packageName}")
            return@runCatching false
        }
        val nav = navControl(r, tab, CLOCK_TABS)
        if (nav == null) {
            Log.i(TAG, "navTab($tab): no verified tab control (nav=null)")
            return@runCatching null
        }
        if (nav.control.packageName?.toString() != CLOCK_PKG) {
            Log.i(TAG, "navTab($tab): control pkg=${nav.control.packageName}")
            return@runCatching false
        }
        if (nav.container.packageName?.toString() != CLOCK_PKG) {
            Log.i(TAG, "navTab($tab): container pkg=${nav.container.packageName}")
            return@runCatching false
        }
        val clicked = clickTabControl(nav)
        Log.i(TAG, "navTab($tab): clicked=$clicked controlClickable=${nav.control.isClickable}")
        clicked
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
