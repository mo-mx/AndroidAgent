package ai.closepaw.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Offline RecognitionService backed by the local sherpa-onnx model.
 *
 * Two reasons this exists:
 *  1. android.service.voice.VoiceInteractionServiceInfo — and therefore AOSP's
 *     AssistantRoleBehavior qualification check — refuses a VoiceInteractionService whose XML
 *     omits `recognitionService`. Without it the ASSISTANT role is never granted.
 *  2. It is genuinely useful: apps using SpeechRecognizer can select this service and get
 *     recognition with no network and no cloud. Audio never leaves the device.
 */
class ClosepawRecognitionService : RecognitionService() {
    private val tag = "CpRecognition"

    @Volatile
    private var cancelled = false

    override fun onStartListening(intent: Intent?, listener: Callback) {
        val root = getExternalFilesDir(null) ?: filesDir
        if (!OfflineAsr.isInstalled(root)) {
            Log.w(tag, "offline ASR model missing at ${OfflineAsr.modelDir(root)}")
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        cancelled = false
        runCatching { listener.readyForSpeech(Bundle()) }
        runCatching { listener.beginningOfSpeech() }

        VoiceRecorder.record(maxMs = 8000, silenceMs = 800, onDone = { res ->
            if (cancelled) return@record
            if (res.samples.isEmpty()) {
                runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                return@record
            }
            runCatching { listener.endOfSpeech() }
            Thread({
                val text = decode(root, res.samples)
                if (text.isBlank()) {
                    runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                } else {
                    val b = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(text)
                        )
                    }
                    runCatching { listener.results(b) }
                }
            }, "recognition-decode").start()
        })
    }

    private fun decode(root: java.io.File, samples: FloatArray): String =
        OfflineAsr.transcribe(root, samples)

    override fun onCancel(listener: Callback) {
        cancelled = true
    }

    override fun onStopListening(listener: Callback) {
        // Results are delivered from the recorder callback.
    }
}
