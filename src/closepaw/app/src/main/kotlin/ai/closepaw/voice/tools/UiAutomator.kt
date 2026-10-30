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
    fun screenTexts(limit: Int = 60): List<String> {
        val out = ArrayList<String>()
        val r = root() ?: return out
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 600 && out.size < limit) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        return out
    }
}
