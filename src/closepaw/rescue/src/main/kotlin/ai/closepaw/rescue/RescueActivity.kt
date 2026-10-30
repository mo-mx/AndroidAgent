package ai.closepaw.rescue

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One-time setup screen. Opening it once is the only manual step required on the phone:
 * it starts the rescue service, asks for the notification permission, and offers the two
 * exemptions that keep a foreground service alive on Samsung (battery optimisation + overlay,
 * the latter is what allows launching the main agent from the background).
 */
class RescueActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed Device Protected Storage while we are definitely unlocked, so the Direct Boot
        // path later has an endpoint + stable device id to work with.
        runCatching { RescueConfig.ensureSeeded(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
            setBackgroundColor(Color.parseColor("#101014"))
        }
        val title = TextView(this).apply {
            text = "ClosePaw 救援链路"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#B8B8C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 28, 0, 28)
        }
        val startBtn = Button(this).apply {
            text = "启动救援服务"
            setOnClickListener {
                RescueService.start(this@RescueActivity)
                refresh()
            }
        }
        val batteryBtn = Button(this).apply {
            text = "关闭电池优化（推荐）"
            setOnClickListener {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        }
        val overlayBtn = Button(this).apply {
            text = "允许后台启动主 Agent（悬浮窗权限）"
            setOnClickListener {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(startBtn, ViewGroup.LayoutParams(-1, -2))
        root.addView(batteryBtn, ViewGroup.LayoutParams(-1, -2))
        root.addView(overlayBtn, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        RescueService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val client = RescueClient(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val overlay = Settings.canDrawOverlays(this)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        status.text = buildString {
            appendLine("设备 ID：${client.deviceId}")
            appendLine("中继：${BuildConfig.RESCUE_ENDPOINT.ifBlank { "<构建时未配置>" }}")
            appendLine("已配置密钥：${if (client.configured) "是" else "否"}")
            appendLine("Direct Boot 存储：${RescueConfig.dpDir(this@RescueActivity).absolutePath}")
            appendLine("当前是否已解锁：${if (client.isUserUnlockedNow()) "是" else "否"}")
            appendLine("电池优化豁免：${if (batteryOk) "是" else "否"}")
            appendLine("悬浮窗（后台启动用）：${if (overlay) "是" else "否"}")
            appendLine()
            append("服务启动后无需再打开本页；重启（含未解锁阶段）会自动恢复。")
        }
    }
}
