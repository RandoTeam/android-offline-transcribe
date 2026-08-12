package com.voiceping.offlinetranscription.service

import android.os.SystemClock
import android.util.Log
import com.voiceping.offlinetranscription.model.AppError
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.SherpaModelType
import com.voiceping.offlinetranscription.util.TextNormalizationUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlin.math.sqrt

/**
 * Coordinates real-time transcription: inference loop, VAD, chunking, and text assembly.
 *
 * Extracted from WhisperEngine to isolate inference loop state and logic.
 * Owns buffer tracking, silence detection, and chunk management internally.
 * Delegates observable state updates back to WhisperEngine via callback methods.
 */
class TranscriptionCoordinator(
    private val engine: WhisperEngine
) {
    // MARK: - Internal State

    var transcriptionJob: Job? = null
        private set
    private var lastBufferSize: Int = 0
    private val inferenceMutex = Mutex()
    private var recordingStartElapsedMs: Long = 0L
    private var hasCompletedFirstInference: Boolean = false
    private var realtimeInferenceCount: Long = 0
    private var movingAverageInferenceSeconds: Double = 0.0

    // MARK: - Constants

    companion object {
        private const val MAX_BUFFER_SAMPLES = AudioConstants.SAMPLE_RATE * 300 // 5 minutes
        // Low-latency tuning for live mic mode (file transcription path is unaffected).
        private const val DEFAULT_MIN_NEW_AUDIO_SECONDS = 0.45f
        private const val DEFAULT_OFFLINE_REALTIME_CHUNK_SECONDS = 2.8f
        private const val DEFAULT_INITIAL_MIN_NEW_AUDIO_SECONDS = 0.20f
        // sherpa-onnx offline models (SenseVoice, Moonshine): fast cadence with
        // CPU-aware delay. Smaller 3.5s chunks keep each inference quick (~0.2s),
        // and the duty-cycle guard prevents CPU starvation. Matches iOS timing.
        private const val SHERPA_OFFLINE_REALTIME_CHUNK_SECONDS = 3.5f
        private const val SHERPA_INITIAL_MIN_NEW_AUDIO_SECONDS = 0.35f
        private const val SHERPA_MIN_NEW_AUDIO_SECONDS = 0.7f
        // Cactus requires at least ~1s of 16kHz mono PCM per inference request.
        private const val CACTUS_INITIAL_MIN_NEW_AUDIO_SECONDS = 1.0f
        private const val CACTUS_MIN_NEW_AUDIO_SECONDS = 1.0f
        // Omnilingual CTC is significantly heavier than other sherpa offline models.
        // Keep realtime decode windows smaller and cadence slower to avoid UI starvation.
        private const val OMNILINGUAL_REALTIME_CHUNK_SECONDS = 4.0f
        private const val OMNILINGUAL_INITIAL_MIN_NEW_AUDIO_SECONDS = 3.0f
        private const val OMNILINGUAL_MIN_NEW_AUDIO_SECONDS = 3.0f
        private const val SHERPA_MIN_INFERENCE_RMS = 0.012f
        private const val INITIAL_VAD_BYPASS_SECONDS = 1.0f
        private const val TARGET_INFERENCE_DUTY_CYCLE = 0.24f
        private const val MAX_CPU_PROTECT_DELAY_SECONDS = 1.6f
        private const val INFERENCE_EMA_ALPHA = 0.20
        private const val DIAGNOSTIC_LOG_INTERVAL = 5L
        // Low threshold to handle quiet microphones.
        private const val SILENCE_THRESHOLD = 0.0015f
        private const val VAD_PREROLL_SECONDS = 0.6f
        private const val NO_SIGNAL_TIMEOUT_SECONDS = 8.0
        private const val SIGNAL_ENERGY_THRESHOLD = 0.005f
    }

    // MARK: - Chunk Manager

    fun createChunkManagerForModel(model: ModelInfo): StreamingChunkManager {
        val chunkSeconds = when (model.engineType) {
            EngineType.SHERPA_ONNX, EngineType.CACTUS -> if (isOmnilingualModel(model)) {
                OMNILINGUAL_REALTIME_CHUNK_SECONDS
            } else {
                SHERPA_OFFLINE_REALTIME_CHUNK_SECONDS
            }
            else -> DEFAULT_OFFLINE_REALTIME_CHUNK_SECONDS
        }
        val minNewAudioSeconds = when (model.engineType) {
            EngineType.SHERPA_ONNX -> if (isOmnilingualModel(model)) {
                OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
            } else {
                SHERPA_MIN_NEW_AUDIO_SECONDS
            }
            EngineType.CACTUS -> CACTUS_MIN_NEW_AUDIO_SECONDS
            else -> DEFAULT_MIN_NEW_AUDIO_SECONDS
        }
        return StreamingChunkManager(
            chunkSeconds = chunkSeconds,
            sampleRate = AudioRecorder.SAMPLE_RATE,
            minNewAudioSeconds = minNewAudioSeconds
        )
    }

    // MARK: - Model Type Checks

    private fun isFastOfflineModel(): Boolean {
        val type = engine.selectedModel.value.engineType
        return type == EngineType.SHERPA_ONNX || type == EngineType.CACTUS
    }

    private fun isCactusModel(model: ModelInfo = engine.selectedModel.value): Boolean {
        return model.engineType == EngineType.CACTUS
    }

    private fun isOmnilingualModel(model: ModelInfo = engine.selectedModel.value): Boolean {
        return model.engineType == EngineType.SHERPA_ONNX &&
            model.sherpaModelType == SherpaModelType.OMNILINGUAL_CTC
    }

    // MARK: - Loop Lifecycle

    fun startLoop(scope: CoroutineScope, sessionToken: Long, asrEngine: AsrEngine) {
        cancelTranscriptionJob()
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        hasCompletedFirstInference = false
        realtimeInferenceCount = 0
        val chunkSeconds = engine.chunkManager.chunkSamples.toFloat() / AudioRecorder.SAMPLE_RATE
        val baseGate = when {
            isOmnilingualModel() -> OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
            isCactusModel() -> CACTUS_MIN_NEW_AUDIO_SECONDS
            isFastOfflineModel() -> SHERPA_MIN_NEW_AUDIO_SECONDS
            else -> DEFAULT_MIN_NEW_AUDIO_SECONDS
        }
        val initialGate = initialRealtimeDelayForModel()
        Log.i(
            "TranscriptionCoordinator",
            "realtime config: chunkSeconds=$chunkSeconds baseGate=${baseGate}s initialGate=${initialGate}s targetDuty=$TARGET_INFERENCE_DUTY_CYCLE"
        )
        transcriptionJob = scope.launch(Dispatchers.Default) {
            if (asrEngine.isStreaming) {
                streamingLoop(asrEngine, sessionToken)
            } else {
                realtimeLoop(asrEngine, sessionToken)
            }
        }
    }

    fun cancelTranscriptionJob() {
        transcriptionJob?.cancel()
        transcriptionJob = null
    }

    suspend fun cancelTranscriptionJobAndWait() {
        transcriptionJob?.cancelAndJoin()
        transcriptionJob = null
    }

    // MARK: - Realtime Inference Loop

    private suspend fun realtimeLoop(asrEngine: AsrEngine, sessionToken: Long) {
        try {
            while (engine.isSessionActive(sessionToken)) {
                try {
                    transcribeCurrentBuffer(asrEngine, sessionToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!engine.isSessionActive(sessionToken)) return
                    engine.onTranscriptionError(AppError.TranscriptionFailed(e))
                    return
                }
            }
        } finally {
            // Final transcription pass for any remaining buffered audio.
            // stopRecording() handles streaming engines; this handles offline engines.
            // NOTE: We bypass isSessionActive() here because stopRecording()
            // invalidates the session before cancelling the loop. The final
            // pass must still run to capture any trailing audio.
            if (!asrEngine.isStreaming && asrEngine.isLoaded) {
                try {
                    withContext(NonCancellable) {
                        val currentCount = engine.audioRecorder.sampleCount
                        if (currentCount > lastBufferSize) {
                            transcribeFinalBuffer(asrEngine, currentCount)
                        }
                        // Commit any remaining hypothesis text as confirmed.
                        // This runs on the coroutine thread (not UI thread) to
                        // avoid racing with the loop's last iteration.
                        engine.chunkManager.finalizeCurrentChunk()
                        engine.updateConfirmedText(engine.chunkManager.confirmedText)
                        engine.updateHypothesisText("")
                    }
                } catch (_: Throwable) {
                    // Best-effort final pass
                }
            }
            transcriptionJob = null
            if (engine.isSessionActive(sessionToken)) {
                engine.audioRecorder.stopRecording()
                engine.transitionTo(SessionState.Idle)
            }
        }
    }

    /** Streaming transcription loop — feeds audio incrementally and polls for results. */
    private suspend fun streamingLoop(asrEngine: AsrEngine, sessionToken: Long) {
        var streamFeedCount = 0L
        var lastLoggedStreamingText = ""
        try {
            while (engine.isSessionActive(sessionToken)) {
                try {
                    // Feed new audio to the streaming engine
                    val currentCount = engine.audioRecorder.sampleCount
                    if (currentCount > lastBufferSize) {
                        val newSamples = engine.audioRecorder.samplesRange(lastBufferSize, currentCount)
                        if (newSamples.isNotEmpty()) {
                            asrEngine.feedAudio(newSamples)
                            lastBufferSize = currentCount
                            streamFeedCount += 1
                            if (streamFeedCount <= 3 || streamFeedCount % DIAGNOSTIC_LOG_INTERVAL == 0L) {
                                val fedSec = newSamples.size.toFloat() / AudioRecorder.SAMPLE_RATE
                                val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                                Log.i(
                                    "TranscriptionCoordinator",
                                    "stream feed #$streamFeedCount +${"%.2f".format(fedSec)}s total=${"%.2f".format(totalSec)}s"
                                )
                            }
                        }
                    }

                    // Poll streaming result
                    val result = asrEngine.getStreamingResult()
                    if (result != null) {
                        val normalized = normalizeDisplayText(result.text)
                        engine.updateHypothesisText(normalized)
                        engine.scheduleTranslationUpdate()
                        if (normalized.isNotBlank() && normalized != lastLoggedStreamingText) {
                            lastLoggedStreamingText = normalized
                            val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                            Log.i(
                                "TranscriptionCoordinator",
                                "stream hypothesis @${"%.2f".format(totalSec)}s chars=${normalized.length}"
                            )
                        }
                    }

                    // Endpoint detected → finalize this utterance
                    if (asrEngine.isEndpointDetected()) {
                        val totalSec = currentCount.toFloat() / AudioRecorder.SAMPLE_RATE
                        Log.i("TranscriptionCoordinator", "stream endpoint detected @${"%.2f".format(totalSec)}s")
                        val finalResult = asrEngine.getStreamingResult()
                        if (finalResult != null && finalResult.text.isNotBlank()) {
                            engine.chunkManager.confirmedSegments.add(finalResult)
                            val rendered = engine.chunkManager.renderSegmentsText(engine.chunkManager.confirmedSegments)
                            engine.updateConfirmedText(rendered)
                            engine.chunkManager.confirmedText = rendered
                        }
                        engine.updateHypothesisText("")
                        engine.scheduleTranslationUpdate()
                        asrEngine.resetStreamingState()
                    }

                    val streamingSafeTrimSample = (lastBufferSize - AudioRecorder.SAMPLE_RATE * 30)
                        .coerceAtLeast(0)
                    trimRecorderBufferIfNeeded(streamingSafeTrimSample)

                    delay(100) // 100ms polling interval (matches iOS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!engine.isSessionActive(sessionToken)) return
                    engine.onTranscriptionError(AppError.TranscriptionFailed(e))
                    return
                }
            }
        } finally {
            // Feed any remaining audio and drain the streaming decoder.
            // stopRecording() already handles this for normal stop flow,
            // but this covers cancellation and error paths too.
            try {
                val currentCount = engine.audioRecorder.sampleCount
                if (currentCount > lastBufferSize) {
                    val remaining = engine.audioRecorder.samplesRange(lastBufferSize, currentCount)
                    if (remaining.isNotEmpty()) {
                        asrEngine.feedAudio(remaining)
                        lastBufferSize = currentCount
                    }
                }
                val finalResult = asrEngine.drainFinalAudio()
                if (finalResult != null && finalResult.text.isNotBlank()) {
                    engine.chunkManager.confirmedSegments.add(finalResult)
                    engine.updateConfirmedText(engine.chunkManager.renderSegmentsText(engine.chunkManager.confirmedSegments))
                    engine.updateHypothesisText("")
                    engine.chunkManager.confirmedText = engine.confirmedText.value
                    engine.scheduleTranslationUpdate()
                }
            } catch (_: Throwable) {
                // Best-effort drain
            }
            transcriptionJob = null
            if (engine.isSessionActive(sessionToken)) {
                engine.audioRecorder.stopRecording()
                engine.transitionTo(SessionState.Idle)
            }
        }
    }

    // MARK: - Per-Frame Transcription

    private suspend fun transcribeCurrentBuffer(asrEngine: AsrEngine, sessionToken: Long) {
        if (!engine.isSessionActive(sessionToken)) return

        // No-signal detection
        if (engine.audioRecorder.bufferSeconds >= NO_SIGNAL_TIMEOUT_SECONDS &&
            engine.audioRecorder.maxRecentEnergy < SIGNAL_ENERGY_THRESHOLD &&
            engine.confirmedText.value.isBlank() &&
            engine.hypothesisText.value.isBlank()
        ) {
            engine.onNoSignalDetected()
            return
        }

        val currentBufferSize = engine.audioRecorder.sampleCount
        val nextBufferSize = currentBufferSize - lastBufferSize
        val nextBufferSeconds = nextBufferSize.toFloat() / AudioRecorder.SAMPLE_RATE
        val bufferSeconds = currentBufferSize.toFloat() / AudioRecorder.SAMPLE_RATE

        val initialPhase = !hasCompletedFirstInference
        val baseDelay = if (initialPhase) {
            initialRealtimeDelayForModel()
        } else {
            adaptiveRealtimeDelayForModel()
        }
        val effectiveDelay = if (initialPhase) {
            baseDelay
        } else {
            computeCpuAwareDelay(baseDelay)
        }
        if (nextBufferSeconds < effectiveDelay) {
            delay(100)
            return
        }

        // VAD check — bypass for system playback (continuous audio, not voice-triggered)
        if (engine.useVAD.value && engine.audioInputMode.value != AudioInputMode.SYSTEM_PLAYBACK) {
            val vadBypassSamples = (AudioRecorder.SAMPLE_RATE * INITIAL_VAD_BYPASS_SECONDS).toInt()
            val bypassVadDuringStartup = initialPhase && currentBufferSize <= vadBypassSamples
            if (!bypassVadDuringStartup) {
                val energy = engine.audioRecorder.relativeEnergy
                if (energy.isNotEmpty()) {
                    val recentEnergy = energy.takeLast(10)
                    val avgEnergy = recentEnergy.sum() / recentEnergy.size
                    val peakEnergy = recentEnergy.maxOrNull() ?: 0f
                    val hasVoice = peakEnergy >= SILENCE_THRESHOLD ||
                        avgEnergy >= SILENCE_THRESHOLD * 0.5f

                    if (!hasVoice) {
                        engine.chunkManager.consecutiveSilentWindows += 1
                        if (engine.chunkManager.consecutiveSilentWindows <= 2 ||
                            engine.chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                        ) {
                            Log.i(
                                "TranscriptionCoordinator",
                                "rt VAD skip: silentWindows=${engine.chunkManager.consecutiveSilentWindows} buffer=${"%.2f".format(bufferSeconds)}s"
                            )
                        }
                        keepVadPreroll(currentBufferSize)
                        return
                    } else {
                        engine.chunkManager.consecutiveSilentWindows = 0
                    }
                } else {
                    engine.chunkManager.consecutiveSilentWindows += 1
                    if (engine.chunkManager.consecutiveSilentWindows <= 2 ||
                        engine.chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                    ) {
                        Log.i(
                            "TranscriptionCoordinator",
                            "rt VAD skip(no-energy): silentWindows=${engine.chunkManager.consecutiveSilentWindows} buffer=${"%.2f".format(bufferSeconds)}s"
                        )
                    }
                    keepVadPreroll(currentBufferSize)
                    return
                }
            }
        }

        // Update energy visualization
        engine.updateBufferEnergy(engine.audioRecorder.relativeEnergy)
        engine.updateBufferSeconds(engine.audioRecorder.bufferSeconds)

        // Chunk-based windowing via StreamingChunkManager
        val slice = engine.chunkManager.computeSlice(currentBufferSize) ?: return

        val audioSamples = engine.audioRecorder.samplesRange(slice.startSample, slice.endSample)
        if (audioSamples.isEmpty()) return
        val sliceStartSec = slice.startSample.toFloat() / AudioRecorder.SAMPLE_RATE
        val sliceEndSec = slice.endSample.toFloat() / AudioRecorder.SAMPLE_RATE
        if (isFastOfflineModel()) {
            val sliceRms = computeRms(audioSamples)
            if (sliceRms < SHERPA_MIN_INFERENCE_RMS) {
                if (engine.chunkManager.consecutiveSilentWindows <= 2 ||
                    engine.chunkManager.consecutiveSilentWindows % DIAGNOSTIC_LOG_INTERVAL.toInt() == 0
                ) {
                    Log.i(
                        "TranscriptionCoordinator",
                        "rt RMS skip: rms=${"%.4f".format(sliceRms)} < ${"%.4f".format(SHERPA_MIN_INFERENCE_RMS)} slice=[${"%.2f".format(sliceStartSec)},${"%.2f".format(sliceEndSec)}]"
                    )
                }
                delay(500)
                return
            }
        }
        lastBufferSize = currentBufferSize

        val startTime = System.nanoTime()
        val numThreads = computeInferenceThreads()
        if (!inferenceMutex.tryLock()) {
            return
        }
        val newSegments = try {
            asrEngine.transcribe(audioSamples, numThreads, "auto")
        } finally {
            inferenceMutex.unlock()
        }
        realtimeInferenceCount += 1
        val inferenceIndex = realtimeInferenceCount
        if (!hasCompletedFirstInference) {
            val firstInferenceMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs
            Log.i(
                "TranscriptionCoordinator",
                "First inference completed in ${firstInferenceMs}ms (buffer=${"%.2f".format(bufferSeconds)}s, slice=${"%.2f".format(sliceEndSec - sliceStartSec)}s)"
            )
        }
        hasCompletedFirstInference = true
        if (!engine.isSessionActive(sessionToken)) return

        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
        if (elapsed > 0.0) {
            movingAverageInferenceSeconds = if (movingAverageInferenceSeconds <= 0.0) {
                elapsed
            } else {
                movingAverageInferenceSeconds + INFERENCE_EMA_ALPHA * (elapsed - movingAverageInferenceSeconds)
            }
        }
        val sliceDurationSec = audioSamples.size.toFloat() / AudioRecorder.SAMPLE_RATE
        val totalWords = newSegments.sumOf { it.text.split(" ").size }
        if (elapsed > 0 && totalWords > 0) {
            engine.updateTokensPerSecond(totalWords / elapsed)
        }
        val confirmedBeforeSec = engine.chunkManager.lastConfirmedSegmentEndMs / 1000f

        if (newSegments.isNotEmpty()) {
            engine.chunkManager.consecutiveSilentWindows = 0
            val lang = WhisperEngine.normalizeLanguageCode(newSegments.firstOrNull()?.detectedLanguage)
            if (lang != null && lang != engine.detectedLanguage.value) {
                engine.updateDetectedLanguage(lang)
                engine.applyDetectedLanguageToTranslation(lang)
            }
        }
        engine.chunkManager.processTranscriptionResult(newSegments, slice.sliceOffsetMs)
        engine.updateConfirmedText(engine.chunkManager.confirmedText)
        engine.updateHypothesisText(engine.chunkManager.hypothesisText)
        engine.scheduleTranslationUpdate()
        val confirmedAfterSec = engine.chunkManager.lastConfirmedSegmentEndMs / 1000f
        val lagAfterSec = (bufferSeconds - confirmedAfterSec).coerceAtLeast(0f)
        val previewText = newSegments.firstOrNull()
            ?.text
            ?.let { normalizeDisplayText(it) }
            ?.take(64)
            .orEmpty()
        val ratio = if (elapsed > 0.0) sliceDurationSec / elapsed else 0.0f
        val shouldLogDetailed =
            inferenceIndex <= 4L ||
                inferenceIndex % DIAGNOSTIC_LOG_INTERVAL == 0L ||
                elapsed >= 0.35 ||
                sliceDurationSec >= 4.0f ||
                lagAfterSec >= 2.0f
        if (shouldLogDetailed) {
            Log.i(
                "TranscriptionCoordinator",
                "rt chunk #$inferenceIndex buf=${"%.2f".format(bufferSeconds)}s new=${"%.2f".format(nextBufferSeconds)}s gate=${"%.2f".format(effectiveDelay)}s base=${"%.2f".format(baseDelay)}s avgInfer=${"%.3f".format(movingAverageInferenceSeconds)}s cpu=${"%.0f".format(engine.cpuPercent.value)}% slice=[${"%.2f".format(sliceStartSec)},${"%.2f".format(sliceEndSec)}] dur=${"%.2f".format(sliceDurationSec)}s infer=${"%.3f".format(elapsed)}s ratio=${"%.1f".format(ratio)}x seg=${newSegments.size} words=$totalWords conf=${"%.2f".format(confirmedBeforeSec)}s->${"%.2f".format(confirmedAfterSec)}s lag=${"%.2f".format(lagAfterSec)}s preview='${previewText}'"
            )
        }

        val safeTrimSample = ((engine.chunkManager.lastConfirmedSegmentEndMs * AudioRecorder.SAMPLE_RATE) / 1000)
            .toInt()
        trimRecorderBufferIfNeeded(safeTrimSample)
    }

    /**
     * Final-pass transcription for offline engines, called from the realtimeLoop
     * finally block. Unlike [transcribeCurrentBuffer], this skips the session-active
     * guard (the session is already invalidated by stopRecording) and skips VAD/delay
     * logic since we just want to flush whatever audio remains.
     */
    private suspend fun transcribeFinalBuffer(asrEngine: AsrEngine, currentBufferSize: Int) {
        val slice = engine.chunkManager.computeSlice(currentBufferSize) ?: return
        val audioSamples = engine.audioRecorder.samplesRange(slice.startSample, slice.endSample)
        if (audioSamples.isEmpty()) return

        lastBufferSize = currentBufferSize
        val numThreads = computeInferenceThreads()
        val newSegments = asrEngine.transcribe(audioSamples, numThreads, "auto")
        if (newSegments.isNotEmpty()) {
            engine.chunkManager.processTranscriptionResult(newSegments, slice.sliceOffsetMs)
            engine.updateConfirmedText(engine.chunkManager.confirmedText)
            engine.updateHypothesisText("")
            Log.i("TranscriptionCoordinator", "final pass: ${newSegments.size} segments, text='${engine.chunkManager.confirmedText.take(80)}'")
        }
    }

    // MARK: - Streaming Drain

    fun drainFinalStreamingAudioIfNeeded() {
        val asrEngine = engine.currentEngine
        if (asrEngine == null || !asrEngine.isLoaded || !asrEngine.isStreaming) return

        val currentCount = engine.audioRecorder.sampleCount
        if (currentCount > lastBufferSize) {
            val remaining = engine.audioRecorder.samplesRange(lastBufferSize, currentCount)
            if (remaining.isNotEmpty()) {
                asrEngine.feedAudio(remaining)
                lastBufferSize = currentCount
            }
        }

        val finalResult = asrEngine.drainFinalAudio()
        if (finalResult == null || finalResult.text.isBlank()) return

        engine.chunkManager.confirmedSegments.add(finalResult)
        engine.updateConfirmedText(engine.chunkManager.renderSegmentsText(engine.chunkManager.confirmedSegments))
        engine.updateHypothesisText("")
        engine.chunkManager.confirmedText = engine.confirmedText.value
        engine.scheduleTranslationUpdate()
    }

    // MARK: - Delay & VAD

    private fun initialRealtimeDelayForModel(): Float {
        if (isOmnilingualModel()) {
            return OMNILINGUAL_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
        if (isCactusModel()) {
            return CACTUS_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
        return if (isFastOfflineModel()) {
            SHERPA_INITIAL_MIN_NEW_AUDIO_SECONDS
        } else {
            DEFAULT_INITIAL_MIN_NEW_AUDIO_SECONDS
        }
    }

    private fun adaptiveRealtimeDelayForModel(): Float {
        if (!isFastOfflineModel()) return engine.chunkManager.adaptiveDelay()
        if (isOmnilingualModel()) {
            return OMNILINGUAL_MIN_NEW_AUDIO_SECONDS
        }
        if (isCactusModel()) {
            return CACTUS_MIN_NEW_AUDIO_SECONDS
        }
        // SenseVoice/Moonshine: use base delay (CPU-aware delay applied by caller)
        return SHERPA_MIN_NEW_AUDIO_SECONDS
    }

    private fun keepVadPreroll(currentBufferSize: Int) {
        val preRollSamples = (AudioRecorder.SAMPLE_RATE * VAD_PREROLL_SECONDS).toInt()
        lastBufferSize = (currentBufferSize - preRollSamples).coerceAtLeast(0)
    }

    private fun computeCpuAwareDelay(baseDelay: Float): Float {
        val avg = movingAverageInferenceSeconds
        if (avg <= 0.0) return baseDelay
        val budgetDelay = (avg / TARGET_INFERENCE_DUTY_CYCLE).toFloat()
        return maxOf(baseDelay, budgetDelay.coerceAtMost(MAX_CPU_PROTECT_DELAY_SECONDS))
    }

    // MARK: - Audio Analysis

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    // MARK: - Buffer Management

    private fun trimRecorderBufferIfNeeded(safeTrimBeforeAbsoluteSample: Int) {
        val currentAbsoluteSamples = engine.audioRecorder.sampleCount
        if (currentAbsoluteSamples <= MAX_BUFFER_SAMPLES) return

        val targetKeepSamples = MAX_BUFFER_SAMPLES / 2
        val desiredDropBefore = currentAbsoluteSamples - targetKeepSamples
        val dropBefore = minOf(
            desiredDropBefore,
            safeTrimBeforeAbsoluteSample.coerceAtLeast(0)
        )
        if (dropBefore <= 0) return

        val dropped = engine.audioRecorder.discardSamples(beforeAbsoluteIndex = dropBefore)
        if (dropped > 0) {
            Log.i(
                "TranscriptionCoordinator",
                "Trimmed $dropped old mic samples (safeBefore=$safeTrimBeforeAbsoluteSample, current=$currentAbsoluteSamples)"
            )
        }
    }

    // MARK: - Text Normalization

    fun normalizeDisplayText(text: String): String = TextNormalizationUtils.normalizeText(text)

    // MARK: - Utilities

    private fun computeInferenceThreads(): Int {
        return engine.inferenceThreadCount()
    }

    // MARK: - State Reset

    fun reset() {
        cancelTranscriptionJob()
        lastBufferSize = 0
        recordingStartElapsedMs = 0L
        hasCompletedFirstInference = false
        realtimeInferenceCount = 0
        movingAverageInferenceSeconds = 0.0
    }
}
