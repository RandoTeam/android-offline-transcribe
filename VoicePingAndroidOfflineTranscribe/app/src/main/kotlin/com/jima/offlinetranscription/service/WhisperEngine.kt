package com.voiceping.offlinetranscription.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.voiceping.offlinetranscription.data.AppPreferences
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.AppError
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.ModelState
import com.voiceping.offlinetranscription.model.PerformanceProfile
import com.voiceping.offlinetranscription.model.ExecutionProviderStatus
import com.voiceping.offlinetranscription.util.TextNormalizationUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

data class TranscriptionSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val detectedLanguage: String? = null
)

/** Strict session state machine for the transcription pipeline. */
enum class SessionState {
    Idle,       // No recording, ready to start
    Recording,  // Mic active, transcription loop running
    Stopping,   // Stop requested, waiting for jobs to complete
    Error       // Error occurred, needs clearTranscription() to reset
}

class WhisperEngine(
    private val context: Context,
    private val preferences: AppPreferences
) {
    // NOTE: Cactus Android SDK hardcodes its own model cache under `filesDir/models/<slug>`.
    // Our app models must not share that namespace, otherwise Cactus will see our
    // whisper.cpp/sherpa directories as "downloaded" and skip its own download.
    private val modelsDir = File(context.filesDir, "asr_models")
    private val downloader = ModelDownloader(modelsDir)
    val audioRecorder = AudioRecorder(context)

    // Model state
    private val _modelState = MutableStateFlow(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _selectedModel = MutableStateFlow(ModelInfo.defaultModel)
    val selectedModel: StateFlow<ModelInfo> = _selectedModel.asStateFlow()
    private val _executionProviderStatus = MutableStateFlow(ExecutionProviderStatus())
    val executionProviderStatus: StateFlow<ExecutionProviderStatus> = _executionProviderStatus.asStateFlow()

    private val _performanceProfile = MutableStateFlow(PerformanceProfile.BALANCED)
    val performanceProfile: StateFlow<PerformanceProfile> = _performanceProfile.asStateFlow()

    // Session state machine
    private val _sessionState = MutableStateFlow(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Derived isRecording for backward compat
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Transcription output
    private val _confirmedText = MutableStateFlow("")
    val confirmedText: StateFlow<String> = _confirmedText.asStateFlow()

    private val _hypothesisText = MutableStateFlow("")
    val hypothesisText: StateFlow<String> = _hypothesisText.asStateFlow()

    private val _bufferEnergy = MutableStateFlow<List<Float>>(emptyList())
    val bufferEnergy: StateFlow<List<Float>> = _bufferEnergy.asStateFlow()

    private val _bufferSeconds = MutableStateFlow(0.0)
    val bufferSeconds: StateFlow<Double> = _bufferSeconds.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0.0)
    val tokensPerSecond: StateFlow<Double> = _tokensPerSecond.asStateFlow()

    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    private val _useVAD = MutableStateFlow(true)
    val useVAD: StateFlow<Boolean> = _useVAD.asStateFlow()

    private val _enableTimestamps = MutableStateFlow(true)
    val enableTimestamps: StateFlow<Boolean> = _enableTimestamps.asStateFlow()

    private val _audioInputMode = MutableStateFlow(AudioInputMode.MICROPHONE)
    val audioInputMode: StateFlow<AudioInputMode> = _audioInputMode.asStateFlow()

    private val _systemAudioCaptureReady = MutableStateFlow(false)
    val systemAudioCaptureReady: StateFlow<Boolean> = _systemAudioCaptureReady.asStateFlow()
    val isSystemAudioCaptureSupported: Boolean
        get() = audioRecorder.isSystemAudioCaptureSupported

    @Volatile var e2eLocked = false
    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled.asStateFlow()

    private val _translationSourceLanguageCode = MutableStateFlow("en")
    val translationSourceLanguageCode: StateFlow<String> = _translationSourceLanguageCode.asStateFlow()

    private val _translationTargetLanguageCode = MutableStateFlow("ja")
    val translationTargetLanguageCode: StateFlow<String> = _translationTargetLanguageCode.asStateFlow()

    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private val _translatedConfirmedText = MutableStateFlow("")
    val translatedConfirmedText: StateFlow<String> = _translatedConfirmedText.asStateFlow()

    private val _translatedHypothesisText = MutableStateFlow("")
    val translatedHypothesisText: StateFlow<String> = _translatedHypothesisText.asStateFlow()

    private val _translationWarning = MutableStateFlow<String?>(null)
    val translationWarning: StateFlow<String?> = _translationWarning.asStateFlow()

    val translationModelReady: StateFlow<Boolean> get() = mlKitTranslator.modelReady
    val translationDownloadStatus: StateFlow<String?> get() = mlKitTranslator.downloadStatus

    // System resource metrics (always sampled)
    private val systemMetrics = SystemMetrics()
    private val _cpuPercent = MutableStateFlow(0f)
    val cpuPercent: StateFlow<Float> = _cpuPercent.asStateFlow()
    private val _memoryMB = MutableStateFlow(0f)
    val memoryMB: StateFlow<Float> = _memoryMB.asStateFlow()

    // E2E evidence collection (delegated to E2ETestOrchestrator)
    val e2eOrchestrator by lazy { E2ETestOrchestrator(context, this) }
    val e2eResult: StateFlow<E2ETestResult?> get() = e2eOrchestrator.e2eResult

    // ASR engine abstraction
    private val setupMutex = Mutex()
    internal var currentEngine: AsrEngine? = null
    private var fileTranscriptionJob: Job? = null
    private var recordingJob: Job? = null
    private var energyJob: Job? = null
    private val recorderPrewarmMutex = Mutex()
    private val inferencePrewarmMutex = Mutex()
    val transcriptionCoordinator = TranscriptionCoordinator(this)
    internal var chunkManager = transcriptionCoordinator.createChunkManagerForModel(_selectedModel.value)
    private var prewarmedModelId: String? = null
    private val sessionToken = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mlKitTranslator = MlKitTranslator()
    private var translationJob: Job? = null
    private var lastTranslationInput: Pair<String, String>? = null

    private fun nextSessionToken(): Long = sessionToken.incrementAndGet()

    private fun invalidateSession() {
        sessionToken.incrementAndGet()
    }

    internal fun isSessionActive(token: Long): Boolean {
        return sessionToken.get() == token && _sessionState.value == SessionState.Recording
    }

    companion object {
        private const val INFERENCE_PREWARM_AUDIO_SECONDS = 0.5f
        fun normalizeLanguageCode(raw: String?): String? =
            TextNormalizationUtils.normalizeLanguageCode(raw)
    }

    // MARK: - Mutation Methods (for TranscriptionCoordinator)

    internal fun updateConfirmedText(text: String) { _confirmedText.value = text }
    internal fun updateHypothesisText(text: String) { _hypothesisText.value = text }
    internal fun updateDetectedLanguage(lang: String) { _detectedLanguage.value = lang }
    internal fun updateTokensPerSecond(value: Double) { _tokensPerSecond.value = value }
    internal fun updateBufferEnergy(energy: List<Float>) { _bufferEnergy.value = energy }
    internal fun updateBufferSeconds(seconds: Double) { _bufferSeconds.value = seconds }
    internal fun inferenceThreadCount(): Int =
        _performanceProfile.value.recommendedCpuThreads(Runtime.getRuntime().availableProcessors())

    internal fun onTranscriptionError(error: AppError) {
        _lastError.value = error
        transitionTo(SessionState.Error)
        audioRecorder.stopRecording()
        stopMicrophoneForegroundService()
    }

    internal fun onNoSignalDetected() {
        _lastError.value = AppError.NoMicrophoneSignal()
        transitionTo(SessionState.Error)
        audioRecorder.stopRecording()
        stopMicrophoneForegroundService()
        transcriptionCoordinator.cancelTranscriptionJob()
        cancelRecorderAndEnergyJobs()
    }

    val fullTranscriptionText: String
        get() {
            // Use StateFlow values directly (not chunkManager text properties)
            // so this works for both offline and streaming engines.
            val parts = listOfNotNull(
                _confirmedText.value.takeIf { it.isNotBlank() },
                _hypothesisText.value.takeIf { it.isNotBlank() }
            )
            return TextNormalizationUtils.normalizeText(parts.joinToString(" "))
        }

    val recordingDurationSeconds: Double
        get() = audioRecorder.bufferSeconds

    init {
        scope.launch {
            preferences.selectedModelId.collect { savedId ->
                if (e2eLocked) return@collect
                if (savedId != null) {
                    ModelInfo.findByIdOrLegacy(savedId)?.let {
                        _selectedModel.value = it
                    }
                }
            }
        }
        scope.launch {
            preferences.useVAD.collect { _useVAD.value = it }
        }
        scope.launch {
            preferences.enableTimestamps.collect { _enableTimestamps.value = it }
        }
        scope.launch {
            preferences.performanceProfile.collect { saved ->
                _performanceProfile.value = runCatching { PerformanceProfile.valueOf(saved) }
                    .getOrDefault(PerformanceProfile.BALANCED)
            }
        }
        scope.launch {
            preferences.translationEnabled.collect { enabled ->
                if (e2eLocked) return@collect
                _translationEnabled.value = enabled
                if (enabled) {
                    scheduleTranslationUpdate()
                } else {
                    resetTranslationState()
                }
            }
        }
        scope.launch {
            preferences.translationSourceLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationSourceLanguageCode.value) {
                    _translationSourceLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        scope.launch {
            preferences.translationTargetLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationTargetLanguageCode.value) {
                    _translationTargetLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        // Always-running system metrics sampling
        scope.launch(Dispatchers.Default) {
            while (true) {
                _cpuPercent.value = systemMetrics.getCpuPercent()
                _memoryMB.value = systemMetrics.getMemoryMB()
                delay(1000)
            }
        }
    }

    /** Create the appropriate ASR engine for the given model. */
    private fun createEngine(model: ModelInfo): AsrEngine {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> SherpaOnnxEngine(
                modelType = model.sherpaModelType
                    ?: throw IllegalArgumentException("sherpaModelType required for SHERPA_ONNX models")
            )
            EngineType.SHERPA_ONNX_STREAMING -> SherpaOnnxStreamingEngine()
            EngineType.CACTUS -> {
                if (!CactusEngine.isRuntimeSupported()) {
                    throw IllegalStateException("Cactus engine requires an arm64-v8a device.")
                }
                CactusEngine(
                    cactusModelType = model.cactusModelType
                        ?: throw IllegalArgumentException("cactusModelType required for CACTUS models")
                )
            }
            EngineType.QWEN_ASR -> QwenASREngine()
            EngineType.QWEN_ONNX -> QwenOnnxEngine()
            EngineType.ANDROID_SPEECH -> AndroidSpeechEngine(
                context = context,
                preferOffline = model.id.contains("offline")
            )
        }
    }

    /** Resolve the path to pass to loadModel based on engine type. */
    private fun resolveModelPath(model: ModelInfo): String {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.SHERPA_ONNX_STREAMING -> downloader.modelDir(model).absolutePath
            EngineType.CACTUS -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ASR -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.ANDROID_SPEECH -> "" // System-provided, no model path
        }
    }

    /** Ensure startup model selection reflects persisted user preference before loading. */
    suspend fun syncSelectedModelFromPreferences() {
        val savedId = preferences.selectedModelId.first() ?: return
        val savedModel = ModelInfo.findByIdOrLegacy(savedId) ?: return
        _selectedModel.value = savedModel
    }

    suspend fun loadModelIfAvailable() {
        val model = _selectedModel.value
        if (!downloader.isModelDownloaded(model)) return

        _modelState.value = ModelState.Loading
        _lastError.value = null

        try {
            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = engine.loadModel(modelPath)
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)
            currentEngine = engine
            _executionProviderStatus.value = engine.executionProviderStatus
            prewarmedModelId = null
            _modelState.value = ModelState.Loaded
        } catch (e: Throwable) {
            _modelState.value = ModelState.Unloaded
        }
    }

    fun unloadModel() {
        resetTranscriptionState()
        currentEngine?.release()
        currentEngine = null
        _executionProviderStatus.value = ExecutionProviderStatus()
        prewarmedModelId = null
        _modelState.value = ModelState.Unloaded
    }

    fun isModelDownloaded(model: ModelInfo): Boolean = downloader.isModelDownloaded(model)

    fun setSelectedModel(model: ModelInfo) {
        _selectedModel.value = model
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(model)
        resetTranscriptionState()
    }

    /** Launch setupModel on the engine's own scope so it survives ViewModel destruction. */
    fun launchSetup() {
        scope.launch { setupModel() }
    }

    suspend fun setupModel() = setupMutex.withLock {
        val model = _selectedModel.value
        _modelState.value = ModelState.Downloading
        _downloadProgress.value = 0f
        _lastError.value = null

        try {
            if (!downloader.isModelDownloaded(model)) {
                if (model.files.isNotEmpty() && !hasValidatedInternetConnection()) {
                    Log.w("WhisperEngine", "No validated internet connection while downloading ${model.id}")
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.NetworkUnavailable()
                    return@withLock
                }

                // Check available storage before attempting download
                // Use context.filesDir (always exists) since modelsDir may not exist yet
                val available = context.filesDir.usableSpace
                val needed = parseModelSize(model.sizeOnDisk)
                if (needed > 0 && available < (needed * 1.1).toLong()) {
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.InsufficientStorage(
                        needed = model.sizeOnDisk,
                        available = formatBytes(available)
                    )
                    return@withLock
                }

                downloader.download(model).collect { progress ->
                    _downloadProgress.value = progress
                }
            }

            _modelState.value = ModelState.Loading
            _downloadProgress.value = 1f

            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = withContext(Dispatchers.Default) {
                engine.loadModel(modelPath)
            }
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)

            val previousEngine = currentEngine
            currentEngine = engine
            prewarmedModelId = null
            _modelState.value = ModelState.Loaded
            preferences.setSelectedModelId(model.id)
            preferences.setLastModelPath(modelPath)
            if (previousEngine != null && previousEngine !== engine) {
                withContext(Dispatchers.Default) {
                    previousEngine.release()
                }
            }
        } catch (e: Throwable) {
            val wasDownloading = _downloadProgress.value < 1f
            _modelState.value = ModelState.Unloaded
            _downloadProgress.value = 0f
            _lastError.value = if (wasDownloading) {
                mapDownloadError(e)
            } else {
                AppError.ModelLoadFailed(e)
            }
        }
    }

    suspend fun switchModel(model: ModelInfo) {
        if (_sessionState.value == SessionState.Recording) {
            stopRecordingAndWait()
        }
        _selectedModel.value = model
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(model)
        resetTranscriptionState()

        val previousEngine = currentEngine
        if (previousEngine != null) {
            withContext(Dispatchers.Default) {
                previousEngine.release()
            }
        }
        currentEngine = null
        prewarmedModelId = null
        _modelState.value = ModelState.Unloaded
        setupModel()
    }

    suspend fun setUseVAD(enabled: Boolean) {
        _useVAD.value = enabled
        preferences.setUseVAD(enabled)
    }

    suspend fun setEnableTimestamps(enabled: Boolean) {
        _enableTimestamps.value = enabled
        preferences.setEnableTimestamps(enabled)
    }

    suspend fun setPerformanceProfile(profile: PerformanceProfile) {
        _performanceProfile.value = profile
        preferences.setPerformanceProfile(profile.name)
    }

    suspend fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
        preferences.setTranslationEnabled(enabled)
        if (!enabled) {
            resetTranslationState()
        } else {
            scheduleTranslationUpdate()
        }
    }

    suspend fun setTranslationSourceLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationSourceLanguageCode.value = normalized
        preferences.setTranslationSourceLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    suspend fun setTranslationTargetLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationTargetLanguageCode.value = normalized
        preferences.setTranslationTargetLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    /**
     * Warm up microphone recorder path ahead of first record tap so runtime setup
     * does not steal the beginning of the utterance.
     */
    suspend fun prewarmRecordingPath() {
        if (_audioInputMode.value != AudioInputMode.MICROPHONE) return
        if (!audioRecorder.hasPermission()) return
        if (_sessionState.value != SessionState.Idle) return
        recorderPrewarmMutex.withLock {
            withContext(Dispatchers.IO) {
                audioRecorder.prewarm(_audioInputMode.value)
            }
        }
    }

    /**
     * Prime the first native ASR decode so user speech is not delayed by runtime
     * graph compilation / allocator initialization on the first utterance.
     */
    suspend fun prewarmInferencePath() {
        if (_sessionState.value != SessionState.Idle) return
        val engine = currentEngine ?: return
        if (!engine.isLoaded) return
        val selectedModel = _selectedModel.value
        val currentModelId = selectedModel.id
        if (prewarmedModelId == currentModelId) return

        inferencePrewarmMutex.withLock {
            if (_sessionState.value != SessionState.Idle) return
            val liveEngine = currentEngine ?: return
            if (!liveEngine.isLoaded) return
            val liveModel = _selectedModel.value
            val liveModelId = liveModel.id
            if (prewarmedModelId == liveModelId) return

            // Skip synthetic inference prewarm — sherpa-onnx warmup can burn CPU on
            // some Android runtimes while idle. Mic prewarm is still active.
            prewarmedModelId = liveModelId
            Log.i("WhisperEngine", "Skipping inference prewarm for ${liveModel.engineType}")
        }
    }

    suspend fun prewarmRealtimePath() {
        Log.i(
            "WhisperEngine",
            "prewarmRealtimePath: state=${_sessionState.value}, inputMode=${_audioInputMode.value}, micPermission=${audioRecorder.hasPermission()}, modelLoaded=${currentEngine?.isLoaded == true}"
        )
        prewarmRecordingPath()
        prewarmInferencePath()
    }

    fun startRecording() {
        Log.i("WhisperEngine", "startRecording: sessionState=${_sessionState.value}, engine=${currentEngine != null}, loaded=${currentEngine?.isLoaded}")
        if (_sessionState.value != SessionState.Idle) {
            Log.w("WhisperEngine", "startRecording: not idle (${_sessionState.value}), ignoring")
            return
        }

        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "startRecording: model not ready")
            _lastError.value = AppError.ModelNotReady()
            transitionTo(SessionState.Error)
            return
        }

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            if (!audioRecorder.isSystemAudioCaptureSupported) {
                _lastError.value = AppError.SystemAudioCaptureUnsupported()
                transitionTo(SessionState.Error)
                return
            }
            if (!audioRecorder.hasSystemAudioCapturePermission) {
                _lastError.value = AppError.SystemAudioCapturePermissionDenied()
                transitionTo(SessionState.Error)
                return
            }
            // Ensure foreground service is running for MediaProjection
            try {
                context.startForegroundService(
                    Intent(context, MediaProjectionService::class.java)
                )
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to start MediaProjectionService: ${e.message}")
            }
        }

        if (!audioRecorder.hasPermission()) {
            Log.e("WhisperEngine", "startRecording: no mic permission")
            _lastError.value = AppError.MicrophonePermissionDenied()
            transitionTo(SessionState.Error)
            return
        }

        resetTranscriptionState()
        if (_audioInputMode.value == AudioInputMode.MICROPHONE) {
            try {
                context.startForegroundService(Intent(context, TranscriptionForegroundService::class.java))
            } catch (e: Exception) {
                Log.e("WhisperEngine", "Unable to start microphone foreground service", e)
                _lastError.value = AppError.TranscriptionFailed(e)
                transitionTo(SessionState.Error)
                return
            }
        }
        transitionTo(SessionState.Recording)

        val activeSessionToken = nextSessionToken()
        transcriptionCoordinator.cancelTranscriptionJob()
        recordingJob?.cancel()
        energyJob?.cancel()
        recordingJob = null
        energyJob = null

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                if (!isSessionActive(activeSessionToken)) return@launch
                audioRecorder.startRecording(_audioInputMode.value)
            } catch (e: Throwable) {
                if (!isSessionActive(activeSessionToken)) return@launch
                withContext(Dispatchers.Main) {
                    onTranscriptionError(AppError.TranscriptionFailed(e))
                }
            }
        }

        transcriptionCoordinator.startLoop(scope, activeSessionToken, engine)

        energyJob = scope.launch(Dispatchers.Default) {
            while (isSessionActive(activeSessionToken)) {
                _bufferEnergy.value = audioRecorder.relativeEnergy
                _bufferSeconds.value = audioRecorder.bufferSeconds
                delay(100)
            }
        }
    }

    fun stopRecording() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        // Cancel file transcription if running (transcribeFile uses fileTranscriptionJob)
        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = null
        // Stop mic input first so no new audio arrives
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobs()

        // For streaming engines, drain remaining audio synchronously.
        // For offline engines, the realtimeLoop finally block handles
        // transcribeFinalBuffer + finalizeCurrentChunk on the coroutine thread
        // to avoid racing with the loop's last iteration.
        if (currentEngine?.isStreaming == true) {
            transcriptionCoordinator.drainFinalStreamingAudioIfNeeded()
        }

        // Stop MediaProjection foreground service if it was running
        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }
        stopMicrophoneForegroundService()

        // Now invalidate and clean up
        invalidateSession()
        transcriptionCoordinator.cancelTranscriptionJob()
        transitionTo(SessionState.Idle)
    }

    private suspend fun stopRecordingAndWait() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        fileTranscriptionJob?.cancelAndJoin()
        fileTranscriptionJob = null
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobsAndWait()

        // For streaming engines, drain remaining audio synchronously.
        // For offline engines, the realtimeLoop finally block handles finalization.
        if (currentEngine?.isStreaming == true) {
            transcriptionCoordinator.drainFinalStreamingAudioIfNeeded()
        }

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }
        stopMicrophoneForegroundService()

        invalidateSession()
        transcriptionCoordinator.cancelTranscriptionJobAndWait()
        transitionTo(SessionState.Idle)
    }

    private fun cancelRecorderAndEnergyJobs() {
        recordingJob?.cancel()
        energyJob?.cancel()
        recordingJob = null
        energyJob = null
    }

    private fun stopMicrophoneForegroundService() {
        try {
            context.stopService(Intent(context, TranscriptionForegroundService::class.java))
        } catch (e: Exception) {
            Log.w("WhisperEngine", "Failed to stop microphone foreground service: ${e.message}")
        }
    }

    private suspend fun cancelRecorderAndEnergyJobsAndWait() {
        recordingJob?.cancelAndJoin()
        energyJob?.cancelAndJoin()
        recordingJob = null
        energyJob = null
    }

    fun setLastError(error: AppError) {
        _lastError.value = error
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        _audioInputMode.value = mode
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        if (!audioRecorder.isSystemAudioCaptureSupported) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCaptureUnsupported()
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCapturePermissionDenied()
            return
        }
        val granted = audioRecorder.setSystemAudioCapturePermission(resultCode, data)
        _systemAudioCaptureReady.value = granted
        if (!granted) {
            _lastError.value = if (audioRecorder.isSystemAudioCaptureSupported) {
                AppError.SystemAudioCapturePermissionDenied()
            } else {
                AppError.SystemAudioCaptureUnsupported()
            }
        } else if (_lastError.value is AppError.SystemAudioCapturePermissionDenied
            || _lastError.value is AppError.SystemAudioCaptureUnsupported
        ) {
            _lastError.value = null
            if (_sessionState.value == SessionState.Error) {
                transitionTo(SessionState.Idle)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
        if (_sessionState.value == SessionState.Error) {
            transitionTo(SessionState.Idle)
        }
    }

    fun clearTranscription() {
        if (_sessionState.value == SessionState.Recording) {
            invalidateSession()
            audioRecorder.stopRecording()
            transcriptionCoordinator.cancelTranscriptionJob()
            cancelRecorderAndEnergyJobs()
        }
        stopMicrophoneForegroundService()
        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = null
        resetTranscriptionState()
        transitionTo(SessionState.Idle)
    }

    internal fun transitionTo(newState: SessionState) {
        _sessionState.value = newState
        _isRecording.value = (newState == SessionState.Recording)
    }



    /**
     * When the ASR engine detects a language (e.g., SenseVoice returns "en" or "ja"),
     * auto-swap translation direction so that detected speech is the source and
     * the other configured language becomes the target.
     */
    internal fun applyDetectedLanguageToTranslation(lang: String) {
        if (!_translationEnabled.value) return
        val currentSource = _translationSourceLanguageCode.value
        val currentTarget = _translationTargetLanguageCode.value

        // If detected language matches target but not source, swap them
        if (lang == currentTarget && lang != currentSource) {
            Log.i("WhisperEngine", "Detected language '$lang' matches target — swapping ($currentSource→$currentTarget becomes $currentTarget→$currentSource)")
            _translationSourceLanguageCode.value = currentTarget
            _translationTargetLanguageCode.value = currentSource
            scope.launch {
                preferences.setTranslationSourceLanguage(currentTarget)
                preferences.setTranslationTargetLanguage(currentSource)
            }
            lastTranslationInput = null
            resetTranslationState()
            scheduleTranslationUpdate()
        } else if (lang != currentSource && lang != currentTarget) {
            Log.i("WhisperEngine", "Detected language '$lang' not in pair ($currentSource→$currentTarget) — ignoring")
        }
        // If lang == currentSource, no change needed
    }

    private fun resetTranscriptionState() {
        resetTranslationState()
        transcriptionCoordinator.reset()
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(_selectedModel.value)
        _confirmedText.value = ""
        _hypothesisText.value = ""
        e2eOrchestrator.reset()
        _detectedLanguage.value = null
        _bufferEnergy.value = emptyList()
        _bufferSeconds.value = 0.0
        _tokensPerSecond.value = 0.0
        _lastError.value = null
        audioRecorder.reset()
    }

    /** Clear translation-related state without affecting transcription or audio. */
    private fun resetTranslationState() {
        translationJob?.cancel()
        translationJob = null
        _translatedConfirmedText.value = ""
        _translatedHypothesisText.value = ""
        _translationWarning.value = null
        lastTranslationInput = null
    }

    /** Transcribe a WAV file. Used for testing and file import UI. */
    fun transcribeFile(filePath: String, languageHint: String = "auto") {
        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "transcribeFile: model not ready")
            _lastError.value = AppError.ModelNotReady()
            writeE2EFailure(error = "model not ready")
            return
        }

        // Guard: ignore if already busy transcribing
        if (_sessionState.value == SessionState.Recording) {
            Log.w("WhisperEngine", "transcribeFile: already busy, ignoring")
            writeE2EFailure(error = "engine busy")
            return
        }

        resetTranscriptionState()
        transitionTo(SessionState.Recording)
        _hypothesisText.value = "Transcribing file..."

        // File decode can be CPU-heavy (especially omnilingual); keep it off main.
        fileTranscriptionJob = scope.launch(Dispatchers.Default) {
            try {
                Log.i("WhisperEngine", "transcribeFile: reading $filePath")
                val audioSamples = withContext(Dispatchers.IO) {
                    readWavFile(filePath)
                }
                val durationSec = audioSamples.size / AudioConstants.SAMPLE_RATE.toDouble()
                Log.i("WhisperEngine", "transcribeFile: ${audioSamples.size} samples (${durationSec}s)")
                _hypothesisText.value = "Transcribing ${"%.1f".format(durationSec)}s of audio..."

                audioRecorder.injectSamples(audioSamples)
                _bufferSeconds.value = durationSec
                _bufferEnergy.value = audioRecorder.relativeEnergy

                val startTime = System.nanoTime()
                val numThreads = inferenceThreadCount()
                Log.i("WhisperEngine", "transcribeFile: starting transcription with $numThreads threads")
                val segments = if (engine is AndroidSpeechEngine && Build.VERSION.SDK_INT < 33 && e2eLocked) {
                    // On API < 33, SpeechRecognizer can't accept file audio directly.
                    // For E2E benchmarks, attempt acoustic loopback (speaker -> mic).
                    Log.i("WhisperEngine", "transcribeFile: Android Speech API<33, using acoustic loopback")
                    engine.transcribeViaAcousticLoopback(audioSamples, languageHint)
                } else {
                    engine.transcribe(audioSamples, numThreads, languageHint)
                }

                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                val totalWords = segments.sumOf { it.text.split(" ").size }
                Log.i("WhisperEngine", "transcribeFile: ${segments.size} segments, $totalWords words in ${"%.2f".format(elapsed)}s")
                if (elapsed > 0 && totalWords > 0) {
                    _tokensPerSecond.value = totalWords / elapsed
                }
                // Apply detected language to translation direction
                val lang = normalizeLanguageCode(segments.firstOrNull()?.detectedLanguage)
                if (lang != null && lang != _detectedLanguage.value) {
                    _detectedLanguage.value = lang
                    applyDetectedLanguageToTranslation(lang)
                }

                chunkManager.confirmedSegments.addAll(segments)
                val renderedText = chunkManager.renderSegmentsText(segments)
                chunkManager.confirmedText = renderedText
                _confirmedText.value = renderedText
                _hypothesisText.value = ""
                val model = _selectedModel.value
                val skipReason = when {
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.contains("requires API 33+", ignoreCase = true) ->
                        "Android Speech file transcription requires API 33+ (device API ${Build.VERSION.SDK_INT})"
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.isBlank() ->
                        "Android Speech returned empty transcript on device API ${Build.VERSION.SDK_INT} (loopback may be blocked by echo cancellation; API 33+ supports direct file input)"
                    else -> null
                }
                val skipped = skipReason != null

                if (!skipped) {
                    Log.i("WhisperEngine", "E2E translation state: enabled=${_translationEnabled.value} src=${_translationSourceLanguageCode.value} tgt=${_translationTargetLanguageCode.value} translated=${_translatedConfirmedText.value.take(20)}")
                    scheduleTranslationUpdate()
                    val waitUntil = SystemClock.elapsedRealtime() + 12_000L
                    while (SystemClock.elapsedRealtime() < waitUntil) {
                        val translatedReady = !_translationEnabled.value ||
                            _confirmedText.value.isBlank() ||
                            _translatedConfirmedText.value.isNotBlank()
                        if (translatedReady) break
                        delay(250)
                    }
                }

                // Write E2E evidence result
                val transcript = _confirmedText.value
                e2eOrchestrator.writeResult(
                    transcript = transcript,
                    durationMs = if (skipped) 0.0 else elapsed * 1000,
                    tokensPerSecond = if (skipped) 0.0 else _tokensPerSecond.value,
                    error = skipReason,
                    skipped = skipped
                )
            } catch (e: CancellationException) {
                Log.i("WhisperEngine", "transcribeFile: cancelled")
            } catch (e: Throwable) {
                Log.e("WhisperEngine", "transcribeFile failed", e)
                _lastError.value = AppError.TranscriptionFailed(e)
                e2eOrchestrator.writeResult(
                    transcript = "",
                    durationMs = 0.0,
                    tokensPerSecond = 0.0,
                    error = e.message,
                    skipped = false
                )
            } finally {
                // Only transition to Idle if we're still in the file-transcription session.
                // stopRecording/clearTranscription may have already transitioned us, and
                // a new recording may have started — don't clobber it.
                val state = _sessionState.value
                if (state == SessionState.Recording || state == SessionState.Stopping) {
                    transitionTo(SessionState.Idle)
                }
            }
        }
    }

    fun writeE2EFailure(modelId: String = _selectedModel.value.id, error: String) {
        e2eOrchestrator.writeFailure(modelId = modelId, error = error)
    }

    fun writeE2ESkipped(modelId: String = _selectedModel.value.id, reason: String) {
        e2eOrchestrator.writeSkipped(modelId = modelId, reason = reason)
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun mapDownloadError(error: Throwable): AppError {
        val root = rootCause(error)
        return when {
            !hasValidatedInternetConnection() -> AppError.NetworkUnavailable()
            root is UnknownHostException -> AppError.NetworkUnavailable()
            root is SocketTimeoutException -> AppError.ModelDownloadFailed(
                Exception("Network timeout while downloading. Check connection and retry.")
            )
            else -> AppError.ModelDownloadFailed(error)
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var cause = error
        var next = cause.cause
        while (next != null && next !== cause) {
            cause = next
            next = cause.cause
        }
        return cause
    }

    private fun parseModelSize(sizeStr: String): Long {
        val cleaned = sizeStr.replace("~", "").trim()
        val parts = cleaned.split(" ")
        if (parts.size != 2) return 0L
        val value = parts[0].toDoubleOrNull() ?: return 0L
        return when (parts[1].uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> 0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }


    internal fun scheduleTranslationUpdate() {
        translationJob?.cancel()

        if (!_translationEnabled.value) {
            resetTranslationState()
            return
        }

        // Quick pre-check: skip scheduling if language codes are not configured
        val srcCheck = _translationSourceLanguageCode.value.trim()
        val tgtCheck = _translationTargetLanguageCode.value.trim()
        if (srcCheck.isEmpty() || tgtCheck.isEmpty()) return

        translationJob = scope.launch(Dispatchers.Default) {
            delay(150)  // Debounce rapid updates during fast speech

            // Re-read latest values after debounce
            val confirmedSnapshot = _confirmedText.value.trim()
            val hypothesisSnapshot = _hypothesisText.value.trim()
            val sourceLanguageCode = _translationSourceLanguageCode.value.trim().lowercase()
            val targetLanguageCode = _translationTargetLanguageCode.value.trim().lowercase()

            if (sourceLanguageCode.isEmpty() || targetLanguageCode.isEmpty()) return@launch

            val currentInput = confirmedSnapshot to hypothesisSnapshot
            if (lastTranslationInput == currentInput) return@launch

            var warningMessage: String? = null

            var translatedConfirmed: String
            var translatedHypothesis: String

            if (sourceLanguageCode == targetLanguageCode) {
                translatedConfirmed = confirmedSnapshot
                translatedHypothesis = hypothesisSnapshot
            } else {
                try {
                    translatedConfirmed = if (confirmedSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = confirmedSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }

                    translatedHypothesis = if (hypothesisSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = hypothesisSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }
                } catch (e: UnsupportedOperationException) {
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = e.message ?: AppError.TranslationUnavailable().message
                } catch (e: Throwable) {
                    if (e is CancellationException) return@launch
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = AppError.TranslationFailed(e).message
                }
            }

            _translatedConfirmedText.value = TextNormalizationUtils.normalizeText(translatedConfirmed)
            _translatedHypothesisText.value = TextNormalizationUtils.normalizeText(translatedHypothesis)
            _translationWarning.value = warningMessage
            lastTranslationInput = currentInput
        }
    }

    private fun readWavFile(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) throw Exception("File not found: $filePath")
        val bytes = file.readBytes()
        if (bytes.size < 12) throw Exception("File too small to be a valid WAV")

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        if (riff != "RIFF") throw Exception("Not a RIFF file")
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        if (wave != "WAVE") throw Exception("Not a WAVE file")

        // Parse chunks to find fmt and data
        var bitsPerSample = 16
        var channels = 1
        var sampleRate = AudioConstants.SAMPLE_RATE
        var dataOffset = -1
        var dataSize = -1

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = java.nio.ByteBuffer.wrap(bytes, pos + 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "fmt " && pos + 8 + chunkSize <= bytes.size) {
                val buf = java.nio.ByteBuffer.wrap(bytes, pos + 8, chunkSize)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.short // audioFormat
                channels = buf.short.toInt()
                sampleRate = buf.int
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = buf.short.toInt()
            } else if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                break
            }
            pos += 8 + chunkSize
            if (chunkSize % 2 != 0) pos++ // RIFF chunks are word-aligned
        }

        if (dataOffset < 0 || dataSize <= 0) throw Exception("No data chunk found in WAV")
        Log.i("WhisperEngine", "WAV: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit data=${dataSize}B")

        return if (bitsPerSample == 16) {
            val sampleCount = dataSize / (2 * channels)
            FloatArray(sampleCount) { i ->
                val off = dataOffset + i * 2 * channels
                val low = bytes[off].toInt() and 0xFF
                val high = bytes[off + 1].toInt()
                (high shl 8 or low).toFloat() / 32768f
            }
        } else if (bitsPerSample == 32) {
            val sampleCount = dataSize / (4 * channels)
            FloatArray(sampleCount) { i ->
                val off = dataOffset + i * 4 * channels
                java.nio.ByteBuffer.wrap(bytes, off, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            }
        } else {
            throw Exception("Unsupported bits per sample: $bitsPerSample")
        }
    }

    fun destroy() {
        if (_sessionState.value == SessionState.Recording) {
            audioRecorder.stopRecording()
            transcriptionCoordinator.cancelTranscriptionJob()
            recordingJob?.cancel()
            energyJob?.cancel()
        }
        audioRecorder.clearSystemAudioCapturePermission()
        _systemAudioCaptureReady.value = false
        translationJob?.cancel()
        scope.cancel()
        mlKitTranslator.close()
        currentEngine?.release()
        currentEngine = null
        prewarmedModelId = null
    }
}
