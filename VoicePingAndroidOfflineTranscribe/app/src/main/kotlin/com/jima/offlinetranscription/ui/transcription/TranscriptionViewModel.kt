package com.voiceping.offlinetranscription.ui.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.PerformanceProfile
import com.voiceping.offlinetranscription.service.WhisperEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionViewModel(
    val engine: WhisperEngine
) : ViewModel() {

    val isRecording = engine.isRecording
    val confirmedText = engine.confirmedText
    val hypothesisText = engine.hypothesisText
    val bufferEnergy = engine.bufferEnergy
    val bufferSeconds = engine.bufferSeconds
    val tokensPerSecond = engine.tokensPerSecond
    val lastError = engine.lastError
    val selectedModel = engine.selectedModel
    val modelState = engine.modelState
    val useVAD = engine.useVAD
    val enableTimestamps = engine.enableTimestamps
    val audioInputMode = engine.audioInputMode
    val systemAudioCaptureReady = engine.systemAudioCaptureReady
    val isSystemAudioCaptureSupported: Boolean
        get() = engine.isSystemAudioCaptureSupported
    val cpuPercent = engine.cpuPercent
    val memoryMB = engine.memoryMB
    val e2eResult = engine.e2eResult
    val performanceProfile = engine.performanceProfile

    // Translation state
    val translationEnabled = engine.translationEnabled
    val translationSourceLanguage = engine.translationSourceLanguageCode
    val translationTargetLanguage = engine.translationTargetLanguageCode
    val translatedConfirmedText = engine.translatedConfirmedText
    val translatedHypothesisText = engine.translatedHypothesisText
    val translationWarning = engine.translationWarning
    val translationModelReady = engine.translationModelReady
    val translationDownloadStatus = engine.translationDownloadStatus

    val fullText: String
        get() = engine.fullTranscriptionText

    private inline fun launchEngineAction(crossinline block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            block()
        }
    }

    private fun copyAssetIfMissing(context: Context, assetName: String): File {
        val cached = File(context.cacheDir, assetName)
        if (cached.exists()) return cached
        context.assets.open(assetName).use { input ->
            cached.outputStream().use { output -> input.copyTo(output) }
        }
        return cached
    }

    fun toggleRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        } else {
            startRecordingWithPreparation()
        }
    }

    fun startRecordingWithPreparation() {
        launchEngineAction {
            engine.prewarmRealtimePath()
            engine.startRecording()
        }
    }

    fun prewarmOnScreenOpen() {
        launchEngineAction {
            engine.prewarmRealtimePath()
        }
    }

    fun clearTranscription() {
        engine.clearTranscription()
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        engine.setAudioInputMode(mode)
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        engine.setSystemAudioCapturePermission(resultCode, data)
    }

    /** Dismiss error without clearing transcription text. */
    fun dismissError() {
        engine.clearError()
    }

    fun transcribeTestFile(filePath: String) {
        engine.transcribeFile(filePath)
    }

    fun transcribeTestAsset(context: Context) {
        val cached = copyAssetIfMissing(context, "test_speech.wav")
        engine.transcribeFile(cached.absolutePath)
    }

    fun stopIfRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        }
    }

    fun switchModel(model: ModelInfo) {
        launchEngineAction {
            engine.switchModel(model)
        }
    }

    fun setUseVAD(enabled: Boolean) {
        launchEngineAction {
            engine.setUseVAD(enabled)
        }
    }

    fun setEnableTimestamps(enabled: Boolean) {
        launchEngineAction {
            engine.setEnableTimestamps(enabled)
        }
    }

    /** Copies a user-selected WAV into private cache so the decoder never depends on a transient URI grant. */
    fun transcribeWavUri(context: Context, uri: Uri) {
        launchEngineAction {
            val cached = withContext(Dispatchers.IO) {
                val destination = File(context.cacheDir, "import-${System.currentTimeMillis()}.wav")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Unable to read selected audio file")
                destination
            }
            engine.transcribeFile(cached.absolutePath)
        }
    }

    fun setPerformanceProfile(profile: PerformanceProfile) {
        launchEngineAction { engine.setPerformanceProfile(profile) }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        launchEngineAction {
            engine.setTranslationEnabled(enabled)
        }
    }

    fun setTranslationSourceLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationSourceLanguageCode(languageCode)
        }
    }

    fun setTranslationTargetLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationTargetLanguageCode(languageCode)
        }
    }
}
