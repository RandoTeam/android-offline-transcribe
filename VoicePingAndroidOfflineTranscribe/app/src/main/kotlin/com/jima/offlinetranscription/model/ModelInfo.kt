package com.voiceping.offlinetranscription.model

enum class EngineType { SHERPA_ONNX, SHERPA_ONNX_STREAMING, CACTUS, QWEN_ASR, QWEN_ONNX, ANDROID_SPEECH }
enum class SherpaModelType { WHISPER, MOONSHINE, SENSE_VOICE, ZIPFORMER_TRANSDUCER, OMNILINGUAL_CTC, PARAKEET_TRANSDUCER }
enum class CactusModelType { WHISPER }

data class ModelFile(val url: String, val localName: String)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val engineType: EngineType,
    val sherpaModelType: SherpaModelType? = null,
    val cactusModelType: CactusModelType? = null,
    val parameterCount: String,
    val sizeOnDisk: String,
    val description: String,
    val languages: String = "99 languages",
    val files: List<ModelFile>
) {
    val capabilities: EngineCapabilities
        get() = capabilitiesFor(engineType, id)

    val inferenceMethod: String
        get() = when (engineType) {
            EngineType.SHERPA_ONNX -> "sherpa-onnx offline (ONNX Runtime)"
            EngineType.SHERPA_ONNX_STREAMING -> "sherpa-onnx streaming (ONNX Runtime)"
            EngineType.CACTUS -> "whisper.cpp (GGML)"
            EngineType.QWEN_ASR -> "qwen-asr (Pure C/NEON)"
            EngineType.QWEN_ONNX -> "QwenASR (ONNX Runtime)"
            EngineType.ANDROID_SPEECH -> "Android SpeechRecognizer"
        }

    companion object {
        private const val WHISPER_TINY_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"

        private const val WHISPER_BASE_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"

        private const val WHISPER_BASE_EN_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/"

        private const val WHISPER_SMALL_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/"

        private const val WHISPER_TURBO_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/"

        private const val MOONSHINE_TINY_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/"

        private const val MOONSHINE_BASE_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8/resolve/main/"

        private const val SENSEVOICE_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"

        private const val ZIPFORMER_EN_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/"

        private const val OMNILINGUAL_300M_BASE_URL =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/resolve/main/"

        private const val QWEN_ASR_0_6B_BASE_URL =
            "https://huggingface.co/Qwen/Qwen3-ASR-0.6B/resolve/main/"

        private const val QWEN_ASR_ONNX_BASE_URL =
            "https://huggingface.co/jima/qwen3-asr-0.6b-onnx-int8/resolve/main/"

        private const val PARAKEET_V3_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"

        private const val WHISPER_GGML_BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        private val LEGACY_MODEL_ID_MAP = mapOf<String, String>(
            // Both Qwen CPU and ONNX cards are kept as reference — no remapping
        )

        private fun whisperFiles(baseUrl: String, prefix: String) = listOf(
            ModelFile("${baseUrl}${prefix}-encoder.int8.onnx", "encoder.int8.onnx"),
            ModelFile("${baseUrl}${prefix}-decoder.int8.onnx", "decoder.int8.onnx"),
            ModelFile("${baseUrl}${prefix}-tokens.txt", "tokens.txt"),
        )

        private fun moonshineFiles(baseUrl: String) = listOf(
            ModelFile("${baseUrl}preprocess.onnx", "preprocess.onnx"),
            ModelFile("${baseUrl}encode.int8.onnx", "encode.int8.onnx"),
            ModelFile("${baseUrl}uncached_decode.int8.onnx", "uncached_decode.int8.onnx"),
            ModelFile("${baseUrl}cached_decode.int8.onnx", "cached_decode.int8.onnx"),
            ModelFile("${baseUrl}tokens.txt", "tokens.txt"),
        )

        val availableModels = listOf(
            // -- SenseVoice (sherpa-onnx) --
            ModelInfo(
                id = "sensevoice-small",
                displayName = "SenseVoice Small",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.SENSE_VOICE,
                parameterCount = "234M",
                sizeOnDisk = "~240 MB",
                description = "Multilingual (zh/en/ja/ko/yue). 5x faster than Whisper Small.",
                languages = "zh/en/ja/ko/yue",
                files = listOf(
                    ModelFile("${SENSEVOICE_BASE_URL}model.int8.onnx", "model.int8.onnx"),
                    ModelFile("${SENSEVOICE_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- Parakeet TDT (sherpa-onnx offline transducer) --
            ModelInfo(
                id = "parakeet-tdt-v3",
                displayName = "Parakeet TDT 0.6B",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.PARAKEET_TRANSDUCER,
                parameterCount = "600M",
                sizeOnDisk = "~671 MB",
                description = "Best English WER (2.5%). 25 European languages.",
                languages = "25 European languages",
                files = listOf(
                    ModelFile("${PARAKEET_V3_BASE_URL}encoder.int8.onnx", "encoder.int8.onnx"),
                    ModelFile("${PARAKEET_V3_BASE_URL}decoder.int8.onnx", "decoder.int8.onnx"),
                    ModelFile("${PARAKEET_V3_BASE_URL}joiner.int8.onnx", "joiner.int8.onnx"),
                    ModelFile("${PARAKEET_V3_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- Whisper (sherpa-onnx ONNX) --
            ModelInfo(
                id = "whisper-tiny",
                displayName = "Whisper Tiny",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.WHISPER,
                parameterCount = "39M",
                sizeOnDisk = "~100 MB",
                description = "Fastest Whisper model. Good for quick notes.",
                files = whisperFiles(WHISPER_TINY_BASE_URL, "tiny")
            ),
            ModelInfo(
                id = "whisper-base",
                displayName = "Whisper Base",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.WHISPER,
                parameterCount = "74M",
                sizeOnDisk = "~160 MB",
                description = "Balanced speed and accuracy.",
                files = whisperFiles(WHISPER_BASE_BASE_URL, "base")
            ),
            ModelInfo(
                id = "whisper-base-en",
                displayName = "Whisper Base (.en)",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.WHISPER,
                parameterCount = "74M",
                sizeOnDisk = "~160 MB",
                description = "English-only base model with lower decoding overhead.",
                languages = "English",
                files = whisperFiles(WHISPER_BASE_EN_BASE_URL, "base.en")
            ),
            ModelInfo(
                id = "whisper-small",
                displayName = "Whisper Small",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.WHISPER,
                parameterCount = "244M",
                sizeOnDisk = "~490 MB",
                description = "Higher accuracy. Best for important recordings.",
                files = whisperFiles(WHISPER_SMALL_BASE_URL, "small")
            ),
            ModelInfo(
                id = "whisper-large-v3-turbo",
                displayName = "Whisper Turbo",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.WHISPER,
                parameterCount = "809M",
                sizeOnDisk = "~1.0 GB",
                description = "Best accuracy. INT8 quantized ONNX for fast inference.",
                files = whisperFiles(WHISPER_TURBO_BASE_URL, "turbo")
            ),
            // -- Moonshine (sherpa-onnx) --
            ModelInfo(
                id = "moonshine-tiny",
                displayName = "Moonshine Tiny",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.MOONSHINE,
                parameterCount = "27M",
                sizeOnDisk = "~125 MB",
                description = "Ultra-fast, English. 5x faster than Whisper Tiny.",
                languages = "English",
                files = moonshineFiles(MOONSHINE_TINY_BASE_URL)
            ),
            ModelInfo(
                id = "moonshine-base",
                displayName = "Moonshine Base",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.MOONSHINE,
                parameterCount = "62M",
                sizeOnDisk = "~290 MB",
                description = "Fast, English. Matches Whisper Base accuracy.",
                languages = "English",
                files = moonshineFiles(MOONSHINE_BASE_BASE_URL)
            ),
            // -- Omnilingual CTC (sherpa-onnx) --
            ModelInfo(
                id = "omnilingual-300m",
                displayName = "Omnilingual 300M",
                engineType = EngineType.SHERPA_ONNX,
                sherpaModelType = SherpaModelType.OMNILINGUAL_CTC,
                parameterCount = "300M",
                sizeOnDisk = "~365 MB",
                description = "1,600+ languages. Facebook MMS CTC model, int8 quantized.",
                languages = "1,600+ languages",
                files = listOf(
                    ModelFile("${OMNILINGUAL_300M_BASE_URL}model.int8.onnx", "model.int8.onnx"),
                    ModelFile("${OMNILINGUAL_300M_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- Zipformer Streaming (sherpa-onnx) --
            ModelInfo(
                id = "zipformer-20m",
                displayName = "Zipformer Streaming",
                engineType = EngineType.SHERPA_ONNX_STREAMING,
                sherpaModelType = SherpaModelType.ZIPFORMER_TRANSDUCER,
                parameterCount = "20M",
                sizeOnDisk = "~73 MB",
                description = "Real-time streaming English model with improved accuracy.",
                languages = "English",
                files = listOf(
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                    ),
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                        "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
                    ),
                    ModelFile(
                        "${ZIPFORMER_EN_BASE_URL}joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                    ),
                    ModelFile("${ZIPFORMER_EN_BASE_URL}tokens.txt", "tokens.txt"),
                )
            ),
            // -- whisper.cpp (GGML, direct inference matching iOS CactusKit) --
            ModelInfo(
                id = "cactus-whisper-tiny",
                displayName = "Whisper Tiny (whisper.cpp)",
                engineType = EngineType.CACTUS,
                cactusModelType = CactusModelType.WHISPER,
                parameterCount = "39M",
                sizeOnDisk = "~31 MB",
                description = "Whisper Tiny via whisper.cpp GGML. Q5_1 quantized, matching iOS.",
                files = listOf(
                    ModelFile("${WHISPER_GGML_BASE_URL}ggml-tiny-q5_1.bin", "ggml-tiny-q5_1.bin")
                )
            ),
            // -- Qwen ASR (Pure C/NEON) --
            ModelInfo(
                id = "qwen3-asr-0.6b",
                displayName = "Qwen3 ASR 0.6B (CPU)",
                engineType = EngineType.QWEN_ASR,
                parameterCount = "600M",
                sizeOnDisk = "~1.8 GB",
                description = "30 languages. Pure C inference with NEON. ~2.7 GB RAM peak.",
                languages = "30 languages",
                files = listOf(
                    ModelFile("${QWEN_ASR_0_6B_BASE_URL}config.json", "config.json"),
                    ModelFile("${QWEN_ASR_0_6B_BASE_URL}generation_config.json", "generation_config.json"),
                    ModelFile("${QWEN_ASR_0_6B_BASE_URL}model.safetensors", "model.safetensors"),
                    ModelFile("${QWEN_ASR_0_6B_BASE_URL}vocab.json", "vocab.json"),
                    ModelFile("${QWEN_ASR_0_6B_BASE_URL}merges.txt", "merges.txt"),
                )
            ),
            // -- Qwen ASR (ONNX Runtime, INT8 quantized) --
            ModelInfo(
                id = "qwen3-asr-0.6b-onnx",
                displayName = "Qwen3 ASR 0.6B (ONNX)",
                engineType = EngineType.QWEN_ONNX,
                parameterCount = "600M",
                sizeOnDisk = "~1.9 GB",
                description = "30 languages. INT8 quantized ONNX. Uses ORT from sherpa-onnx.",
                languages = "30 languages",
                files = listOf(
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}encoder.int8.onnx", "encoder.int8.onnx"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}decoder_prefill.int8.onnx", "decoder_prefill.int8.onnx"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}decoder_decode.int8.onnx", "decoder_decode.int8.onnx"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}embed_tokens.fp16.npy", "embed_tokens.fp16.npy"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}vocab.json", "vocab.json"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}config.json", "config.json"),
                    ModelFile("${QWEN_ASR_ONNX_BASE_URL}tokens.json", "tokens.json"),
                )
            ),
            // -- Android Native Speech --
            ModelInfo(
                id = "android-speech-offline",
                displayName = "Android Speech (Offline)",
                engineType = EngineType.ANDROID_SPEECH,
                parameterCount = "System",
                sizeOnDisk = "Built-in",
                description = "Android's built-in on-device speech recognition. No download. API 33+ for file transcription.",
                languages = "50+ languages",
                files = emptyList()
            ),
            ModelInfo(
                id = "android-speech-online",
                displayName = "Android Speech (Online)",
                engineType = EngineType.ANDROID_SPEECH,
                parameterCount = "System",
                sizeOnDisk = "Built-in",
                description = "Android's cloud speech recognition via Google. Requires internet. API 33+ for file transcription.",
                languages = "100+ languages",
                files = emptyList()
            ),
        )

        val defaultModel = availableModels.first { it.id == "sensevoice-small" }

        fun findByIdOrLegacy(id: String?): ModelInfo? {
            if (id.isNullOrBlank()) return null
            val canonicalId = LEGACY_MODEL_ID_MAP[id] ?: id
            return availableModels.firstOrNull { it.id == canonicalId }
        }

        /** Group models by engine for UI display. */
        val modelsByEngine: Map<EngineType, List<ModelInfo>>
            get() = availableModels.groupBy { it.engineType }
    }
}
