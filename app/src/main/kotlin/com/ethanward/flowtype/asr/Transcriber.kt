package com.ethanward.flowtype.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * One loaded Parakeet model (sherpa-onnx, NeMo transducer, greedy decoding,
 * no hotwords: PLAN §4.3). Not thread-safe: use it from one thread.
 */
class Transcriber private constructor(
    val model: AsrModel,
    private val recognizer: OfflineRecognizer,
    val loadMs: Long,
) {
    fun decode(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    companion object {
        fun load(model: AsrModel, dir: File, threads: Int): Transcriber {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = threads,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            )
            val started = System.nanoTime()
            val recognizer = OfflineRecognizer(config = config)
            return Transcriber(model, recognizer, (System.nanoTime() - started) / 1_000_000)
        }
    }
}
