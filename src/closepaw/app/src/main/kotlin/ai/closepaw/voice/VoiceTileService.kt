package ai.closepaw.voice

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import ai.closepaw.R

/**
 * Quick Settings tile: one tap in the notification shade opens the voice prompt.
 *
 * This exists because the system-assistant route is unavailable on this device (One UI 9
 * refuses to qualify a sideloaded app for `android.app.role.ASSISTANT`, and injecting the
 * secure setting directly crashes the platform's VoiceInteractionManagerService). A tile is
 * the closest OS-level entry point a third-party app can legitimately own, and it is
 * reachable without unlocking to the app drawer.
 */
class VoiceTileService : TileService() {
    private val tag = "VoiceTile"

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "语音助手"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Log.i(tag, "tile tapped")
        val intent = Intent(this, VoiceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: starting an activity from a tile requires an explicit PendingIntent
            // when the device is locked, otherwise the launch is silently dropped.
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
