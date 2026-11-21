package ai.closepaw.rescue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the rescue link at boot — including the Direct Boot window.
 *
 * `LOCKED_BOOT_COMPLETED` arrives after the system is up but *before* the user unlocks, and is
 * only delivered to `directBootAware` receivers. This is the whole point of the experiment:
 * the minimum rescue path must not wait for a PIN. Everything this receiver touches goes
 * through [RescueConfig] (Device Protected Storage) so nothing throws while CE storage is
 * still locked.
 *
 * Android 15+ forbids launching dataSync/microphone/mediaPlayback FGS from BOOT_COMPLETED,
 * which is why the service declares `specialUse` — and whether the OEM honours it during
 * Direct Boot is measured on-device, not assumed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val unlocked = runCatching { RescueClient.isUserUnlocked(context) }.getOrDefault(true)
        Log.i("RescueBoot", "received $action (userUnlocked=$unlocked, directBoot=${!unlocked})")
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching { RescueConfig.ensureSeeded(context) }
                RescueService.start(context)
            }
        }
    }
}
