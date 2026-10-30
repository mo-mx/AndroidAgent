package ai.closepaw.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Fully offline Chinese ASR.
 *
 * Measured on a 30-utterance Mandarin command set (PC, same audio):
 *   streaming-zipformer-zh-14M   CER 5.3%  exact 80.0%  45 ms   (legacy)
 *   paraformer-zh-small          CER 1.3%  exact 93.3%  25 ms   ← shipped
 *   zipformer-ctc-zh-int8-2025   CER 0.9%  exact 93.3%  95 ms, 351 MB
 *   sensevoice-small             CER 3.1%  exact 83.3%  63 MB
 * paraformer wins on size/latency with the same exact-match rate, so it is the default.
 *
 * Model files live outside the APK in `files/asr/` so they can be swapped without a rebuild.
 * Engine is detected from the file names (or an explicit `engine.txt`).
 */
object OfflineAsr {
    private const val TAG = "OfflineAsr"
    const val SAMPLE_RATE = 16000

    enum class Engine { PARAFORMER, ZIPFORMER_CTC, STREAMING }

    private var offline: OfflineRecognizer? = null
    private var online: OnlineRecognizer? = null
    private var loadedKey: String? = null
    private var loadedEngine: Engine? = null

    fun modelDir(root: File): File = File(root, "asr")

    private fun onnxFiles(d: File): List<File> =
        d.listFiles { f -> f.name.endsWith(".onnx") }?.toList() ?: emptyList()

    fun isInstalled(root: File): Boolean {
        val d = modelDir(root)
        if (!File(d, "tokens.txt").exists()) return false
        return onnxFiles(d).isNotEmpty()
    }

    fun detectEngine(root: File): Engine? {
        val d = modelDir(root)
        val files = onnxFiles(d)
        if (files.isEmpty()) return null
        val explicit = File(d, "engine.txt")
        if (explicit.exists()) {
            runCatching { explicit.readText().trim() }.getOrNull()?.let { name ->
                Engine.entries.firstOrNull { it.name.equals(name, true) }?.let { return it }
            }
        }
        val names = files.joinToString(" ") { it.name.lowercase() }
        return when {
            names.contains("paraformer") -> Engine.PARAFORMER
            names.contains("ctc") -> Engine.ZIPFORMER_CTC
            files.any { it.name.startsWith("encoder") } -> Engine.STREAMING
            // a lone model.onnx with tokens is most likely a CTC/paraformer export
            names.contains("model") -> Engine.ZIPFORMER_CTC
            else -> null
        }
    }

    private fun pickInt8(files: List<File>, prefix: String? = null): File? {
        val pool = if (prefix == null) files else files.filter { it.name.startsWith(prefix) }
        return pool.firstOrNull { it.name.contains("int8") } ?: pool.firstOrNull()
    }

    @Synchronized
    fun engine(root: File): Engine? {
        val key = modelDir(root).absolutePath
        if (loadedKey == key && loadedEngine != null) return loadedEngine
        release()
        val detected = detectEngine(root) ?: run {
            Log.w(TAG, "no usable offline ASR model in ${modelDir(root)}")
            return null
        }
        val d = modelDir(root)
        val tokens = File(d, "tokens.txt").absolutePath
        val files = onnxFiles(d)
        try {
            when (detected) {
                Engine.PARAFORMER -> {
                    val m = pickInt8(files) ?: return null
                    Log.i(TAG, "loading paraformer: ${m.name} (${m.length() / 1024 / 1024} MB)")
                    offline = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                paraformer = OfflineParaformerModelConfig(model = m.absolutePath),
                                tokens = tokens,
                                numThreads = 2,
                                provider = "cpu",
                                debug = false,
                            ),
                            decodingMethod = "greedy_search",
                        )
                    )
                }
                Engine.ZIPFORMER_CTC -> {
                    val m = pickInt8(files) ?: return null
                    Log.i(TAG, "loading zipformer-ctc: ${m.name} (${m.length() / 1024 / 1024} MB)")
                    offline = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                zipformerCtc = OfflineZipformerCtcModelConfig(model = m.absolutePath),
                                tokens = tokens,
                                numThreads = 2,
                                provider = "cpu",
                                debug = false,
                            ),
                            decodingMethod = "greedy_search",
                        )
                    )
                }
                Engine.STREAMING -> {
                    val enc = pickInt8(files, "encoder") ?: return null
                    val dec = pickInt8(files, "decoder") ?: return null
                    val joi = pickInt8(files, "joiner") ?: return null
                    Log.i(TAG, "loading streaming zipformer (legacy)")
                    online = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = enc.absolutePath,
                                    decoder = dec.absolutePath,
                                    joiner = joi.absolutePath,
                                ),
                                tokens = tokens,
                                numThreads = 2,
                                provider = "cpu",
                                debug = false,
                            ),
                            enableEndpoint = true,
                            decodingMethod = "greedy_search",
                        )
                    )
                }
            }
            loadedKey = key
            loadedEngine = detected
            Log.i(TAG, "offline ASR ready ($detected)")
            return detected
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load offline ASR ($detected)", t)
            release()
            return null
        }
    }

    /** Transcribe one complete utterance. Returns "" on failure. */
    @Synchronized
    fun transcribe(root: File, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val eng = engine(root) ?: return ""
        return try {
            when (eng) {
                Engine.PARAFORMER, Engine.ZIPFORMER_CTC -> {
                    val rec = offline ?: return ""
                    val s = rec.createStream()
                    s.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(s)
                    val text = rec.getResult(s).text
                    runCatching { s.release() }
                    text
                }
                Engine.STREAMING -> {
                    val rec = online ?: return ""
                    val s = rec.createStream()
                    var i = 0
                    val chunk = 3200
                    var last = ""
                    while (i < samples.size) {
                        val end = minOf(i + chunk, samples.size)
                        s.acceptWaveform(samples.copyOfRange(i, end), SAMPLE_RATE)
                        while (rec.isReady(s)) rec.decode(s)
                        last = runCatching { rec.getResult(s).text }.getOrDefault(last)
                        i = end
                    }
                    s.inputFinished()
                    while (rec.isReady(s)) rec.decode(s)
                    val text = runCatching { rec.getResult(s).text }.getOrDefault(last)
                    runCatching { s.release() }
                    text
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            ""
        }
    }

    @Synchronized
    fun release() {
        runCatching { offline?.release() }
        runCatching { online?.release() }
        offline = null
        online = null
        loadedKey = null
        loadedEngine = null
    }
}
