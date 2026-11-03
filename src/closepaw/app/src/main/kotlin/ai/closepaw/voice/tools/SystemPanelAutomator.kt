package ai.closepaw.voice.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ai.closepaw.app.AgentService

/**
 * Drives system settings panels through the AccessibilityService when the platform refuses a
 * direct API call (Wi-Fi/Bluetooth toggles are system-only since Android 10/13).
 *
 * Rules this implementation follows, because the honest-failure requirement is the point:
 *  - the toggle is located from the *live* node tree (class/text/contentDescription, checkable
 *    state), never from hard-coded coordinates;
 *  - ACTION_CLICK first, then the nearest clickable ancestor, and only then a synthesised
 *    gesture at the node's own reported bounds;
 *  - success is only reported after the real system state (WifiManager/BluetoothAdapter) is
 *    re-read and matches the requested state;
 *  - the panel is dismissed afterwards so the assistant's own UI is what the user sees again.
 */
object SystemPanelAutomator {
    private const val TAG = "SysPanel"

    enum class Target(
        val label: String,
        val keywords: List<String>,
        val panelAction: String,
        val settingsAction: String,
    ) {
        WIFI("Wi-Fi", listOf("wifi", "wi-fi", "无线局域网", "wlan"), Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS),
        BLUETOOTH("蓝牙", listOf("蓝牙", "bluetooth"), Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_BLUETOOTH_SETTINGS),
    }

    fun service(): AccessibilityService? = AgentService.instance

    fun available(): Boolean = AgentService.instance != null



    /** Real device state for the target, or null when unreadable. */
    fun readState(ctx: Context, target: Target): Boolean? = when (target) {
        Target.WIFI -> WifiControl.isEnabled(ctx)
        Target.BLUETOOTH -> runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
        }.getOrNull()
    }

    /**
     * Turn [target] [on] by opening the system panel and clicking the real switch.
     * Returns only when the post-condition was verified (or with an explicit failure reason).
     */
    fun setEnabled(ctx: Context, target: Target, on: Boolean): ToolResult {
        // Distinguish "not authorised" from "authorised but not bound yet", and wait for a
        // rebind instead of failing on the first null instance.
        val health = AccessibilityHealth.awaitConnected(ctx, 12_000) { st ->
            Log.i(TAG, "a11y while waiting: ${st.state} (${st.detail})")
        }
        val svc = AgentService.instance
        if (svc == null) {
            Log.w(TAG, "a11y unavailable: ${health.state} ${health.detail}")
            return ToolResult(
                when (health.state) {
                    AccessibilityHealth.State.DISABLED -> ToolStatus.PERMISSION_DENIED
                    AccessibilityHealth.State.LOST -> ToolStatus.TIMEOUT
                    else -> ToolStatus.DEVICE_UNAVAILABLE
                },
                "syspanel",
                AccessibilityHealth.speechFor(health),
                "state=${health.state} ${health.detail} connect#=${health.connectCount} lost#=${health.unbindCount}",
                verified = false,
            )
        }

        // A settings panel launched while the keyguard is up is rendered *behind* the lock screen:
        // the window we would inspect is the keyguard itself. Measured on SM-F731U1 (Android 17):
        // searching that tree finds no switch, and typing a gesture there is meaningless. Report
        // the limitation instead of pretending to click.
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            return ToolResult.restricted(
                "syspanel",
                "手机现在锁着，系统不允许在锁屏上弹出设置面板；解锁后再说一次我就能替你点",
                "keyguard locked → panel cannot be shown above keyguard"
            )
        }

        val before = readState(ctx, target)
        if (before == on) {
            return ToolResult.ok(
                "syspanel", "已经${if (on) "打开" else "关闭"}了", verified = true, detail = "already=$before"
            )
        }

        // 1) open the official panel
        val opened = openPanel(ctx, target)
        Log.i(TAG, "panel opened=$opened for $target (before=$before)")

        // 2) wait for the panel window and locate the control
        val cand = waitForControl(svc, target, 5000)
        if (cand == null) {
            Log.w(TAG, "no control found for $target; dumping tree")
            dumpTree(svc, 6)
            closePanel(svc)
            return ToolResult.error(
                "syspanel", "没能在设置面板里找到${target.label}开关",
                "toggle node not found within 4s (panel opened=$opened)"
            )
        }
        val node = cand.node
        Log.i(TAG, "control: switch=${cand.isSwitch} cls=${node.className} text=${node.text} desc=${node.contentDescription} checked=${node.isChecked} clickable=${node.isClickable} bounds=${node.bounds()}")

        // A row (no checked state) can't tell us its state — the platform read above is the truth.
        if (cand.isSwitch && node.isChecked == on) {
            Log.i(TAG, "toggle already in desired state per node")
            closePanel(svc)
            return ToolResult.ok("syspanel", "${target.label}已经是${if (on) "开" else "关"}着了", verified = true)
        }

        // 3) click: real switch inside the row → node/ancestor action → gesture on bounds
        val innerSwitch = if (!cand.isSwitch) {
            findFirst(node) { n ->
                val cls = n.className?.toString()?.lowercase() ?: ""
                cls.contains("switch") || cls.contains("toggle") || n.isCheckable
            }?.also {
                Log.i(TAG, "switch inside row: cls=${it.className} checked=${it.isChecked} clickable=${it.isClickable} bounds=${it.bounds()}")
            }
        } else null

        if (!cand.isSwitch) dumpSubtree(node, 3, "row")

        val clicked = when {
            innerSwitch != null && innerSwitch.isChecked == on -> true
            innerSwitch != null -> clickNode(svc, innerSwitch) || tapRightOf(svc, innerSwitch.bounds() ?: node.bounds())
            else -> clickNode(svc, node) || tapRightOf(svc, node.bounds())
        }
        Log.i(TAG, "click delivered=$clicked")

        // 4) verify through the platform, not through the UI
        val after = pollState(ctx, target, on, 8000)
        closePanel(svc)

        return when {
            after == on -> ToolResult.ok(
                "syspanel",
                "${target.label}已${if (on) "打开" else "关闭"}",
                verified = true,
                detail = "clicked=$clicked state=$before→$after"
            )
            after == before -> ToolResult.error(
                "syspanel",
                "点了开关，但${target.label}状态没变",
                "clicked=$clicked state still $after"
            )
            else -> ToolResult.error(
                "syspanel",
                "${target.label}状态没有按预期变化",
                "clicked=$clicked expected=$on actual=$after"
            )
        }
    }

    private fun openPanel(ctx: Context, target: Target): Boolean {
        val intents = buildList {
            add(Intent(target.panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            add(Intent(target.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        for (i in intents) {
            val ok = runCatching {
                ctx.startActivity(i)
                true
            }.getOrElse {
                Log.w(TAG, "startActivity failed for ${i.action}", it)
                false
            }
            if (ok) return true
        }
        return false
    }

    data class Candidate(val node: AccessibilityNodeInfo, val isSwitch: Boolean, val label: AccessibilityNodeInfo? = null)

    /**
     * Poll the active window until the target control appears.
     *
     * Two layouts exist in practice:
     *  - stock/quick-panel style: an android.widget.Switch (checkable, state readable);
     *  - Samsung panel style: a label ("Wi-Fi") plus a clickable row underneath — the row is the
     *    toggle, and it carries no checked state, so the platform API remains the source of truth.
     */
    private fun waitForControl(svc: AccessibilityService, target: Target, timeoutMs: Long): Candidate? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            if (root != null) {
                findSwitch(root, target)?.let { return Candidate(it, true) }
                findRowByLabel(root, target)?.let { (row, label) -> return Candidate(row, false, label) }
            }
            SystemClock.sleep(150)
        }
        return null
    }

    private fun findSwitch(root: AccessibilityNodeInfo, target: Target): AccessibilityNodeInfo? =
        findFirst(root) { n ->
            val cls = n.className?.toString()?.lowercase() ?: ""
            val label = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
            val looksSwitch = cls.contains("switch") || cls.contains("toggle") || n.isCheckable
            looksSwitch && target.keywords.any { label.contains(it) }
        }

    /**
     * Find the label node, then return (the row that owns it, the label).
     *
     * Samsung's panel puts the label and the switch in the same row but the label is not the
     * toggle: tapping the text area opens details instead of flipping the switch. So the caller
     * looks for a real switch inside this row before falling back to the row itself.
     */
    private fun findRowByLabel(
        root: AccessibilityNodeInfo,
        target: Target,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        val label = findFirst(root) { n ->
            val text = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
            target.keywords.any { k -> text.trim() == k || text.trim().startsWith(k) }
        } ?: return null
        var cur: AccessibilityNodeInfo? = label
        var hops = 0
        while (cur != null && hops++ < 4) {
            if (cur.isClickable) return cur to label
            cur = cur.parent
        }
        return (label.parent ?: label) to label
    }

    /** Breadth-first search with a node budget, so a pathological tree cannot hang the worker. */
    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
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

    private fun clickNode(svc: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        // 1) the node itself
        if (node.isClickable) {
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (ok) return true
        }
        // 2) nearest clickable ancestor
        var p = node.parent
        var hops = 0
        while (p != null && hops++ < 4) {
            if (p.isClickable) {
                val ok = runCatching { p.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (ok) return true
                break
            }
            p = p.parent
        }
        // 3) gesture at the node's own bounds (still state-derived, not hard-coded)
        val b = node.bounds()
        if (b == null || b.width() <= 0) return false
        return tap(svc, b.centerX().toFloat(), b.centerY().toFloat())
    }

    private fun AccessibilityNodeInfo.bounds(): Rect? =
        runCatching { Rect().also { getBoundsInScreen(it) } }.getOrNull()

    /**
     * Tap the trailing edge of a control's own bounds. Coordinates come from the live node, never
     * from constants, and the offset is chosen because switches sit at the row's right edge.
     */
    private fun tapRightOf(svc: AccessibilityService, b: Rect?): Boolean {
        if (b == null || b.width() <= 0) return false
        val x = b.left + b.width() * 0.90f
        val y = b.centerY().toFloat()
        Log.i(TAG, "tapRightOf bounds=$b → $x,$y")
        return tap(svc, x, y)
    }

    private fun dumpSubtree(n: AccessibilityNodeInfo, maxDepth: Int, tag: String) {
        fun walk(x: AccessibilityNodeInfo?, depth: Int) {
            if (x == null || depth > maxDepth) return
            Log.i(TAG, "[$tag] " + "  ".repeat(depth) + "${x.className} text='${x.text}' desc='${x.contentDescription}' " +
                "checkable=${x.isCheckable} checked=${x.isChecked} clickable=${x.isClickable} bounds=${x.bounds()}")
            for (i in 0 until x.childCount) runCatching { walk(x.getChild(i), depth + 1) }
        }
        walk(n, 0)
    }

    private fun tap(svc: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var done = false
        val ok = runCatching {
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    done = true; latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    done = false; latch.countDown()
                }
            }, null)
        }.getOrDefault(false)
        if (!ok) return false
        latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.i(TAG, "gesture tap at $x,$y completed=$done")
        return done
    }

    private fun pollState(ctx: Context, target: Target, want: Boolean, timeoutMs: Long): Boolean? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: Boolean? = null
        while (SystemClock.uptimeMillis() < deadline) {
            last = readState(ctx, target)
            if (last == want) return last
            SystemClock.sleep(200)
        }
        return last
    }

    private fun closePanel(svc: AccessibilityService) {
        val pkg = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (pkg == null || pkg == "ai.closepaw") return
        if (pkg.contains("systemui") || pkg.contains("settings")) {
            runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            SystemClock.sleep(250)
            Log.i(TAG, "panel dismissed (was $pkg)")
        }
    }

    /** Debug helper: print a shallow tree so a missing switch can be diagnosed from logs. */
    private fun dumpTree(svc: AccessibilityService, maxDepth: Int) {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > maxDepth) return
            val b = n.bounds()
            Log.i(
                TAG,
                "  ".repeat(depth) + "${n.className} text='${n.text}' desc='${n.contentDescription}' " +
                    "checkable=${n.isCheckable} checked=${n.isChecked} clickable=${n.isClickable} bounds=$b"
            )
            for (i in 0 until n.childCount) runCatching { walk(n.getChild(i), depth + 1) }
        }
        Log.i(TAG, "tree root pkg=${root.packageName}")
        walk(root, 0)
    }
}
