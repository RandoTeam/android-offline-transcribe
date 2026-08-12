package com.voiceping.offlinetranscription.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voiceping.offlinetranscription.OfflineTranscriptionApp
import com.voiceping.offlinetranscription.model.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Android speech-recognition provider backed by the same local WhisperEngine session pipeline. */
class OfflineRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var activeCallback: Callback? = null
    private var lastPartial = ""

    override fun onStartListening(intent: Intent?, callback: Callback) {
        stopObservation()
        val engine = (application as OfflineTranscriptionApp).whisperEngine
        if (engine.modelState.value != ModelState.Loaded) {
            callback.error(SpeechRecognizer.ERROR_SERVER)
            return
        }
        val requestedLanguage = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        engine.setLiveLanguageHint(requestedLanguage)
        activeCallback = callback
        lastPartial = ""
        callback.readyForSpeech(Bundle())
        engine.startRecording()
        if (!engine.isRecording.value) {
            callback.error(SpeechRecognizer.ERROR_AUDIO)
            activeCallback = null
            engine.setLiveLanguageHint("auto")
            return
        }
        callback.beginningOfSpeech()
        observation = scope.launch {
            engine.hypothesisText.collectLatest { hypothesis ->
                val normalized = hypothesis.trim()
                if (normalized.isNotBlank() && normalized != lastPartial) {
                    lastPartial = normalized
                    callback.partialResults(resultsBundle(normalized))
                }
            }
        }
    }

    override fun onStopListening(callback: Callback) {
        val engine = (application as OfflineTranscriptionApp).whisperEngine
        engine.stopRecording()
        scope.launch {
            // Let the engine's existing final-flush path commit its last segment.
            delay(FINAL_RESULT_SETTLE_MS)
            val result = engine.fullTranscriptionText.trim()
            if (result.isBlank()) callback.error(SpeechRecognizer.ERROR_NO_MATCH)
            else callback.results(resultsBundle(result))
            finishRecognition()
        }
    }

    override fun onCancel(callback: Callback) {
        (application as OfflineTranscriptionApp).whisperEngine.stopRecording()
        callback.error(SpeechRecognizer.ERROR_CLIENT)
        finishRecognition()
    }

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun finishRecognition() {
        stopObservation()
        (application as OfflineTranscriptionApp).whisperEngine.setLiveLanguageHint("auto")
        activeCallback = null
    }

    private fun stopObservation() {
        observation?.cancel()
        observation = null
    }

    override fun onDestroy() {
        finishRecognition()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private companion object { const val FINAL_RESULT_SETTLE_MS = 500L }
}
