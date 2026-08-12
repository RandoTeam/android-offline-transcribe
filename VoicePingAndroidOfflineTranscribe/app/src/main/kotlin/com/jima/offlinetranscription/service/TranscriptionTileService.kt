package com.voiceping.offlinetranscription.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voiceping.offlinetranscription.MainActivity
import com.voiceping.offlinetranscription.OfflineTranscriptionApp
import com.voiceping.offlinetranscription.model.ModelState

/**
 * Quick Settings trigger for the single WhisperEngine session pipeline.
 * A tile never fabricates a separate recorder. If no downloaded model is ready,
 * it opens the app instead of pretending that recording began.
 */
class TranscriptionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val engine = (application as OfflineTranscriptionApp).whisperEngine
        if (engine.isRecording.value) {
            engine.stopRecording()
            updateTile()
            return
        }
        if (engine.modelState.value != ModelState.Loaded) {
            startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        engine.startRecording()
        updateTile()
    }

    private fun updateTile() {
        val engine = (application as OfflineTranscriptionApp).whisperEngine
        qsTile?.apply {
            when {
                engine.isRecording.value -> {
                    state = Tile.STATE_ACTIVE
                    label = "Stop dictation"
                    subtitle = engine.selectedModel.value.displayName
                }
                engine.modelState.value == ModelState.Loaded -> {
                    state = Tile.STATE_INACTIVE
                    label = "Start dictation"
                    subtitle = engine.selectedModel.value.displayName
                }
                else -> {
                    state = Tile.STATE_UNAVAILABLE
                    label = "Offline Transcribe Lab"
                    subtitle = "Open app to load a model"
                }
            }
            updateTile()
        }
    }
}
