package com.voiceping.offlinetranscription.service

import android.util.Log
import com.voiceping.offlinetranscription.model.ExecutionProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ASR engine backed by qwen-asr pure C library via JNI.
 * Expects a model directory containing config.json, model.safetensors, vocab.json, merges.txt.
 *
 * All access to contextPtr is guarded by [lock] so that release()
 * cannot free the native context while transcribe() is in-flight.
 */
class QwenASREngine : AsrEngine {
    companion object {
        private const val TAG = "QwenASREngine"

        /** ISO 639-1 → Qwen language names. */
        private val LANGUAGE_MAP = mapOf(
            "en" to "English", "zh" to "Chinese", "yue" to "Cantonese", "ar" to "Arabic",
            "de" to "German", "fr" to "French", "es" to "Spanish", "pt" to "Portuguese",
            "id" to "Indonesian", "it" to "Italian", "ko" to "Korean", "ru" to "Russian",
            "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese", "tr" to "Turkish",
            "hi" to "Hindi", "ms" to "Malay", "nl" to "Dutch", "sv" to "Swedish",
            "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech",
            "fil" to "Filipino", "fa" to "Persian", "el" to "Greek", "ro" to "Romanian",
            "hu" to "Hungarian", "mk" to "Macedonian",
        )

        private fun recommendedThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores <= 2 -> 1
                cores <= 4 -> 2
                else -> 4
            }
        }
    }

    private var contextPtr: Long = 0
    @Volatile
    private var loaded: Boolean = false
    private val lock = ReentrantLock()

    override val isLoaded: Boolean get() = loaded
    override val executionProviderStatus: ExecutionProviderStatus
        get() = ExecutionProviderStatus(diagnostic = "Native C/NEON CPU backend.")

    override suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    if (contextPtr != 0L) {
                        QwenASRLib.freeContext(contextPtr)
                        contextPtr = 0
                        loaded = false
                    }
                    val threads = recommendedThreads()
                    Log.i(TAG, "Loading Qwen ASR from $modelPath with $threads threads")
                    val ptr = QwenASRLib.initContext(modelPath, threads)
                    contextPtr = ptr
                    loaded = ptr != 0L
                    if (loaded) {
                        Log.i(TAG, "Qwen ASR loaded successfully")
                    } else {
                        Log.e(TAG, "Failed to load Qwen ASR (initContext returned 0)")
                    }
                    ptr != 0L
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load Qwen ASR from $modelPath", e)
                    contextPtr = 0L
                    loaded = false
                    false
                }
            }
        }
    }

    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (contextPtr == 0L) return@withContext emptyList()

                try {
                    // Map ISO code to Qwen language name, or null for auto-detect
                    val qwenLang = if (language == "auto") null else LANGUAGE_MAP[language]
                    QwenASRLib.setLanguage(contextPtr, qwenLang)

                    val text = QwenASRLib.transcribe(contextPtr, audioSamples)
                    val inferenceMs = QwenASRLib.getLastInferenceMs(contextPtr)
                    Log.i(TAG, "Transcribed ${audioSamples.size} samples in ${inferenceMs.toLong()}ms")

                    if (text.isBlank()) return@withContext emptyList()

                    val durationMs = (audioSamples.size.toLong() * 1000) / 16000
                    listOf(
                        TranscriptionSegment(
                            text = text.trim(),
                            startMs = 0,
                            endMs = durationMs
                        )
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Qwen ASR transcribe failed", e)
                    emptyList()
                }
            }
        }
    }

    override fun release() {
        loaded = false
        lock.withLock {
            if (contextPtr != 0L) {
                try {
                    QwenASRLib.freeContext(contextPtr)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to free Qwen ASR context", e)
                }
                contextPtr = 0
                loaded = false
            }
        }
    }
}
