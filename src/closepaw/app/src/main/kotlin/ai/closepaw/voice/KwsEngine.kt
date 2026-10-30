package ai.closepaw.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local wake-word engine (sherpa-onnx keyword spotting, zipformer zh-en 3M, int8).
 *
 * Model files live outside the APK in the app's external files dir (`kws/`), so the model can
 * be swapped without rebuilding:
 *   encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx
 *   decoder-epoch-13-avg-2-chunk-16-left-64.onnx
 *   joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx
 *   tokens.txt, keywords_wake.txt
 *
 * Nothing leaves the device: audio is consumed by a 16 kHz mono stream and only the matched
 * keyword string is ever emitted.
 */
object KwsEngine {
    private const val TAG = "KwsEngine"
    const val SAMPLE_RATE = 16000

    fun modelDir(root: File): File = File(root, "kws")

    fun isInstalled(root: File): Boolean {
        val d = modelDir(root)
        return File(d, "tokens.txt").exists() &&
            File(d, "keywords_wake.txt").exists() &&
            File(d, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx").exists() &&
            File(d, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx").exists() &&
            File(d, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx").exists()
    }

    /** Build a spotter from the on-device model dir, or null when files are missing. */
    fun create(root: File): KeywordSpotter? {
        if (!isInstalled(root)) {
            Log.w(TAG, "KWS model not installed at ${modelDir(root)}")
            return null
        }
        val d = modelDir(root)
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(d, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                    decoder = File(d, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(d, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                ),
                tokens = File(d, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            keywordsFile = File(d, "keywords_wake.txt").absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        )
        return try {
            val spotter = KeywordSpotter(config = config)
            Log.i(TAG, "KWS ready (zh-en 3M int8)")
            spotter
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create KeywordSpotter", t)
            null
        }
    }

    fun newStream(spotter: KeywordSpotter): OnlineStream = spotter.createStream()

    /**
     * Push one audio chunk and return the detected keyword, if any.
     * Resets the stream after a hit so the same keyword can fire again.
     */
    fun feed(spotter: KeywordSpotter, stream: OnlineStream, samples: FloatArray): String? {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (spotter.isReady(stream)) spotter.decode(stream)
        // v2.0.1 JNI has no isDetected(): the result's keyword field is only non-empty when a
        // keyword actually fired, so that is our detection signal.
        val kw = runCatching { spotter.getResult(stream).keyword }.getOrDefault("")
        if (kw.isNotBlank()) {
            runCatching { spotter.reset(stream) }
            return kw
        }
        return null
    }

    /**
     * Offline self-test: run a 16-bit mono 16 kHz WAV through the spotter and return the
     * keywords hit. Used to prove the engine works without a human speaking.
     */
    fun detectInWav(spotter: KeywordSpotter, wav: File): List<String> {
        val pcm = readWavMono16k(wav) ?: return emptyList()
        val stream = newStream(spotter)
        val hits = mutableListOf<String>()
        val chunk = 1600 // 100 ms
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + chunk, pcm.size)
            val slice = pcm.copyOfRange(i, end)
            feed(spotter, stream, slice)?.let { hits.add(it) }
            i = end
        }
        stream.release()
        return hits
    }

    /** Minimal RIFF/WAVE reader: PCM 16-bit mono, resampling to 16 kHz is NOT done. */
    private fun readWavMono16k(file: File): FloatArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                Log.e(TAG, "not a wav: ${file.name}"); return null
            }
            var pos = 12
            var dataOffset = -1
            var dataLen = 0
            var sampleRate = 0
            var channels = 0
            while (pos + 8 <= header.size && dataOffset < 0) {
                raf.seek(pos.toLong())
                val id = ByteArray(4).also { raf.readFully(it) }
                val size = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) })
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val name = String(id)
                if (name == "fmt ") {
                    val fmt = ByteArray(size).also { raf.readFully(it) }
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short // audioFormat
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                } else if (name == "data") {
                    dataOffset = (pos + 8)
                    dataLen = size
                } else {
                    raf.seek(pos + 8L + size)
                }
                pos = pos + 8 + size + (size % 2)
            }
            if (dataOffset < 0 || sampleRate == 0) return null
            Log.d(TAG, "wav ${file.name}: rate=$sampleRate ch=$channels bytes=$dataLen")
            if (sampleRate != SAMPLE_RATE) Log.w(TAG, "wav rate $sampleRate != $SAMPLE_RATE")
            raf.seek(dataOffset.toLong())
            val buf = ByteArray(dataLen)
            var read = 0
            while (read < dataLen) {
                val n = raf.read(buf, read, dataLen - read)
                if (n <= 0) break
                read += n
            }
            val shorts = ShortArray(read / 2)
            ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val out = FloatArray(shorts.size / channels.coerceAtLeast(1))
            for (i in out.indices) out[i] = shorts[i * channels.coerceAtLeast(1)] / 32768f
            out
        }
    } catch (t: Throwable) {
        Log.e(TAG, "wav read failed", t); null
    }
}
