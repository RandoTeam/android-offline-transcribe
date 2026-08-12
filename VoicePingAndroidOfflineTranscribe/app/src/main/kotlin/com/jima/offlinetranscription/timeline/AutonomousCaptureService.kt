package com.voiceping.offlinetranscription.timeline

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceping.offlinetranscription.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Explicitly user-enabled accessibility entry point for the local Personal Timeline.
 * It never captures globally: a user switch, an app allowlist, and a non-sensitive
 * accessibility tree are all required before an event is persisted.
 */
class AutonomousCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preferences: AppPreferences
    private lateinit var journal: EventJournal
    @Volatile private var enabled = false
    @Volatile private var paused = false
    @Volatile private var policy = CapturePolicy()
    private var settleJob: Job? = null
    private val sessionsByPackage = mutableMapOf<String, String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 250
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        preferences = AppPreferences(applicationContext)
        journal = EventJournal(File(filesDir, "timeline/events.ndjson"))
        scope.launch {
            combine(
                preferences.autonomousCaptureEnabled,
                preferences.autonomousCapturePaused,
                preferences.autonomousCaptureAllowlist,
            ) { isEnabled, isPaused, allowed -> Triple(isEnabled, isPaused, allowed) }
                .collect { (isEnabled, isPaused, allowed) ->
                    enabled = isEnabled
                    paused = isPaused
                    policy = CapturePolicy(allowedPackages = allowed)
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled || paused || event == null) return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val root = rootInActiveWindow ?: return
        val candidate = CaptureCandidate(
            packageName = packageName,
            containsSensitiveField = containsSensitiveNode(root),
        )
        when (policy.decision(candidate)) {
            CaptureDecision.CAPTURE -> scheduleSnapshot(packageName, event.eventType, root)
            CaptureDecision.SKIP_SENSITIVE_CONTENT -> writeSensitiveSuspension(packageName)
            else -> Unit
        }
    }

    private fun scheduleSnapshot(packageName: String, eventType: Int, root: AccessibilityNodeInfo) {
        val text = collectVisibleText(root).take(MAX_CAPTURED_TEXT_CHARS)
        if (text.isBlank()) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(350) // debounce noisy accessibility update bursts
            val sessionId = synchronized(sessionsByPackage) {
                sessionsByPackage.getOrPut(packageName) { UUID.randomUUID().toString() }
            }
            journal.append(
                TimelineEvent(
                    sessionId = sessionId,
                    timestampMillis = System.currentTimeMillis(),
                    packageName = packageName,
                    type = if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                        TimelineEventType.APP_ENTER
                    } else {
                        TimelineEventType.SCREEN_SNAPSHOT
                    },
                    summary = text,
                )
            )
        }
    }

    private fun writeSensitiveSuspension(packageName: String) {
        scope.launch {
            journal.append(
                TimelineEvent(
                    sessionId = synchronized(sessionsByPackage) {
                        sessionsByPackage.getOrPut(packageName) { UUID.randomUUID().toString() }
                    },
                    timestampMillis = System.currentTimeMillis(),
                    packageName = packageName,
                    type = TimelineEventType.CAPTURE_SUSPENDED_SENSITIVE,
                    summary = "Capture suspended: sensitive content",
                )
            )
        }
    }

    private fun containsSensitiveNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    if (containsSensitiveNode(child)) return true
                } finally {
                    child.recycle()
                }
            }
        }
        return false
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo): String = buildString {
        fun visit(current: AccessibilityNodeInfo) {
            if (current.isPassword) return
            current.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
            current.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                }
            }
        }
        visit(node)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        settleJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private companion object { const val MAX_CAPTURED_TEXT_CHARS = 8_000 }
}
