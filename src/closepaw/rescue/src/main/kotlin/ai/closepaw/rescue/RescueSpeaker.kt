package ai.closepaw.rescue

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Tiny TTS helper so the relay can make the phone say something out loud (rescue feedback). */
object RescueSpeaker {
    private const val TAG = "RescueSpeaker"
    private var tts: TextToSpeech? = null

    fun speak(ctx: Context, text: String) {
        val app = ctx.applicationContext
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rescue-${System.currentTimeMillis()}")
                } else {
                    Log.e(TAG, "TTS init failed $status")
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rescue-${System.currentTimeMillis()}")
        }
    }
}
