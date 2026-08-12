package com.voiceping.offlinetranscription.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps an explicitly started microphone transcription session eligible for background use. */
class TranscriptionForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Offline transcription is active")
            .setContentText("Recording locally; tap the app to finish")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Active transcription", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Visible while the microphone is used for local transcription"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "active_transcription"
        const val NOTIFICATION_ID = 1002
    }
}
