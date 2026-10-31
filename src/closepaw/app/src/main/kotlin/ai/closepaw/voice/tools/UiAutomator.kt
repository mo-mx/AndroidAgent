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
    fun tabSelected(text: String): Boolean? = selectedState(root(), text)

    internal fun selectedState(r: AccessibilityNodeInfo?, text: String): Boolean? {
        if (r == null) return null
        val target = text.trim().lowercase()
        if (target.isEmpty()) return null
        var match: AccessibilityNodeInfo? = null
        var anySelected = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_NODES) {
            val n = queue.removeFirst()
            if (runCatching { n.isSelected }.getOrDefault(false)) anySelected = true
            if (match == null) {
                val labels = listOfNotNull(runCatching { n.text?.toString() }.getOrNull(),
                    runCatching { n.contentDescription?.toString() }.getOrNull())
                if (labels.any { it.trim().lowercase().contains(target) }) match = n
            }
            for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        val node = match ?: return null
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops++ < 4) {
            if (runCatching { cur!!.isSelected }.getOrDefault(false)) return true
            cur = runCatching { cur!!.parent }.getOrNull()
        }
        // No node in the tree advertises selection at all → the platform does not expose it here.
        if (!anySelected) return null
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
    )

    const val MAX_NODES = 600

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
        fun take(s: CharSequence?) {
            val t = s?.toString()
            if (t.isNullOrBlank()) return
            if (out.size < limit) out.add(t) else dropped = true
        }
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            take(n.text)
            take(n.contentDescription)
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        val pkg = runCatching { r.packageName?.toString() }.getOrNull()
        return ScreenRead(out, visited, queue.isNotEmpty(), dropped, pkg, serviceAvailable)
    }
}
