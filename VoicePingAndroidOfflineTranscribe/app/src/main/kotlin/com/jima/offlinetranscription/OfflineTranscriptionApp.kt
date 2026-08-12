package com.voiceping.offlinetranscription

import android.app.Application
import android.util.Log
import com.voiceping.offlinetranscription.data.AppPreferences
import com.voiceping.offlinetranscription.history.TranscriptHistoryDatabase
import com.voiceping.offlinetranscription.history.TranscriptHistoryRepository
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.service.WhisperEngine
import java.io.File

class OfflineTranscriptionApp : Application() {
    companion object {
        private const val TAG = "OfflineTranscriptionApp"
        private const val LEGACY_MODELS_DIR_NAME = "models"    // Old model cache path
        private const val APP_MODELS_DIR_NAME = "asr_models"   // Current model cache
    }

    /**
     * Migrate model dirs from legacy `filesDir/models/` to `filesDir/asr_models/`.
     * This was originally needed for Cactus SDK compatibility; kept for one-time migration
     * of existing installations.
     */
    private fun migrateModelCacheIfNeeded() {
        val oldBase = File(filesDir, LEGACY_MODELS_DIR_NAME)
        if (!oldBase.exists() || !oldBase.isDirectory) return

        val newBase = File(filesDir, APP_MODELS_DIR_NAME)
        if (!newBase.exists()) newBase.mkdirs()

        val modelIds = ModelInfo.availableModels.map { it.id }.toSet()
        for (id in modelIds) {
            val src = File(oldBase, id)
            if (!src.exists() || !src.isDirectory) continue

            val dst = File(newBase, id)
            if (dst.exists()) continue

            if (!src.renameTo(dst)) {
                Log.w(TAG, "Failed to migrate model dir: ${src.absolutePath} -> ${dst.absolutePath}")
            } else {
                Log.i(TAG, "Migrated model dir: $id")
            }
        }
    }

    lateinit var preferences: AppPreferences
        private set

    lateinit var whisperEngine: WhisperEngine
        private set

    lateinit var transcriptHistory: TranscriptHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        migrateModelCacheIfNeeded()
        preferences = AppPreferences(this)
        transcriptHistory = TranscriptHistoryRepository(TranscriptHistoryDatabase.create(this).entries())
        whisperEngine = WhisperEngine(this, preferences, transcriptHistory)
    }

    override fun onTerminate() {
        super.onTerminate()
        whisperEngine.destroy()
    }
}
