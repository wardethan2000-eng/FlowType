package com.ethanward.flowtype.asr

/**
 * The speech models Phase 0 compares (PLAN §4.3), from sherpa-onnx's
 * `asr-models` release. Sizes and SHA-256 are the release assets' own, pinned.
 */
data class AsrModel(
    val id: String,
    val label: String,
    /** One line for the Models screen. */
    val summary: String,
    /** Asset name without `.tar.bz2`; also the folder inside the archive. */
    val archive: String,
    val bytes: Long,
    val sha256: String,
) {
    val url: String get() = "$RELEASE/$archive.tar.bz2"

    companion object {
        const val RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
        /** Files every NeMo transducer package carries. */
        val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        /** Kept from the archive so the bench can run before any recording exists. */
        const val SAMPLE_WAV = "test_wavs/0.wav"
    }
}

object AsrModels {
    val V2 = AsrModel(
        id = "v2",
        label = "Parakeet v2",
        summary = "The most accurate for English. Recommended.",
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        bytes = 482_468_385,
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
    )
    val SMALL = AsrModel(
        id = "110m",
        label = "Parakeet 110M",
        summary = "Smaller and quicker, a little less accurate.",
        archive = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
        bytes = 108_035_095,
        sha256 = "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b",
    )
    val UNIFIED = AsrModel(
        id = "unified",
        label = "Parakeet unified",
        summary = "A newer 0.6B model, on trial against v2.",
        archive = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
        bytes = 501_350_460,
        sha256 = "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150",
    )
    val ALL = listOf(V2, SMALL, UNIFIED)
    val DEFAULT = V2

    fun byId(id: String): AsrModel? = ALL.firstOrNull { it.id == id }

    /** Silero VAD, for the chunked decoding the bench compares. */
    const val VAD_URL = "${AsrModel.RELEASE}/silero_vad.onnx"
    const val VAD_BYTES = 643_854L
    const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
}
