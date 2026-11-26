package com.joe.edith

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * openWakeWord ("Hey Jarvis") running on the phone. Same three-step pipeline as the Python version:
 *   16 kHz audio -> mel spectrogram -> speech embeddings -> wake word score (0..1).
 * Feed it 1280 samples (80 ms) at a time.
 */
class WakeWord(dir: File) : AutoCloseable {
    companion object {
        const val CHUNK = 1280                 // 80 ms of 16 kHz audio
        private const val CONTEXT = 480        // extra samples the mel model needs from the previous chunk
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76      // mel frames per embedding
        private const val FEATURES = 16        // embeddings per wake word prediction
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val mel = env.createSession(File(dir, "melspectrogram.onnx").path, opts)
    private val emb = env.createSession(File(dir, "embedding_model.onnx").path, opts)
    private val ww = env.createSession(File(dir, "hey_jarvis_v0.1.onnx").path, opts)
    private val melIn = mel.inputNames.first()
    private val embIn = emb.inputNames.first()
    private val wwIn = ww.inputNames.first()

    private val tail = FloatArray(CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var warmup = 0

    fun reset() {
        tail.fill(0f)
        melFrames.clear()
        features.clear()
        warmup = 0
    }

    /** Returns the wake word score for this chunk (0 while it's still warming up). */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK)
        // 1) mel spectrogram of the last 480 + 1280 samples (raw int16 values, as openWakeWord does)
        val audio = FloatArray(CONTEXT + CHUNK)
        System.arraycopy(tail, 0, audio, 0, CONTEXT)
        for (i in 0 until CHUNK) audio[CONTEXT + i] = chunk[i].toFloat()
        System.arraycopy(audio, CHUNK, tail, 0, CONTEXT)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { t ->
            mel.run(mapOf(melIn to t)).use { r ->
                val buf = (r.get(0) as OnnxTensor).floatBuffer
                val n = buf.remaining() / MEL_BINS
                for (f in 0 until n) {
                    val frame = FloatArray(MEL_BINS)
                    for (b in 0 until MEL_BINS) frame[b] = buf.get() / 10f + 2f
                    melFrames.addLast(frame)
                }
            }
        }
        while (melFrames.size > 120) melFrames.removeFirst()
        if (melFrames.size < MEL_WINDOW) return 0f

        // 2) one speech embedding from the newest 76 mel frames
        val window = FloatArray(MEL_WINDOW * MEL_BINS)
        val start = melFrames.size - MEL_WINDOW
        for (i in 0 until MEL_WINDOW) System.arraycopy(melFrames[start + i], 0, window, i * MEL_BINS, MEL_BINS)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
            emb.run(mapOf(embIn to t)).use { r ->
                val buf = (r.get(0) as OnnxTensor).floatBuffer
                val v = FloatArray(buf.remaining())
                buf.get(v)
                features.addLast(v)
            }
        }
        while (features.size > FEATURES * 2) features.removeFirst()
        if (features.size < FEATURES) return 0f

        // 3) wake word score from the newest 16 embeddings
        val dim = features.last().size
        val input = FloatArray(FEATURES * dim)
        val fs = features.size - FEATURES
        for (i in 0 until FEATURES) System.arraycopy(features[fs + i], 0, input, i * dim, dim)
        val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, FEATURES.toLong(), dim.toLong())).use { t ->
            ww.run(mapOf(wwIn to t)).use { r -> (r.get(0) as OnnxTensor).floatBuffer.get(0) }
        }
        warmup++
        return if (warmup < 5) 0f else score      // openWakeWord ignores the first few predictions too
    }

    override fun close() {
        mel.close(); emb.close(); ww.close(); opts.close()
    }
}
