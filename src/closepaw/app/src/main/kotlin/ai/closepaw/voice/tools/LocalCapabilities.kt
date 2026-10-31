package ai.closepaw.voice.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Android capability layer — the part of the assistant that answers from the device
 * itself instead of from a model.
 *
 * Contract for every capability:
 *  - returns a [ToolResult] whose `speak` is a natural sentence and whose `status` says whether
 *    the query/action really happened;
 *  - queries read the platform API (never guess, never pattern-match a canned answer);
 *  - actions verify the post-condition, and report SYSTEM_RESTRICTED / PERMISSION_DENIED with a
 *    concrete next step when Android does not allow it.
 *
 * Key=-value registry so the planner can route by intent name without a giant `when`.
 */
object LocalCapabilities {
    private const val TAG = "LocalCaps"

    // ── battery ──
    fun battery(ctx: Context): ToolResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ToolResult.unavailable("battery", "读不到电池信息")
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = runCatching { bm.isCharging }.getOrDefault(false)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saver = pm?.isPowerSaveMode == true
        val screenOn = pm?.isInteractive ?: true
        val parts = buildList {
            add("还剩百分之$pct")
            if (charging) add("正在充电")
            if (saver) add("省电模式开着")
            if (!screenOn) add("屏幕是熄的")
        }
        return ToolResult.ok("battery", parts.joinToString("，"), verified = true, detail = "pct=$pct charging=$charging saver=$saver")
    }

    // ── time ──
    fun time(): ToolResult {
        val now = Date()
        val t = SimpleDateFormat("HH 点 mm 分", Locale.CHINA).format(now)
        val d = SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA).format(now)
        return ToolResult.ok("time", "现在是 $t，$d", verified = true, detail = now.toString())
    }

    // ── torch ──
    fun torchState(ctx: Context): ToolResult {
        val s = TorchControl.knownState(ctx)
            ?: return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "torch_state",
                "还不清楚手电筒的状态", "no cached torch callback yet", null
            )
        return ToolResult.ok(
            "torch_state", if (s) "手电筒现在是开着的" else "手电筒现在是关着的",
            verified = true, detail = "torch=$s"
        )
    }

    // ── wifi ──
    fun wifiState(ctx: Context): ToolResult {
        val enabled = WifiControl.isEnabled(ctx)
            ?: return ToolResult.denied("wifi_state", "读不到 Wi-Fi 状态，可能缺少权限", "isWifiEnabled threw")
        if (enabled != true) return ToolResult.ok("wifi_state", "Wi-Fi 是关着的", verified = true, detail = "enabled=false")
        val net = activeNetwork(ctx)
        val onWifi = net?.first == Transport.WIFI
        val online = net?.second == true
        val speech = when {
            onWifi && online -> "Wi-Fi 开着，而且是连上网络的"
            onWifi -> "Wi-Fi 开着，但没有真正连上互联网"
            else -> "Wi-Fi 开着，但当前用的是移动数据"
        }
        return ToolResult.ok("wifi_state", speech, verified = true, detail = "wifi=$enabled onWifi=$onWifi online=$online")
    }

    // ── bluetooth ──
    private fun btAdapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun btState(ctx: Context): ToolResult {
        val a = btAdapter(ctx) ?: return ToolResult.unavailable("bt_state", "这台设备没有蓝牙")
        val enabled = try {
            a.isEnabled
        } catch (e: SecurityException) {
            return ToolResult.denied(
                "bt_state", "查询蓝牙需要蓝牙权限，还没授权",
                "BLUETOOTH_CONNECT missing (API ${Build.VERSION.SDK_INT})"
            )
        }
        if (!enabled) return ToolResult.ok("bt_state", "蓝牙是关着的", verified = true, detail = "enabled=false")
        val bonded = try {
            a.bondedDevices?.size ?: 0
        } catch (e: SecurityException) {
            0
        }
        return ToolResult.ok(
            "bt_state", "蓝牙是开着的，配对了 $bonded 个设备", verified = true, detail = "enabled=true bonded=$bonded"
        )
    }

    fun btSet(ctx: Context, on: Boolean): ToolResult {
        val a = btAdapter(ctx) ?: return ToolResult.unavailable("bt", "这台设备没有蓝牙")
        val before = runCatching { a.isEnabled }.getOrNull()
        if (before == on) {
            return ToolResult.ok("bt", if (on) "蓝牙已经是开着的" else "蓝牙已经是关着的", verified = true)
        }
        // Android 13+ blocks enable()/disable() for non-system apps; one attempt, then be honest.
        val attempted = runCatching {
            if (on) a.enable() else a.disable()
        }.getOrElse { Log.w(TAG, "bt toggle threw", it); false }
        val after = runCatching { a.isEnabled }.getOrNull()
        if (after == on) return ToolResult.ok("bt", if (on) "蓝牙已打开" else "蓝牙已关闭", verified = true)
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return ToolResult.restricted(
            "bt",
            "Android 不允许普通应用直接切换蓝牙，我已经打开蓝牙设置，点一下开关就行",
            "attempted=$attempted before=$before after=$after"
        )
    }

    // ── network ──
    private enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

    /** (transport, validated internet) of the active network. */
    private fun activeNetwork(ctx: Context): Pair<Transport, Boolean>? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val n = cm.activeNetwork ?: return Transport.NONE to false
        val caps = cm.getNetworkCapabilities(n) ?: return Transport.NONE to false
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return transport to validated
    }

    fun networkState(ctx: Context): ToolResult {
        val net = activeNetwork(ctx)
            ?: return ToolResult.denied("network_state", "读不到网络状态", "ConnectivityManager null")
        val (transport, online) = net
        val tName = when (transport) {
            Transport.WIFI -> "Wi-Fi"
            Transport.CELLULAR -> "移动数据"
            Transport.ETHERNET -> "有线网络"
            Transport.OTHER -> "其他网络"
            Transport.NONE -> "没有网络"
        }
        val speech = when {
            transport == Transport.NONE -> "现在没有可用的网络"
            online -> "当前用$tName，可以访问互联网"
            else -> "当前用$tName，但连不上互联网"
        }
        return ToolResult.ok("network_state", speech, verified = true, detail = "transport=$transport validated=$online")
    }

    /** Quick reachability test used by the router before touching the cloud model. */
    fun onlineNow(ctx: Context): Boolean = activeNetwork(ctx)?.second == true

    // ── volume ──
    private fun audio(ctx: Context) =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    fun volumeState(ctx: Context): ToolResult {
        val am = audio(ctx) ?: return ToolResult.unavailable("volume", "读不到音量")
        val media = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val mediaMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val ring = am.getStreamVolume(android.media.AudioManager.STREAM_RING)
        val ringMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
        val mediaPct = (media * 100 / mediaMax.coerceAtLeast(1))
        val ringPct = (ring * 100 / ringMax.coerceAtLeast(1))
        val ringer = am.ringerMode
        val silent = ringer == android.media.AudioManager.RINGER_MODE_SILENT
        val vibrate = ringer == android.media.AudioManager.RINGER_MODE_VIBRATE
        val speech = buildString {
            append("媒体音量百分之$mediaPct，铃声音量百分之$ringPct")
            if (silent) append("，现在是无铃声模式")
            else if (vibrate) append("，现在是振动模式")
        }
        return ToolResult.ok("volume", speech, verified = true, detail = "media=$media/$mediaMax ring=$ring/$ringMax mode=$ringer")
    }

    fun volumeSet(ctx: Context, percent: Int): ToolResult {
        val am = audio(ctx) ?: return ToolResult.unavailable("volume", "读不到音量")
        val p = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val target = (max * p / 100).coerceIn(0, max)
        val ok = runCatching {
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            true
        }.getOrDefault(false)
        // the mixer needs a moment before getStreamVolume reflects the new value
        Thread.sleep(150)
        val now = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val nowPct = now * 100 / max.coerceAtLeast(1)
        return if (ok && kotlin.math.abs(now - target) <= 1) {
            ToolResult.ok("volume", "媒体音量已调到百分之$nowPct", verified = true, detail = "set=$target actual=$now max=$max")
        } else {
            ToolResult.error("volume", "音量没调成功", "requested=$target actual=$now")
        }
    }

    // ── device ──
    fun deviceState(ctx: Context, focus: String = "all"): ToolResult {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val locked = km?.isKeyguardLocked ?: false
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0
        val lockText = if (locked) "屏幕现在是锁着的" else "屏幕现在是解锁的"
        val storageText = "可用存储 ${"%.1f".format(freeGb)} G，总共 ${"%.1f".format(totalGb)} G"
        val speech = when (focus) {
            "storage" -> storageText
            "lock" -> lockText
            "version" -> "系统是 Android ${Build.VERSION.RELEASE}，SDK ${Build.VERSION.SDK_INT}"
            else -> "系统是 Android ${Build.VERSION.RELEASE}，$lockText，$storageText"
        }
        return ToolResult.ok(
            "device", speech, verified = true,
            detail = "locked=$locked sdk=${Build.VERSION.SDK_INT} free=${freeGb}GB"
        )
    }

    // ── camera ──
    fun cameraOpen(ctx: Context, video: Boolean): ToolResult {
        val pkg = "com.sec.android.app.camera"
        val intent = if (video) {
            Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
        } else {
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            // Honest reporting: the intent opened a camera UI, but nothing has been captured
            // and we cannot verify a recording from here.
            ToolResult.sentWithoutProof(
                "camera",
                if (video) "已经打开相机的录像界面，录制需要你在相机里按一下，我没有确认到录制结果"
                else "已经打开相机，拍照需要你按一下快门，我没有确认到拍摄结果",
                "package=$pkg action=${intent.action} (UI opened, capture not verified)"
            )
        } catch (t: Throwable) {
            ToolResult.error("camera", "打不开相机，可能屏幕已锁定", t.javaClass.simpleName)
        }
    }

    // ── phone ──
    fun call(ctx: Context, alias: String): ToolResult {
        val table = Contacts.aliasTable(ctx)
        if (table.isEmpty()) {
            return ToolResult(
                ToolStatus.PERMISSION_DENIED, "call",
                "还没有配置联系人别名，请先在联系人配置文件里设置", "files/contacts.json missing or empty", null
            )
        }
        val entry = table.entries.firstOrNull { alias.contains(it.key) }
            ?: return ToolResult.unavailable(
                "call", "没有找到「${alias.take(6)}」这个联系人", "known=${table.keys}"
            )
        val number = entry.value["number"] ?: return ToolResult.error("call", "这个联系人没有号码", entry.key)
        val allowDirect = entry.value["call"]?.equals("true", true) == true
        val action = if (allowDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(action, android.net.Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (allowDirect && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.denied("call", "没有拨号权限，先打不开直接外呼", "CALL_PHONE not granted")
        }
        return try {
            ctx.startActivity(i)
            ToolResult.ok(
                "call",
                if (allowDirect) "正在拨打${entry.key}" else "已经帮你打开拨号界面，号码填好了，按一下拨号键就能打给${entry.key}",
                verified = true, detail = "$action -> $number"
            )
        } catch (t: Throwable) {
            ToolResult.error("call", "打不开拨号界面", t.javaClass.simpleName)
        }
    }

    /**
     * 联系人别名表: files/contacts.json
     * {"妈妈": {"number": "13800000000", "call": "false"}, ...}
     */
    object Contacts {
        /** Looks in both files/ and the external app dir, so pushing via adb is enough. */
        fun aliasTable(ctx: Context): Map<String, Map<String, String>> {
            val candidates = listOfNotNull(
                File(ctx.filesDir, "contacts.json"),
                ctx.getExternalFilesDir(null)?.let { File(it, "contacts.json") },
            )
            val f = candidates.firstOrNull { it.exists() } ?: return emptyMap()
            return runCatching {
                val root = org.json.JSONObject(f.readText())
                root.keys().asSequence().associateWith { k ->
                    val o = root.getJSONObject(k)
                    o.keys().asSequence().associateWith { o.getString(it) }
                }
            }.getOrElse { emptyMap() }
        }
    }
}
