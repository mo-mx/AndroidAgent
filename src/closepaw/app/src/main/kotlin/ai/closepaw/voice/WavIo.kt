package ai.closepaw.voice

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader for the on-device ASR benchmark path (PCM16, any rate, mono/stereo). */
object WavIo {
    private const val TAG = "WavIo"

    /** Returns (samples at 16 kHz mono float, trueSampleRate). */
    fun read(file: File): FloatArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                Log.e(TAG, "not a wav: ${file.name}")
                return null
            }
            var pos = 12
            var dataOffset = -1
            var dataLen = 0
            var sampleRate = 16000
            var channels = 1
            var bits = 16
            val fileLen = raf.length()
            while (pos + 8 <= fileLen && dataOffset < 0) {
                raf.seek(pos.toLong())
                val id = ByteArray(4).also { raf.readFully(it) }
                val size = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) })
                    .order(ByteOrder.LITTLE_ENDIAN).int
                when (String(id)) {
                    "fmt " -> {
                        val fmt = ByteArray(size).also { raf.readFully(it) }
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // format
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int  // byte rate
                        bb.short // block align
                        bits = bb.short.toInt()
                    }
                    "data" -> {
                        dataOffset = pos + 8
                        dataLen = size
                    }
                    else -> raf.seek(pos + 8L + size)
                }
                pos += 8 + size + (size % 2)
            }
            if (dataOffset < 0 || bits != 16) {
                Log.e(TAG, "unsupported wav: bits=$bits dataOffset=$dataOffset")
                return null
            }
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
            val ch = channels.coerceAtLeast(1)
            val mono = FloatArray(shorts.size / ch)
            for (i in mono.indices) {
                var acc = 0f
                for (c in 0 until ch) acc += shorts[i * ch + c] / 32768f
                mono[i] = acc / ch
            }
            Log.d(TAG, "${file.name}: sr=$sampleRate ch=$channels bits=$bits samples=${mono.size}")
            if (sampleRate != 16000) resampleLinear(mono, sampleRate, 16000) else mono
        }
    } catch (t: Throwable) {
        Log.e(TAG, "read failed", t)
        null
    }

    /** Real resampling (linear interpolation) — never just relabel the rate. */
    fun resampleLinear(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to.toDouble()
        for (i in 0 until n) {
            val src = i * step
            val i0 = src.toInt().coerceIn(0, x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = x[i0] * (1 - frac) + x[i1] * frac
        }
        return out
    }
}
