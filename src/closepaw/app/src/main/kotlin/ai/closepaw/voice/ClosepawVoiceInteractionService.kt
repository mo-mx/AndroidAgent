package ai.closepaw.voice

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Makes ClosePaw selectable as the system digital assistant
 * (设置 → 应用 → 默认应用 → 数字助理应用), so the side-key long press / assist gesture
 * can open the voice session instead of Google Assistant or Bixby.
 *
 * This only registers the assistant entry point. Always-on wake-word listening is a
 * separate mechanism (see phase-3 wake-word work) — the framework's DSP hotword API is
 * restricted to preinstalled system apps, so this service deliberately does not pretend
 * to offer it.
 */
class ClosepawVoiceInteractionService : VoiceInteractionService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceInteractionService created")
    }

    override fun onReady() {
        super.onReady()
        ready = true
        Log.i(TAG, "VoiceInteractionService ready (ClosePaw is the digital assistant)")
    }

    override fun onShutdown() {
        ready = false
        Log.i(TAG, "VoiceInteractionService shutdown")
        super.onShutdown()
    }

    companion object {
        private const val TAG = "CpVoiceInteraction"
        @Volatile
        var ready: Boolean = false
            private set
    }
}
