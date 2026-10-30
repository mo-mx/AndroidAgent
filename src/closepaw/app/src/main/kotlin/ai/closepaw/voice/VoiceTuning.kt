package ai.closepaw.voice

import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Runtime tuning for the capture path, read from `files/voice_tuning.txt`.
 *
 * Why a file instead of constants: the winning audio source / VAD settings have to be measured
 * on the real device, and rebuilding + reinstalling for every A/B is slow. Keys (one per line,
 * `key=value`):
 *
 *   source=mic|voice_recognition|voice_communication|camcorder
 *   silence_ms=1200
 *   max_ms=12000
 *   rms=0.008
 *   dump=1        (write captured wavs to files/asr_debug for offline scoring)
 */
object VoiceTuning {
    private const val TAG = "VoiceTuning"

    var sourceName: String = "voice_recognition"
    var silenceMs: Int = 1200
    var maxMs: Int = 12000
    var rms: Double = 0.008
    var dump: Boolean = true

    fun load(filesDir: File): VoiceTuning {
        val f = File(filesDir, "voice_tuning.txt")
        if (!f.exists()) {
            Log.i(TAG, "no tuning file, using defaults ($sourceName, silence=${silenceMs}ms)")
            apply()
            return this
        }
        runCatching {
            f.readLines().forEach { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) return@forEach
                val (k, v) = l.split("=", limit = 2).map { it.trim() }
                when (k) {
                    "source" -> sourceName = v
                    "silence_ms" -> v.toIntOrNull()?.let { silenceMs = it }
                    "max_ms" -> v.toIntOrNull()?.let { maxMs = it }
                    "rms" -> v.toDoubleOrNull()?.let { rms = it }
                    "dump" -> dump = (v == "1" || v.equals("true", true))
                }
            }
        }.onFailure { Log.w(TAG, "bad tuning file: $it") }
        Log.i(TAG, "tuning: source=$sourceName silence=${silenceMs}ms max=${maxMs}ms rms=$rms dump=$dump")
        apply()
        return this
    }

    private fun apply() {
        VoiceRecorder.audioSource = when (sourceName) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}
