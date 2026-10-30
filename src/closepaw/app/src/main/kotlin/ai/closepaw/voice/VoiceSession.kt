package ai.closepaw.voice

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for the voice pipeline state.
 *
 * Every wake-up bumps [sessionId]. Anything asynchronous (recorder callback, ASR decode thread,
 * TTS utterance, tool execution) captures the id it was started with and must drop its result
 * if [sessionId] has moved on. That is what stops a previous turn's audio or answer from
 * leaking into a new one.
 *
 * States: IDLE → LISTENING → RECOGNIZING → EXECUTING → SPEAKING → (LISTENING | IDLE)
 */
object VoiceSession {
    enum class State { IDLE, LISTENING, RECOGNIZING, EXECUTING, SPEAKING }

    private val idGen = AtomicLong(0)
    @Volatile private var _sessionId: Long = 0
    @Volatile private var _state: State = State.IDLE
    @Volatile var wakeStartedAt: Long = 0

    val sessionId: Long get() = _sessionId
    val state: State get() = _state

    /** Begin a brand new session; returns the new id. Invalidates every earlier callback. */
    fun beginWake(reason: String): Long {
        val id = idGen.incrementAndGet()
        _sessionId = id
        wakeStartedAt = System.currentTimeMillis()
        Log.i(TAG, "SESSION #$id begin ($reason), state=$_state → resetting")
        return id
    }

    fun isCurrent(id: Long): Boolean = id == _sessionId

    fun setState(id: Long, next: State, detail: String = "") {
        if (!isCurrent(id)) {
            Log.d(TAG, "SESSION #$id stale, ignoring transition to $next ($detail)")
            return
        }
        val prev = _state
        _state = next
        Log.i(TAG, "SESSION #$id $prev → $next${if (detail.isEmpty()) "" else " ($detail)"} " +
            "@+${System.currentTimeMillis() - wakeStartedAt}ms")
    }

    /** Mark the pipeline idle without starting a new session (e.g. UI closed). */
    fun finish(reason: String) {
        Log.i(TAG, "SESSION #$_sessionId finish ($reason) from $_state")
        _state = State.IDLE
    }
    private const val TAG = "VoiceSession"
}
