package ai.closepaw.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * Minimal TextToSpeech wrapper used for all spoken feedback.
 *
 * Deliberately tiny: one global queue, zh-CN preferred (falls back to system default),
 * never blocks callers, and drops overlapping announcements instead of stacking them.
 * All speech is fire-and-forget — a failed utterance must never break the agent loop.
 */
object TtsSpeaker {
    private const val TAG = "TtsSpeaker"

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    // Bumped by every stop(); in-flight drain()/callbacks from older generations are dropped.
    @Volatile private var generation = 0
    private var audioManager: AudioManager? = null
    private var focusHeld = false
    private var focusRequest: AudioFocusRequest? = null
    private val pending = ArrayDeque<Pair<String, Boolean>>() // text to interrupt-current
    private var currentUtteranceId: String? = null

    fun init(context: Context) {
        if (ready || initializing) return
        initializing = true
        val app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val zh = Locale.SIMPLIFIED_CHINESE
                val res = engine?.setLanguage(zh)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "zh-CN not available, keeping engine default")
                }
                engine?.setSpeechRate(1.0f)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { currentUtteranceId = utteranceId }
                    override fun onDone(utteranceId: String?) {
                        if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "utterance error $errorCode for $utteranceId")
                        if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    }
                })
                ready = true
                initializing = false
                main.post { drain() }
                Log.i(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
                initializing = false
            }
        }
    }

    /** Speak [text]. [interrupt] flushes anything currently queued/spoken. */
    fun speak(text: String, interrupt: Boolean = false) {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        Log.i(TAG, "speak(interrupt=$interrupt, gen=$generation): ${clean.take(40)}")
        synchronized(pending) { pending.addLast(clean to interrupt) }
        main.post { drain() }
    }

    /** True while an utterance is queued or playing — callers must not record into TTS. */
    fun isSpeaking(): Boolean {
        val queued = synchronized(pending) { pending.isNotEmpty() }
        return queued || currentUtteranceId != null
    }

    /**
     * Hard stop: invalidate the generation, flush the queue, stop the engine's audio output,
     * drop audio focus. Anything already dispatched is dead after this returns.
     */
    fun stop() {
        generation++
        val engine = tts
        runCatching { engine?.stop() }   // genuinely stops AudioTrack playback, not just the queue
        synchronized(pending) { pending.clear() }
        currentUtteranceId = null
        abandonFocus()
        Log.i(TAG, "stop() → gen=$generation, queue cleared, focus released")
    }

    /** True when nothing is queued and no utterance is playing. */
    fun idle(): Boolean = !isSpeaking()

    /** Stop and block (up to [timeoutMs]) until the engine reports silence. */
    fun stopAndAwait(timeoutMs: Long = 400): Boolean {
        stop()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isSpeaking()) return true
            Thread.sleep(25)
        }
        return !isSpeaking()
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        if (focusHeld) return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener { }
            .build().also { focusRequest = it }
        val r = runCatching { am.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) Log.w(TAG, "audio focus not granted (result=$r), speaking anyway")
        return focusHeld
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        val am = audioManager ?: return
        val req = focusRequest
        if (req != null) runCatching { am.abandonAudioFocusRequest(req) }
        focusHeld = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun drain() {
        if (!ready) return
        val engine = tts ?: return
        while (true) {
            val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() }
            ?: return
            val (text, interrupt) = next
            val myGen = generation
            if (myGen != generation) continue
            val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "cp-$myGen-${System.currentTimeMillis()}"
            currentUtteranceId = id
            requestFocus()
            val queued = engine.speak(text, mode, null, id)
            if (queued != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() rejected ($queued) for $id")
                currentUtteranceId = null
            }
        }
    }

    /**
     * Strip things that read badly aloud: markdown emphasis, code fences, URLs, and
     * the emoji status prefixes ClosePaw uses in logs. Keeps the spoken text short —
     * long answers are clipped so feedback stays within a few seconds.
     */
    fun sanitize(raw: String): String {
        var s = raw
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("https?://\\S+"), " ")
        s = s.replace(Regex("[*_#>]+"), " ")
        // Java regex needs \x{...} for astral code points; \u{...} is not supported.
        s = s.replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length > MAX_SPOKEN_CHARS) s.take(MAX_SPOKEN_CHARS) + "，其余内容见屏幕" else s
    }

    private const val MAX_SPOKEN_CHARS = 180
}
