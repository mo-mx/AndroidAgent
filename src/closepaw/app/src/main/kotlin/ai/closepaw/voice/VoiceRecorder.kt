package ai.closepaw.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Push-to-talk recorder with energy VAD + pre-roll.
 *
 * Fixes learned from the CER investigation:
 *  - **Pre-roll**: keeps the last [PRE_ROLL_MS] of audio before speech onset in a ring buffer and
 *    prepends it, so the first syllable is not clipped (was the top complaint).
 *  - **Longer end-of-speech**: natural pauses no longer end the utterance at 800 ms.
 *  - **Longer hard cap** so a full sentence is not truncated mid-way.
 *  - **Configurable audio source**: Samsung's VOICE_RECOGNITION path applies processing that can
 *    hurt far-field speech; MIC can be cleaner. Selected at runtime for A/B testing.
 *  - **Optional WAV dump** (`setDumpDir`) so captured device audio can be scored on a PC with the
 *    exact same model — that is how capture-path bugs get separated from model quality.
 */
object VoiceRecorder {
    private const val TAG = "VoiceRecorder"
    const val SAMPLE_RATE = 16000
    const val PRE_ROLL_MS = 500

    var audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private var dumpDir: File? = null

    class Result(val samples: FloatArray, val reason: String, val wav: File? = null)

    fun setDumpDir(dir: File?) {
        dumpDir = dir
        runCatching { dir?.mkdirs() }
    }

    @Volatile private var cancelled = false
    @Volatile private var activeRecorder: AudioRecord? = null

    /**
     * Abort the in-flight capture. Called when a new wake-up arrives: the previous turn's audio
     * must stop existing, otherwise its [onDone] callback would answer the new session.
     */
    fun cancel() {
        cancelled = true
        runCatching { activeRecorder?.stop() }
        Log.i(TAG, "record cancelled by caller")
    }

    @SuppressLint("MissingPermission")
    fun record(
        maxMs: Int = 12000,
        silenceMs: Int = 1200,
        rmsThreshold: Double = 0.008,
        onLevel: (Double) -> Unit = {},
        onDone: (Result) -> Unit,
    ): Thread {
        val t = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, SAMPLE_RATE / 10 * 2) // ~100 ms of shorts
            cancelled = false
            val rec = try {
                AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 4
                )
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord init failed (source=$audioSource)", t)
                onDone(Result(FloatArray(0), "init_failed"))
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (source=$audioSource)")
                runCatching { rec.release() }
                onDone(Result(FloatArray(0), "init_failed"))
                return@Thread
            }

            val chunks = ArrayList<FloatArray>()
            // ring buffer of recent silence, used as pre-roll
            val preRoll = ArrayDeque<FloatArray>()
            var preRollSamples = 0
            val preRollLimit = SAMPLE_RATE * PRE_ROLL_MS / 1000

            val buf = ShortArray(bufSize)
            var total = 0
            var speechSeen = false
            var trailingSilenceMs = 0
            var reason = "max_length"
            activeRecorder = rec
            try {
                rec.startRecording()
                val start = System.currentTimeMillis()
                while (true) {
                    if (cancelled) {
                        reason = "cancelled"
                        break
                    }
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val floats = FloatArray(n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i] / 32768f
                        floats[i] = v
                        sum += (v * v).toDouble()
                    }
                    val rms = sqrt(sum / n)
                    onLevel(rms)
                    val chunkMs = n * 1000 / SAMPLE_RATE

                    if (!speechSeen) {
                        if (rms > rmsThreshold) {
                            // speech just started: prepend whatever pre-roll we retained
                            speechSeen = true
                            while (preRoll.isNotEmpty()) {
                                val c = preRoll.removeFirst()
                                chunks.add(c)
                                total += c.size
                            }
                            preRollSamples = 0
                            chunks.add(floats)
                            total += n
                            trailingSilenceMs = 0
                        } else {
                            preRoll.addLast(floats)
                            preRollSamples += n
                            while (preRollSamples > preRollLimit && preRoll.isNotEmpty()) {
                                preRollSamples -= preRoll.removeFirst().size
                            }
                        }
                    } else {
                        chunks.add(floats)
                        total += n
                        if (rms > rmsThreshold) {
                            trailingSilenceMs = 0
                        } else {
                            trailingSilenceMs += chunkMs
                            if (trailingSilenceMs >= silenceMs) {
                                reason = "silence"
                                break
                            }
                        }
                    }
                    if (System.currentTimeMillis() - start >= maxMs) break
                    if (total >= SAMPLE_RATE * 30) break
                }
            } catch (t: Throwable) {
                Log.e(TAG, "record failed", t)
                reason = "error"
            } finally {
                activeRecorder = null
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }

            val out = FloatArray(total)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, off, c.size)
                off += c.size
            }
            val wav = if (total > 0) dump(out, reason) else null
            Log.i(
                TAG,
                "recorded ${total} samples (${total * 1000 / SAMPLE_RATE} ms), reason=$reason, " +
                    "speech=$speechSeen, source=$audioSource, wav=${wav?.name ?: "-"}"
            )
            if (cancelled) onDone(Result(FloatArray(0), "cancelled", wav))
            else if (!speechSeen) onDone(Result(FloatArray(0), "no_speech", wav))
            else onDone(Result(out, reason, wav))
        }, "voice-recorder")
        t.isDaemon = true
        t.start()
        return t
    }

    /** Write a 16 kHz mono 16-bit PCM WAV so device audio can be scored on a PC. */
    private fun dump(samples: FloatArray, reason: String): File? {
        val dir = dumpDir ?: return null
        return try {
            dir.mkdirs()
            val f = File(dir, "cap_${System.currentTimeMillis()}_$reason.wav")
            val dataBytes = samples.size * 2
            FileOutputStream(f).use { out ->
                fun le32(v: Int) = byteArrayOf(
                    (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
                    ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
                )
                fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
                out.write("RIFF".toByteArray()); out.write(le32(36 + dataBytes))
                out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
                out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
                out.write(le32(SAMPLE_RATE)); out.write(le32(SAMPLE_RATE * 2))
                out.write(le16(2)); out.write(le16(16))
                out.write("data".toByteArray()); out.write(le32(dataBytes))
                val pcm = ByteArray(dataBytes)
                for (i in samples.indices) {
                    val s = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                    pcm[i * 2] = (s and 0xff).toByte()
                    pcm[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                }
                out.write(pcm)
            }
            f
        } catch (t: Throwable) {
            Log.w(TAG, "wav dump failed", t)
            null
        }
    }

    fun levelToUi(rms: Double): Float = (rms * 18.0).coerceIn(0.0, 1.0).toFloat()
}
