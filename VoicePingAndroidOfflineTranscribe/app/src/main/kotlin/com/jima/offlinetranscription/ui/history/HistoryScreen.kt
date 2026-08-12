package com.voiceping.offlinetranscription.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceping.offlinetranscription.OfflineTranscriptionApp
import com.voiceping.offlinetranscription.history.TranscriptHistoryEntry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OfflineTranscriptionApp
    val entries by app.transcriptHistory.entries.collectAsState(initial = emptyList())
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("No saved transcripts yet.", style = MaterialTheme.typography.titleMedium)
                Text("Completed microphone, system-audio and file transcriptions are saved locally here.")
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.padding(padding)) {
                items(entries, key = { it.id }) { entry -> HistoryCard(entry) }
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: TranscriptHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${entry.source} · ${entry.modelId}", style = MaterialTheme.typography.labelLarge)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAtMillis)), style = MaterialTheme.typography.labelSmall)
            Text(entry.transcript, style = MaterialTheme.typography.bodyMedium, maxLines = 5)
            Text("${entry.backend} · ${entry.language} · ${"%.1f".format(entry.durationMillis / 1000.0)} s", style = MaterialTheme.typography.labelSmall)
        }
    }
}
