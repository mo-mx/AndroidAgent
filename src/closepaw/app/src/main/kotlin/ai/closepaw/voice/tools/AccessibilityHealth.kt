package ai.closepaw.voice.tools

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import ai.closepaw.app.AgentService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observability for the AccessibilityService, because "无障碍服务没有运行" was being reported as a
 * single opaque failure when it actually has four distinct causes:
 *
 *   DISABLED            – the user turned it off in Settings (needs explicit re-enabling)
 *   ENABLED_NOT_BOUND   – Settings says enabled, but the system has not (re)bound the service yet;
 *                         this is the normal state for a few seconds after a process restart
 *   CONNECTED           – onServiceConnected() ran; tools can inspect and act on the UI
 *   LOST                – it was connected and dropped (process killed, APK replaced, crash)
 *
 * The old code only knew "instance == null", so it could not wait for a rebind and could not tell
 * the user what to do. Every transition is timestamped and written to files/a11y_status.txt so
 * on-device test runs can be inspected after the fact.
 */
object AccessibilityHealth {
    private const val TAG = "A11yHealth"
    private const val SERVICE_ID = "ai.closepaw/.app.AgentService"

    enum class State { DISABLED, ENABLED_NOT_BOUND, CONNECTED, LOST }

    data class Status(
        val state: State,
        val enabledInSettings: Boolean,
        val listedByManager: Boolean,
        val instanceAlive: Boolean,
        val connectCount: Int,
        val unbindCount: Int,
        val lastConnectedAgoMs: Long?,
        val lastLostAgoMs: Long?,
        val detail: String,
    )

    @Volatile private var connectCount = 0
    @Volatile private var unbindCount = 0
    @Volatile private var lastConnectedAt = 0L
    @Volatile private var lastLostAt = 0L
    @Volatile private var lastLostReason = ""
    @Volatile var statusFile: File? = null

    fun onConnected(svc: android.accessibilityservice.AccessibilityService) {
        connectCount++
        lastConnectedAt = System.currentTimeMillis()
        Log.i(TAG, "CONNECTED (#$connectCount) pkg=${svc.packageName}")
        write(svc, "CONNECTED #$connectCount")
    }

    fun onUnbound(reason: String) {
        unbindCount++
        lastLostAt = System.currentTimeMillis()
        lastLostReason = reason
        Log.w(TAG, "LOST (#$unbindCount) reason=$reason")
        statusFile?.let { f ->
            runCatching {
                f.appendText("${now()} LOST #$unbindCount reason=$reason\n")
            }
        }
    }

    fun snapshot(ctx: Context): Status {
        val ctxApp = ctx.applicationContext
        val setting = runCatching {
            Settings.Secure.getString(ctxApp.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        }.getOrDefault("")
        val enabled = setting.split(':').any { it.equals(SERVICE_ID, true) }
        val am = ctxApp.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val listed = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == "ai.closepaw" }
        }.getOrDefault(false) == true
        val alive = AgentService.instance != null

        val state = when {
            alive -> State.CONNECTED
            !enabled && !listed -> State.DISABLED
            else -> if (lastLostAt > lastConnectedAt) State.LOST else State.ENABLED_NOT_BOUND
        }
        val detail = buildString {
            append("setting=$enabled listed=$listed instance=$alive")
            if (lastLostAt > 0) append(" lastLost=${lastLostReason}")
        }
        return Status(
            state = state,
            enabledInSettings = enabled,
            listedByManager = listed,
            instanceAlive = alive,
            connectCount = connectCount,
            unbindCount = unbindCount,
            lastConnectedAgoMs = if (lastConnectedAt > 0) System.currentTimeMillis() - lastConnectedAt else null,
            lastLostAgoMs = if (lastLostAt > 0) System.currentTimeMillis() - lastLostAt else null,
            detail = detail,
        )
    }

    /**
     * Wait for the service to be usable. Returns the final status; [timeoutMs] should cover the
     * platform's rebind delay (measured: 1–6 s after a process restart, longer while dozing).
     */
    fun awaitConnected(ctx: Context, timeoutMs: Long, onTransition: (Status) -> Unit = {}): Status {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Status = snapshot(ctx)
        onTransition(last)
        if (last.state == State.CONNECTED) return last
        if (last.state == State.DISABLED) return last   // waiting cannot help
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            val s = snapshot(ctx)
            if (s.state != last.state) {
                Log.i(TAG, "state ${last.state} → ${s.state} (${s.detail})")
                onTransition(s)
                last = s
            }
            if (s.state == State.CONNECTED) return s
            last = s
        }
        onTransition(last)
        return last
    }

    /** Spoken guidance that tells the user what to actually do, per real state. */
    fun speechFor(status: Status): String = when (status.state) {
        State.CONNECTED -> "无障碍已经连上了"
        State.DISABLED -> "无障碍服务被关掉了，请在设置的无障碍里重新打开 ClosePaw"
        State.ENABLED_NOT_BOUND -> "无障碍已经授权，但系统还没把它连上，等几秒再试一次"
        State.LOST -> "无障碍刚才断开了，正在等系统重新连上"
    }

    fun openAccessibilitySettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    private fun write(ctx: Context, line: String) {
        val f = statusFile ?: runCatching {
            File(ctx.filesDir, "a11y_status.txt").also { statusFile = it }
        }.getOrNull() ?: return
        runCatching { f.appendText("${now()} $line\n") }
    }

    private fun now(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Convenience for diagnostics screens/logs. */
    fun describe(ctx: Context): String {
        val s = snapshot(ctx)
        return "${s.state} (${s.detail}) connect#=${s.connectCount} lost#=${s.unbindCount}"
    }
}
