package com.voiceping.offlinetranscription.ui.transcription

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.voiceping.offlinetranscription.service.MediaProjectionService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceping.offlinetranscription.BuildConfig
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.PerformanceProfile
import com.voiceping.offlinetranscription.service.E2ETestResult
import com.voiceping.offlinetranscription.ui.components.AudioVisualizer
import com.voiceping.offlinetranscription.ui.components.RecordButton
import com.voiceping.offlinetranscription.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(viewModel: TranscriptionViewModel, onChangeModel: () -> Unit = {}) {
    val context = LocalContext.current
    var pendingPermissionStart by remember { mutableStateOf(false) }
    val projectionManager = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        } else {
            null
        }
    }

    val wavPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.transcribeWavUri(context, it) }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.setSystemAudioCapturePermission(result.resultCode, result.data)
        if (pendingPermissionStart &&
            result.resultCode == Activity.RESULT_OK &&
            result.data != null
        ) {
            pendingPermissionStart = false
            viewModel.startRecordingWithPreparation()
        } else if (pendingPermissionStart) {
            pendingPermissionStart = false
            Log.i("TranscriptionScreen", "System audio capture permission denied/cancelled")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingPermissionStart) {
            if (viewModel.audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK &&
                !viewModel.systemAudioCaptureReady.value
            ) {
                projectionManager?.let { manager ->
                    // Android 14+ requires foreground service before getMediaProjection()
                    context.startForegroundService(
                        Intent(context, MediaProjectionService::class.java)
                    )
                    mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                } ?: run {
                    pendingPermissionStart = false
                }
            } else {
                pendingPermissionStart = false
                viewModel.startRecordingWithPreparation()
            }
        } else {
            pendingPermissionStart = false
            Log.i("TranscriptionScreen", "Permission result ignored (granted=$granted)")
        }
    }

    fun requestSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projectionManager == null) {
            viewModel.setSystemAudioCapturePermission(Activity.RESULT_CANCELED, null)
            return
        }
        // Android 14+ requires foreground service before getMediaProjection()
        context.startForegroundService(
            Intent(context, MediaProjectionService::class.java)
        )
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun onRecordClick() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val wantsSystemCapture = viewModel.audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK

        if (viewModel.isRecording.value) {
            pendingPermissionStart = false
            viewModel.toggleRecording()
        } else if (wantsSystemCapture && !viewModel.systemAudioCaptureReady.value) {
            if (hasPermission) {
                pendingPermissionStart = true
                requestSystemAudioCapture()
            } else {
                pendingPermissionStart = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (hasPermission) {
            pendingPermissionStart = false
            viewModel.startRecordingWithPreparation()
        } else {
            pendingPermissionStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isRecording by viewModel.isRecording.collectAsState()
    val confirmedText by viewModel.confirmedText.collectAsState()
    val hypothesisText by viewModel.hypothesisText.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val useVAD by viewModel.useVAD.collectAsState()
    val enableTimestamps by viewModel.enableTimestamps.collectAsState()
    val audioInputMode by viewModel.audioInputMode.collectAsState()
    val systemAudioCaptureReady by viewModel.systemAudioCaptureReady.collectAsState()
    val displayConfirmedText = remember(confirmedText) { confirmedText.trim() }
    val displayHypothesisText = remember(hypothesisText) { hypothesisText.trim() }

    // Translation state
    val translationEnabled by viewModel.translationEnabled.collectAsState()
    val translatedConfirmedText by viewModel.translatedConfirmedText.collectAsState()
    val translatedHypothesisText by viewModel.translatedHypothesisText.collectAsState()
    val translationWarning by viewModel.translationWarning.collectAsState()
    val translationDownloadStatus by viewModel.translationDownloadStatus.collectAsState()
    val translationSourceLanguage by viewModel.translationSourceLanguage.collectAsState()
    val translationTargetLanguage by viewModel.translationTargetLanguage.collectAsState()
    val performanceProfile by viewModel.performanceProfile.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    // Elapsed recording timer — based on wall clock, not accumulated delays
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                kotlinx.coroutines.delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    LaunchedEffect(displayConfirmedText, displayHypothesisText, isRecording) {
        if (isRecording || displayConfirmedText.isNotEmpty() || displayHypothesisText.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Pre-initialize mic recorder setup on screen entry to avoid first-utterance clipping.
    LaunchedEffect(Unit) {
        viewModel.prewarmOnScreenOpen()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcribe") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TranscriptionTextDisplay(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                confirmedText = displayConfirmedText,
                hypothesisText = displayHypothesisText,
                isRecording = isRecording,
                translationEnabled = translationEnabled,
                translatedConfirmedText = translatedConfirmedText,
                translatedHypothesisText = translatedHypothesisText,
                translationSourceLanguage = translationSourceLanguage,
                translationTargetLanguage = translationTargetLanguage,
                translationDownloadStatus = translationDownloadStatus,
                translationWarning = translationWarning
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                AudioInputModeCard(
                    audioInputMode = audioInputMode,
                    systemCaptureReady = systemAudioCaptureReady,
                    systemCaptureSupported = viewModel.isSystemAudioCaptureSupported,
                    onInputModeChange = { viewModel.setAudioInputMode(it) },
                    onRequestSystemCapture = { requestSystemAudioCapture() }
                )

                if (isRecording) {
                    RecordingStatsBar(viewModel)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${selectedModel.displayName} · ${selectedModel.languages}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = selectedModel.inferenceMethod,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (BuildConfig.DEBUG) {
                    val e2eResult by viewModel.e2eResult.collectAsState()
                    e2eResult?.let { result ->
                        E2EEvidenceOverlay(result)
                    }
                }

                ResourceStatsBar(viewModel, isRecording, elapsedSeconds)
            }

            ControlButtonsRow(
                isRecording = isRecording,
                onPickAudio = { wavPickerLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave")) },
                onRecord = { onRecordClick() },
                onSettings = { showSettings = true }
            )
        }
    }

    lastError?.let { error ->
        val isPermissionError = error is com.voiceping.offlinetranscription.model.AppError.MicrophonePermissionDenied
        val isSystemCapturePermissionError =
            error is com.voiceping.offlinetranscription.model.AppError.SystemAudioCapturePermissionDenied
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = {
                Text(
                    if (isPermissionError || isSystemCapturePermissionError) {
                        "Permission Required"
                    } else {
                        "Error"
                    }
                )
            },
            text = { Text(error.message) },
            confirmButton = {
                if (isPermissionError) {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text("Grant Permission")
                    }
                } else if (isSystemCapturePermissionError) {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        requestSystemAudioCapture()
                    }) {
                        Text("Enable System Capture")
                    }
                } else {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (isPermissionError || isSystemCapturePermissionError) {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showSettings) {
        SettingsBottomSheet(
            selectedModel = selectedModel,
            useVAD = useVAD,
            enableTimestamps = enableTimestamps,
            translationEnabled = translationEnabled,
            translationSourceLanguage = translationSourceLanguage,
            translationTargetLanguage = translationTargetLanguage,
            performanceProfile = performanceProfile,
            fullText = viewModel.fullText,
            onCopyText = { clipboardManager.setText(AnnotatedString(viewModel.fullText)) },
            onClearTranscription = { viewModel.clearTranscription() },
            onChangeModel = {
                viewModel.stopIfRecording()
                showSettings = false
                onChangeModel()
            },
            onVADChange = { viewModel.setUseVAD(it) },
            onTimestampsChange = { viewModel.setEnableTimestamps(it) },
            onTranslationEnabledChange = { viewModel.setTranslationEnabled(it) },
            onSourceLanguageChange = { viewModel.setTranslationSourceLanguageCode(it) },
            onTargetLanguageChange = { viewModel.setTranslationTargetLanguageCode(it) },
            onPerformanceProfileChange = { viewModel.setPerformanceProfile(it) },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun TranscriptionTextDisplay(
    modifier: Modifier = Modifier,
    confirmedText: String,
    hypothesisText: String,
    isRecording: Boolean,
    translationEnabled: Boolean,
    translatedConfirmedText: String,
    translatedHypothesisText: String,
    translationSourceLanguage: String,
    translationTargetLanguage: String,
    translationDownloadStatus: String?,
    translationWarning: String?
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        if (confirmedText.isNotEmpty()) {
            Text(
                text = confirmedText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (hypothesisText.isNotEmpty()) {
            Text(
                text = hypothesisText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }

        if (confirmedText.isEmpty() && hypothesisText.isEmpty() && !isRecording) {
            Text(
                text = "Tap the microphone button to start transcribing.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        if (isRecording && confirmedText.isEmpty() && hypothesisText.isEmpty()) {
            Text(
                text = "Listening...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        if (translationEnabled && (translatedConfirmedText.isNotBlank() || translatedHypothesisText.isNotBlank())) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Translation ($translationSourceLanguage \u2192 $translationTargetLanguage)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (translatedConfirmedText.isNotBlank()) {
                Text(
                    text = translatedConfirmedText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (translatedHypothesisText.isNotBlank()) {
                Text(
                    text = translatedHypothesisText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    fontStyle = FontStyle.Italic
                )
            }
        }

        translationDownloadStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        translationWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ControlButtonsRow(
    isRecording: Boolean,
    onPickAudio: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPickAudio,
            enabled = !isRecording,
            modifier = Modifier.semantics { contentDescription = "Open WAV File" }
        ) {
            Icon(
                Icons.Filled.AudioFile,
                contentDescription = null,
                tint = if (!isRecording) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        RecordButton(
            isRecording = isRecording,
            onClick = onRecord
        )

        Spacer(modifier = Modifier.width(12.dp))

        IconButton(
            onClick = onSettings,
            modifier = Modifier.semantics { contentDescription = "Settings" }
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AudioInputModeCard(
    audioInputMode: AudioInputMode,
    systemCaptureReady: Boolean,
    systemCaptureSupported: Boolean,
    onInputModeChange: (AudioInputMode) -> Unit,
    onRequestSystemCapture: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        Text(
            text = "Audio Source",
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = audioInputMode == AudioInputMode.MICROPHONE,
                onClick = { onInputModeChange(AudioInputMode.MICROPHONE) },
                label = { Text("Voice") }
            )
            FilterChip(
                selected = audioInputMode == AudioInputMode.SYSTEM_PLAYBACK,
                onClick = {
                    onInputModeChange(AudioInputMode.SYSTEM_PLAYBACK)
                    // Auto-trigger permission dialog like iOS
                    if (systemCaptureSupported && !systemCaptureReady) {
                        onRequestSystemCapture()
                    }
                },
                enabled = systemCaptureSupported,
                label = { Text("System") }
            )
        }

        if (audioInputMode == AudioInputMode.SYSTEM_PLAYBACK) {
            val statusText = when {
                !systemCaptureSupported -> "System capture requires Android 10+."
                systemCaptureReady -> "System capture enabled. Carrier call audio may still be blocked by OS/device policy."
                else -> "Requesting system audio permission…"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    selectedModel: ModelInfo,
    useVAD: Boolean,
    enableTimestamps: Boolean,
    translationEnabled: Boolean,
    translationSourceLanguage: String,
    translationTargetLanguage: String,
    performanceProfile: PerformanceProfile,
    fullText: String,
    onCopyText: () -> Unit,
    onClearTranscription: () -> Unit,
    onChangeModel: () -> Unit,
    onVADChange: (Boolean) -> Unit,
    onTimestampsChange: (Boolean) -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onPerformanceProfileChange: (PerformanceProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyText,
                    enabled = fullText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "settings_copy_text" }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Text")
                }
                OutlinedButton(
                    onClick = onClearTranscription,
                    enabled = fullText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "settings_clear_transcription" }
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Performance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var performanceExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Execution profile")
                Box {
                    OutlinedButton(onClick = { performanceExpanded = true }) {
                        Text(performanceProfile.label)
                    }
                    DropdownMenu(
                        expanded = performanceExpanded,
                        onDismissRequest = { performanceExpanded = false }
                    ) {
                        PerformanceProfile.entries.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.label) },
                                onClick = {
                                    onPerformanceProfileChange(profile)
                                    performanceExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onChangeModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "settings_change_model" }
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Change Model")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = "Current Model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(selectedModel.displayName)
                Text(
                    selectedModel.parameterCount,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Transcription Settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice Activity Detection")
                Switch(checked = useVAD, onCheckedChange = onVADChange)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Timestamps")
                Switch(checked = enableTimestamps, onCheckedChange = onTimestampsChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Translation (ML Kit)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Translation")
                Switch(checked = translationEnabled, onCheckedChange = onTranslationEnabledChange)
            }

            if (translationEnabled) {
                var srcExpanded by remember { mutableStateOf(false) }
                var tgtExpanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Source")
                    Box {
                        OutlinedButton(onClick = { srcExpanded = true }) {
                            Text(translationSourceLanguage)
                        }
                        LanguageDropdown(
                            expanded = srcExpanded,
                            onDismiss = { srcExpanded = false },
                            onSelect = { onSourceLanguageChange(it); srcExpanded = false }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Target")
                    Box {
                        OutlinedButton(onClick = { tgtExpanded = true }) {
                            Text(translationTargetLanguage)
                        }
                        LanguageDropdown(
                            expanded = tgtExpanded,
                            onDismiss = { tgtExpanded = false },
                            onSelect = { onTargetLanguageChange(it); tgtExpanded = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun E2EEvidenceOverlay(result: E2ETestResult) {
    val statusLabel = when {
        result.skipped -> "UNSUPPORTED"
        result.pass -> "PASS"
        else -> "FAIL"
    }
    val statusBg = when {
        result.skipped -> MaterialTheme.colorScheme.secondaryContainer
        result.pass -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val statusFg = when {
        result.skipped -> MaterialTheme.colorScheme.onSecondaryContainer
        result.pass -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onError
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
            .semantics { contentDescription = "e2e_overlay" }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E2E EVIDENCE",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusFg,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Model: ${result.modelId}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Engine: ${result.engine}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Duration: ${"%.0f".format(result.durationMs)} ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Token/sec: ${"%.1f".format(result.tokensPerSecond)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (result.skipped && !result.error.isNullOrBlank()) {
            Text(
                text = "Reason: ${result.error.take(120)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        if (result.transcript.isNotEmpty()) {
            Text(
                text = result.transcript.take(120),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun RecordingStatsBar(viewModel: TranscriptionViewModel) {
    val bufferEnergy by viewModel.bufferEnergy.collectAsState()
    val bufferSeconds by viewModel.bufferSeconds.collectAsState()
    val tokensPerSecond by viewModel.tokensPerSecond.collectAsState()
    val micPeakLevel = remember(bufferEnergy) { bufferEnergy.takeLast(12).maxOrNull() ?: 0f }

    AudioVisualizer(
        energyLevels = bufferEnergy,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = FormatUtils.formatDuration(bufferSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format("mic %.3f", micPeakLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tokensPerSecond > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format("%.1f tok/s", tokensPerSecond),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResourceStatsBar(viewModel: TranscriptionViewModel, isRecording: Boolean, elapsedSeconds: Int) {
    val cpuPercent by viewModel.cpuPercent.collectAsState()
    val memoryMB by viewModel.memoryMB.collectAsState()
    val tokensPerSecond by viewModel.tokensPerSecond.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRecording) {
            Text(
                text = "${elapsedSeconds}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            text = String.format("CPU %.0f%%", cpuPercent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = String.format("RAM %.0f MB", memoryMB),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (tokensPerSecond > 0) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = String.format("%.1f tok/s", tokensPerSecond),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val COMMON_LANGUAGES = listOf(
    "en" to "English",
    "ja" to "Japanese",
    "zh" to "Chinese",
    "ko" to "Korean",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ru" to "Russian",
    "ar" to "Arabic",
    "hi" to "Hindi",
    "th" to "Thai",
    "vi" to "Vietnamese",
    "id" to "Indonesian",
    "tr" to "Turkish",
    "pl" to "Polish",
    "nl" to "Dutch",
    "sv" to "Swedish",
    "uk" to "Ukrainian",
)

private val PerformanceProfile.label: String
    get() = when (this) {
        PerformanceProfile.ECO -> "Eco"
        PerformanceProfile.BALANCED -> "Balanced"
        PerformanceProfile.MAX_PERFORMANCE -> "Max Performance"
        PerformanceProfile.BENCHMARK -> "Benchmark"
    }

@Composable
private fun LanguageDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        COMMON_LANGUAGES.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text("$name ($code)") },
                onClick = { onSelect(code) }
            )
        }
    }
}
