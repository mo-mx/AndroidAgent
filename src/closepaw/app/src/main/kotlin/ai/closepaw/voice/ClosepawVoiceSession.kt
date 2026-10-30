package ai.closepaw.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * System-assistant session.
 *
 * Deliberately thin: instead of drawing its own UI and running its own recogniser (which used
 * to mean Google's cloud recogniser), it launches [VoiceActivity] — the single offline-first
 * voice surface — and hides itself. That keeps exactly one speech pipeline in the app and
 * guarantees a side-key press never reaches a cloud ASR.
 */
class ClosepawVoiceSession(context: Context) : VoiceInteractionSession(context) {

    private val tag = "ClosepawVoiceSession"
    private val main = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        TtsSpeaker.init(context)
        val started = try {
            val i = Intent(context, VoiceActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(i)
            true
        } catch (t: Throwable) {
            Log.e(tag, "failed to launch VoiceActivity", t)
            false
        }
        Log.i(tag, "onShow → VoiceActivity started=$started")
        if (!started) TtsSpeaker.speak("语音界面打不开", interrupt = true)
        main.postDelayed({ hide() }, if (started) 300 else 1200)
    }
}
