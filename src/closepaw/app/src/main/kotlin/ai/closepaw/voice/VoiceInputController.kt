package ai.closepaw.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps [SpeechRecognizer] into a small callback-driven helper shared by the system
 * assistant session and the in-app voice activity, so both paths behave identically.
 *
 * The recognizer is created against the app's default recognition service (Google / Samsung
 * on stock One UI). Caller owns the lifecycle: [start] once, [destroy] when done.
 */
class VoiceInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val tag = "VoiceInput"
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial: String = ""
    private var destroyed = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String = "zh-CN") {
        if (destroyed) return
        lastPartial = ""
        if (!isAvailable) {
            onError("这台手机没有可用的语音识别服务")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onPartial("请讲…")
                override fun onBeginningOfSpeech() = onPartial("听到了，继续说…")
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = onPartial("识别中…")

                override fun onError(error: Int) {
                    Log.w(tag, "ASR error $error")
                    onError(describe(error))
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?: lastPartial
                    Log.i(tag, "ASR result: $text")
                    onFinal(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let {
                            lastPartial = it
                            onPartial(it)
                        }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            }
            r.startListening(intent)
        }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        destroyed = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到说话"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，请稍后再试"
        SpeechRecognizer.ERROR_CLIENT -> "识别被取消"
        else -> "语音识别失败（$error）"
    }
}
