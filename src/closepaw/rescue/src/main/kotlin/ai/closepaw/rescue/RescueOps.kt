package ai.closepaw.rescue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Whitelisted remote operations. Everything runs with ordinary app permissions — no root, no
 * Device Owner, no accessibility tricks — and every read is wrapped so a Direct Boot window
 * (credential-encrypted storage still locked) degrades to `null`/`unavailable` instead of
 * throwing. Anything Android forbids for a normal app is reported honestly as unsupported.
 */
class RescueOps(private val ctx: Context, private val client: RescueClient) {

    private val tag = "RescueOps"
    private val agentPkg = "ai.closepaw"

    @Volatile
    var lastError: String? = null

    fun status(): JSONObject {
        val out = JSONObject()
        out.put("ok", true)
        out.put("device_id", client.deviceId)
        out.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        out.put("sdk", Build.VERSION.SDK_INT)
        out.put("rescue_version", BuildConfig.VERSION_NAME)
        out.put("ts", System.currentTimeMillis())

        // ── Direct Boot state ──
        val unlocked = runCatching { RescueClient.isUserUnlocked(ctx) }.getOrDefault(true)
        out.put("user_unlocked", unlocked)
        out.put("direct_boot", !unlocked)

        // ── power (available pre-unlock) ──
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            out.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            out.put("charging", bm.isCharging)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            out.put("screen_on", pm.isInteractive)
            out.put("power_save", pm.isPowerSaveMode)
        }.onFailure { out.put("power_error", it.javaClass.simpleName) }
        out.put("uptime_s", android.os.SystemClock.elapsedRealtime() / 1000)

        // ── network (NA network info works in Direct Boot) ──
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val transport = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            out.put("network", transport)
            out.put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
        }.onFailure { out.put("network", "unknown") }

        // ── main agent visibility ──
        val agent = JSONObject()
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(agentPkg, 0)
            agent.put("installed", true)
            agent.put("version", pi.versionName ?: "?")
            agent.put(
                "version_code",
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            )
        }.onFailure { agent.put("installed", false) }
        // Settings.Secure is credential-encrypted: unreadable before unlock.
        agent.put("accessibility_enabled", if (unlocked) isAgentAccessibilityEnabled() else null)
        out.put("agent", agent)

        // ── capability matrix, as actually implemented ──
        val caps = JSONObject()
        caps.put("launch_installed_app", unlocked)      // background activity start needs unlocked CE app
        caps.put("speak", unlocked)                     // TTS engines live in CE storage
        caps.put("read_own_status", true)
        caps.put("read_network", true)
        caps.put("silent_install", false)               // needs Device Owner or root
        caps.put("user_confirmed_install", true)
        caps.put("toggle_accessibility", false)         // needs WRITE_SECURE_SETTINGS
        caps.put("read_other_app_logs", false)
        out.put("capabilities", caps)

        lastError?.let { out.put("last_error", it) }
        out.put("config", RescueConfig.describe(ctx))
        return out
    }

    /** Richer diagnostic bundle for "prove you are really alive" checks. */
    fun diagnostics(): JSONObject {
        val out = status()
        runCatching {
            val dp = RescueConfig.dpDir(ctx)
            out.put("dp_writable", dp.canWrite())
            out.put("dp_files", dp.list()?.joinToString(",") ?: "")
            val t0 = System.currentTimeMillis()
            val r = client.poll(JSONObject().put("device", client.deviceId).put("probe", true))
            out.put("relay_roundtrip_ms", System.currentTimeMillis() - t0)
            out.put("relay_http", r.code)
        }.onFailure { lastError = it.toString() }
        out.put("ok", true)
        return out
    }

    private fun isAgentAccessibilityEnabled(): Boolean = runCatching {
        val v = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
            ?: return false
        v.contains(agentPkg)
    }.getOrDefault(false)

    /** Launch the main agent. Requires the user to be unlocked (background start + CE app). */
    fun startAgent(): JSONObject {
        if (!runCatching { RescueClient.isUserUnlocked(ctx) }.getOrDefault(true)) {
            return unsupported("start_agent", "设备尚未首次解锁：主 Agent 的数据在凭据加密存储里，系统不允许此时启动")
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(agentPkg)
            ?: return err("主 Agent 未安装或没有启动入口")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            JSONObject().put("ok", true).put("launched", agentPkg)
        } catch (t: Throwable) {
            Log.e(tag, "startAgent failed", t)
            unsupported("start_agent", "后台启动被系统拦截：${t.javaClass.simpleName}")
        }
    }

    fun speak(text: String): JSONObject {
        if (!runCatching { RescueClient.isUserUnlocked(ctx) }.getOrDefault(true)) {
            return unsupported("speak", "设备尚未首次解锁：TTS 引擎数据在凭据加密存储里，无法播报")
        }
        return runCatching {
            RescueSpeaker.speak(ctx, text)
            JSONObject().put("ok", true).put("spoken", text.take(120))
        }.getOrElse { err("TTS 失败：${it.javaClass.simpleName}") }
    }

    fun batteryOnly(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("ok", true)
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
    }

    fun unsupported(op: String, why: String): JSONObject =
        JSONObject().put("ok", false).put("op", op).put("unsupported", true).put("reason", why)

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
