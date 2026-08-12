package com.voiceping.offlinetranscription.service

import android.util.Log
import com.voiceping.offlinetranscription.model.ExecutionProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ASR engine using Qwen3-ASR ONNX models via ONNX Runtime.
 * Uses INT8 quantized encoder + decoder ONNX models for inference.
 * ORT library is loaded at runtime from sherpa-onnx AAR's bundled libonnxruntime.so.
 *
 * All access to contextPtr is guarded by [lock] so that release()
 * cannot free the native context while transcribe() is in-flight.
 */
class QwenOnnxEngine : AsrEngine {
    companion object {
        private const val TAG = "QwenOnnxEngine"
    }

    private var contextPtr: Long = 0
    @Volatile
    private var loaded: Boolean = false
    private val lock = ReentrantLock()

    override val isLoaded: Boolean get() = loaded
    override val executionProviderStatus: ExecutionProviderStatus
        get() = ExecutionProviderStatus(diagnostic = "Bundled ONNX Runtime CPU path; no QNN/LiteRT delegate is loaded.")

    override suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    if (contextPtr != 0L) {
                        QwenOnnxLib.freeContext(contextPtr)
                        contextPtr = 0
                        loaded = false
                    }
                    Log.i(TAG, "Loading Qwen ONNX from $modelPath")
                    val ptr = QwenOnnxLib.initContext(modelPath)
                    contextPtr = ptr
                    loaded = ptr != 0L
                    if (loaded) {
                        Log.i(TAG, "Qwen ONNX loaded successfully")
                    } else {
                        Log.e(TAG, "Failed to load Qwen ONNX (initContext returned 0)")
                    }
                    ptr != 0L
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load Qwen ONNX from $modelPath", e)
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
                    val text = QwenOnnxLib.transcribe(contextPtr, audioSamples)
                    Log.i(TAG, "Transcribed ${audioSamples.size} samples")

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
                    Log.e(TAG, "Qwen ONNX transcribe failed", e)
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
                    QwenOnnxLib.freeContext(contextPtr)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to free Qwen ONNX context", e)
                }
                contextPtr = 0
                loaded = false
            }
        }
    }
}
