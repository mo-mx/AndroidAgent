package ai.closepaw.rescue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outbound rescue link — the minimum service that must survive into Direct Boot.
 *
 * Runs in its own `:rescue` process, is `directBootAware`, and reads nothing from
 * credential-encrypted storage, so it can start on LOCKED_BOOT_COMPLETED (before the user
 * ever unlocks) and keep reporting while the phone sits on the lock screen.
 *
 * Loop: long-poll the relay → execute a whitelisted op → post the result. Poll interval is
 * short while the screen is on and backs off when idle, to stay cheap on mobile data.
 */
class RescueService : Service() {

    companion object {
        private const val TAG = "RescueService"
        private const val CH_ID = "rescue_link"
        private const val NOTIF_ID = 4101
        private const val POLL_ACTIVE_MS = 3_000L
        private const val POLL_IDLE_MS = 15_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, RescueService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
            }
        }
    }

    private val running = AtomicBoolean(false)
    private lateinit var client: RescueClient
    private lateinit var ops: RescueOps
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        client = RescueClient(this)
        ops = RescueOps(this, client)
        createChannel()
        startForegroundCompat()
        val unlocked = runCatching { RescueClient.isUserUnlocked(this) }.getOrDefault(true)
        Log.i(
            TAG,
            "created; directBoot=${!unlocked} configured=${client.configured} " +
                "device=${client.deviceId} ${RescueConfig.describe(this)}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            worker = Thread({ loop() }, "rescue-poll").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    private fun loop() {
        if (!client.configured) {
            Log.e(TAG, "endpoint/secret not configured — rescue link disabled")
            return
        }
        var backoff = 0
        while (running.get()) {
            val started = System.currentTimeMillis()
            var screenOn = true
            try {
                val status = ops.status()
                screenOn = status.optBoolean("screen_on", true)
                val req = JSONObject()
                    .put("device", client.deviceId)
                    .put("screen_on", screenOn)
                    .put("direct_boot", status.optBoolean("direct_boot", false))
                    .put("battery", status.optInt("battery", -1))
                val reply = client.poll(req)
                if (reply.code == 200 && reply.body.isNotBlank()) {
                    handleCommand(reply.body)
                    backoff = 0
                } else if (reply.code == 204) {
                    backoff = 0
                } else {
                    Log.w(TAG, "poll code=${reply.code} body=${reply.body.take(160)}")
                    ops.lastError = "poll ${reply.code}: ${reply.body.take(80)}"
                    backoff = (backoff + 1).coerceAtMost(4)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "poll failed: ${t.javaClass.simpleName}")
                ops.lastError = "${t.javaClass.simpleName}: ${t.message}"
                backoff = (backoff + 1).coerceAtMost(4)
            }
            val base = if (screenOn) POLL_ACTIVE_MS else POLL_IDLE_MS
            val elapsed = System.currentTimeMillis() - started
            val wait = (base * (1 + backoff) - elapsed).coerceAtLeast(800)
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun handleCommand(raw: String) {
        val obj = JSONObject(raw)
        val cmdId = obj.optString("id", "")
        if (cmdId.isBlank()) return // poll returned "nothing pending"
        val op = obj.optString("op", "")
        Log.i(TAG, "cmd id=$cmdId op=$op")
        val result = try {
            when (op) {
                "status" -> ops.status()
                "diagnostics" -> ops.diagnostics()
                "battery" -> ops.batteryOnly()
                "start_agent" -> ops.startAgent()
                "speak" -> ops.speak(obj.optString("text", "收到指令"))
                "ping" -> JSONObject().put("ok", true).put("pong", System.currentTimeMillis())
                "install_apk" -> ops.unsupported(op, "需要用户确认：APK 安装必须由系统安装器弹窗确认")
                "silent_install" -> ops.unsupported(op, "无 Device Owner / root，Android 不允许静默安装")
                "toggle_accessibility" -> ops.unsupported(op, "修改无障碍开关需要 WRITE_SECURE_SETTINGS")
                else -> ops.unsupported(op, "未知指令")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "op $op failed", t)
            ops.lastError = "$op: ${t.javaClass.simpleName}: ${t.message}"
            JSONObject().put("ok", false).put("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        val payload = JSONObject()
            .put("device", client.deviceId)
            .put("id", cmdId)
            .put("op", op)
            .put("result", result)
            .put("ts", System.currentTimeMillis())
        val r = client.result(payload)
        if (r.code !in 200..299) Log.w(TAG, "result post code=${r.code} ${r.body.take(120)}")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "救援链路", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "保持与救援中继的连接（含未解锁状态）"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun startForegroundCompat() {
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_ID)
                .setContentTitle("ClosePaw 救援")
                .setContentText("救援链路运行中")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ClosePaw 救援")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
