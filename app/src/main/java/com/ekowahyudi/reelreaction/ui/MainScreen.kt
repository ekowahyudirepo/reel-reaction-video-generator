package com.ekowahyudi.reelreaction.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ekowahyudi.reelreaction.MainViewModel
import com.ekowahyudi.reelreaction.ProcessState
import com.ekowahyudi.reelreaction.pipeline.PipelineProgress
import com.ekowahyudi.reelreaction.pipeline.PipelineResult
import com.ekowahyudi.reelreaction.pipeline.Stage
import com.ekowahyudi.reelreaction.pipeline.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    val running = state is ProcessState.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reel Reaction") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.bottomImagePath == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Siapkan dulu", fontWeight = FontWeight.Bold)
                        Text("Pilih gambar untuk bagian bawah video (dan video penutup, opsional) di Pengaturan.")
                        TextButton(onClick = onOpenSettings) { Text("Buka Pengaturan") }
                    }
                }
            }

            OutlinedTextField(
                value = viewModel.url,
                onValueChange = { viewModel.url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Link video (YouTube, TikTok, dll.)") },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    TextButton(
                        enabled = !running,
                        onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                viewModel.url = UrlUtils.extractUrl(pasted) ?: pasted
                            }
                        },
                    ) { Text("Tempel") }
                },
            )

            if (running) {
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Batalkan")
                }
            } else {
                Button(
                    onClick = viewModel::start,
                    enabled = viewModel.url.isNotBlank() && settings.bottomImagePath != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Buat Video") }
            }

            when (val s = state) {
                is ProcessState.Running -> ProgressCard(s.progress, hasOutro = settings.outroPath != null)
                is ProcessState.Done -> ResultCard(s.result, onDismiss = viewModel::dismissResult)
                is ProcessState.Failed -> ErrorCard(s.message, onDismiss = viewModel::dismissResult)
                ProcessState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: PipelineProgress, hasOutro: Boolean) {
    val stages = Stage.entries.filter { hasOutro || it != Stage.OUTRO }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stages.forEach { stage ->
                val mark = when {
                    stage.ordinal < progress.stage.ordinal -> "✓"
                    stage == progress.stage -> "▶"
                    else -> "○"
                }
                val active = stage == progress.stage
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(mark)
                    Text(
                        text = if (active && progress.detail.isNotBlank()) "${stage.label} — ${progress.detail}" else stage.label,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
            progress.watermark?.let { Text("Watermark: $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Biarkan aplikasi tetap terbuka sampai selesai.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultCard(result: PipelineResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Video siap! 🎬", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            result.title?.let { Text(it, maxLines = 2) }
            Text("Watermark: ${result.watermark}", style = MaterialTheme.typography.bodySmall)
            Text("Tersimpan di galeri: Movies/${result.displayName}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { playVideo(context, result.uri) }) { Text("Putar") }
                OutlinedButton(onClick = { shareVideo(context, result.uri) }) { Text("Bagikan") }
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gagal", fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    }
}

private fun playVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Tidak ada pemutar video terpasang; video tetap ada di galeri.
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Bagikan video"))
}
