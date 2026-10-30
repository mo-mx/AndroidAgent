package ai.closepaw.voice.tools

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Wi-Fi control, told honestly.
 *
 * Since Android 10 (API 29) `WifiManager.setWifiEnabled()` is deprecated and returns false for
 * ordinary apps — the platform only allows it for system apps, Device Owner/profile owners and
 * holders of NETWORK_SETTINGS. Retrying the same API cannot succeed, so this intentionally
 * makes **one** attempt and then reports SYSTEM_RESTRICTED with a settings-panel fallback the
 * user can tap. Saying "打开失败" here would be wrong: nothing failed, it is not permitted.
 *
 * `isWifiEnabled` (a read) is still allowed, so the tool can verify the post-condition and say
 * "Wi-Fi 已经是开着的" instead of doing nothing silently.
 */
object WifiControl {
    private const val TAG = "WifiControl"

    private fun manager(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isEnabled(ctx: Context): Boolean? = runCatching { manager(ctx)?.isWifiEnabled }.getOrNull()

    fun enable(ctx: Context): ToolResult {
        val wm = manager(ctx) ?: return ToolResult.unavailable("wifi", "这台设备没有 Wi-Fi 模块")
        val before = runCatching { wm.isWifiEnabled }.getOrNull()
        if (before == true) {
            return ToolResult.ok("wifi", "Wi-Fi 已经是开着的", verified = true, detail = "already on")
        }

        // Single attempt only — repeating a permission-denied API just wastes the user's time.
        val attempted = runCatching { wm.setWifiEnabled(true) }.getOrElse {
            Log.w(TAG, "setWifiEnabled threw", it); false
        }
        val after = runCatching { wm.isWifiEnabled }.getOrNull()
        if (attempted && after == true) {
            return ToolResult.ok("wifi", "Wi-Fi 已打开", verified = true, detail = "setWifiEnabled ok")
        }

        val opened = openWifiPanel(ctx)
        val why = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !attempted)
            "Android 10+ 禁止普通应用调用 setWifiEnabled（需系统应用/设备所有者/NETWORK_SETTINGS）"
        else
            "setWifiEnabled 返回 $attempted，实际状态 $after"

        return ToolResult.restricted(
            "wifi",
            if (opened) "系统不允许我直接开 Wi-Fi，已经帮你打开 Wi-Fi 设置面板，点一下开关就行"
            else "系统不允许普通应用直接开 Wi-Fi，请在设置里手动打开",
            "attempted=$attempted before=$before after=$after panel=$opened; $why"
        )
    }

    /** Opens the Wi-Fi quick panel (Android 10+) or the Wi-Fi settings page as a fallback. */
    fun openWifiPanel(ctx: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        for (i in candidates) {
            val ok = runCatching {
                ctx.startActivity(i)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun disable(ctx: Context): ToolResult {
        val wm = manager(ctx) ?: return ToolResult.unavailable("wifi", "这台设备没有 Wi-Fi 模块")
        val before = runCatching { wm.isWifiEnabled }.getOrNull()
        if (before == false) return ToolResult.ok("wifi", "Wi-Fi 已经是关着的", verified = true)
        val attempted = runCatching { wm.setWifiEnabled(false) }.getOrElse {
            Log.w(TAG, "setWifiEnabled(false) threw", it); false
        }
        val after = runCatching { wm.isWifiEnabled }.getOrNull()
        if (after == false) return ToolResult.ok("wifi", "Wi-Fi 已关闭", verified = true)
        val opened = openWifiPanel(ctx)
        return ToolResult.restricted(
            "wifi",
            if (opened) "系统不允许我直接关 Wi-Fi，已经打开 Wi-Fi 设置面板，点一下开关就行"
            else "系统不允许普通应用关闭 Wi-Fi，请在设置里手动关闭",
            "attempted=$attempted before=$before after=$after panel=$opened"
        )
    }
}
