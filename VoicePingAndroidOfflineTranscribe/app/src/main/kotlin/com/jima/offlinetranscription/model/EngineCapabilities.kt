package com.voiceping.offlinetranscription.model

/**
 * A truthful, declarative description of an ASR backend.
 *
 * UI and session routing use this instead of branching on individual model ids.
 * A capability being false means the control must not be offered as functional.
 */
data class EngineCapabilities(
    val streaming: Boolean = false,
    val offlineTranscription: Boolean = true,
    val languageAutoDetection: Boolean = false,
    val forcedLanguage: Boolean = false,
    val systemPrompt: Boolean = false,
    val hotwords: Boolean = false,
    val timestamps: Boolean = true,
    val wordTimestamps: Boolean = false,
    val partialHypotheses: Boolean = false,
    val translation: Boolean = false,
    val supportedLanguages: Set<String> = emptySet(),
    val executionProviders: Set<ExecutionProvider> = setOf(ExecutionProvider.CPU),
    val configurableCpuThreads: Boolean = true,
    val quantization: String? = null,
    val systemAudioCompatible: Boolean = true,
) {
    companion object {
        val NONE = EngineCapabilities(offlineTranscription = false, timestamps = false)
    }
}

/** A requested provider is always reported separately from the provider actually used. */
enum class ExecutionProvider {
    CPU,
    GPU,
    QUALCOMM_HTP,
}

/** Runtime provider state used by diagnostics to avoid misleading acceleration claims. */
data class ExecutionProviderStatus(
    val requested: ExecutionProvider = ExecutionProvider.CPU,
    val actual: ExecutionProvider = ExecutionProvider.CPU,
    val diagnostic: String? = null,
)

fun capabilitiesFor(engineType: EngineType, modelId: String): EngineCapabilities = when (engineType) {
    EngineType.SHERPA_ONNX_STREAMING -> EngineCapabilities(
        streaming = true,
        languageAutoDetection = false,
        forcedLanguage = false,
        partialHypotheses = true,
        supportedLanguages = setOf("en"),
        quantization = "INT8",
    )
    EngineType.QWEN_ASR, EngineType.QWEN_ONNX -> EngineCapabilities(
        languageAutoDetection = true,
        forcedLanguage = true,
        timestamps = false,
        supportedLanguages = setOf("auto", "ru", "en"),
        quantization = if (engineType == EngineType.QWEN_ONNX) "INT8" else null,
    )
    EngineType.ANDROID_SPEECH -> EngineCapabilities(
        streaming = true,
        languageAutoDetection = true,
        forcedLanguage = true,
        partialHypotheses = true,
        supportedLanguages = setOf("system"),
        configurableCpuThreads = false,
    )
    EngineType.CACTUS -> EngineCapabilities(
        languageAutoDetection = true,
        forcedLanguage = true,
        timestamps = true,
        supportedLanguages = setOf("auto"),
        quantization = "GGML",
    )
    EngineType.SHERPA_ONNX -> EngineCapabilities(
        languageAutoDetection = true,
        forcedLanguage = true,
        translation = modelId.startsWith("whisper-"),
        supportedLanguages = setOf("auto"),
        quantization = "INT8",
    )
}
