package com.voiceping.offlinetranscription.timeline

/** Minimal durable event. Heavy artifacts are written only after this journal record succeeds. */
data class TimelineEvent(
    val sessionId: String,
    val timestampMillis: Long,
    val packageName: String,
    val type: TimelineEventType,
    val summary: String,
    val recovered: Boolean = false,
)

enum class TimelineEventType {
    APP_ENTER,
    APP_EXIT,
    SCREEN_SNAPSHOT,
    SCREENSHOT_SAVED,
    ASR_FINAL,
    NOTIFICATION_POSTED,
    CAPTURE_SUSPENDED_SENSITIVE,
    SESSION_FINALIZED,
}
