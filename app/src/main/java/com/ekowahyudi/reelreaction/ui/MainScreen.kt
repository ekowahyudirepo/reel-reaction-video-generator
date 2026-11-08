package com.ekowahyudi.reelreaction.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekowahyudi.reelreaction.CrashLogger
import com.ekowahyudi.reelreaction.MainViewModel
import com.ekowahyudi.reelreaction.data.JobStatus
import com.ekowahyudi.reelreaction.data.JobWithOutputs
import com.ekowahyudi.reelreaction.data.OutputRow
import com.ekowahyudi.reelreaction.data.OutputStatus
import com.ekowahyudi.reelreaction.pipeline.RunningInfo
import com.ekowahyudi.reelreaction.pipeline.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val jobs by viewModel.jobs.collectAsState()
    val layouts by viewModel.layouts.collectAsState()
    val running by viewModel.running.collectAsState()
    val message by viewModel.message.collectAsState()
    val clipboard = LocalClipboardManager.current
    var pendingDelete by remember { mutableStateOf<JobWithOutputs?>(null) }
    var alsoDeleteVideos by remember { mutableStateOf(false) }
    var pendingVideoDelete by remember { mutableStateOf<OutputRow?>(null) }

    val readyLayouts = layouts.count { it.isReady }
    val failed = jobs.count { it.job.status == JobStatus.FAILED }
    val waiting = jobs.count { it.job.status == JobStatus.QUEUED }

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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CrashReportCard() }

            if (readyLayouts == 0) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Siapkan dulu", fontWeight = FontWeight.Bold)
                            Text("Buat minimal satu layout aktif dengan gambar bagian bawah di Pengaturan.")
                            TextButton(onClick = onOpenSettings) { Text("Buka Pengaturan") }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = viewModel.input,
                        onValueChange = { viewModel.input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Link video (boleh banyak, satu per baris)") },
                        minLines = 2,
                        maxLines = 6,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                val links = UrlUtils.extractAll(pasted)
                                val addition = if (links.isEmpty()) pasted else links.joinToString("\n")
                                viewModel.input = if (viewModel.input.isBlank()) addition else viewModel.input.trimEnd() + "\n" + addition
                            }
                        }) { Text("Tempel") }
                        Button(
                            onClick = viewModel::addToQueue,
                            enabled = viewModel.input.isNotBlank() && readyLayouts > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Tambah ke Antrian") }
                    }
                    if (readyLayouts > 1) {
                        Text(
                            "Setiap link menghasilkan $readyLayouts video (satu per layout aktif).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            message?.let { text ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                            TextButton(onClick = viewModel::dismissMessage) { Text("Tutup") }
                        }
                    }
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Antrian & riwayat (${jobs.size})" + if (waiting > 0) " · $waiting menunggu" else "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (failed > 0) {
                            TextButton(onClick = viewModel::retryAllFailed) { Text("Ulangi semua gagal ($failed)") }
                        }
                    }
                }
            }

            items(jobs, key = { it.job.id }) { item ->
                JobCard(
                    item = item,
                    running = running?.takeIf { it.jobId == item.job.id },
                    onRetry = { viewModel.retry(item.job.id) },
                    onCancel = { viewModel.cancel(item.job.id) },
                    onDelete = {
                        alsoDeleteVideos = false
                        pendingDelete = item
                    },
                    onDeleteVideo = { pendingVideoDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { item ->
        val videoCount = item.outputs.count { it.uri != null }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus data ini?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Catatan dihapus dari daftar sehingga link ini boleh diunduh lagi.")
                    if (videoCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { alsoDeleteVideos = !alsoDeleteVideos },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoDeleteVideos, onCheckedChange = { alsoDeleteVideos = it })
                            Text("Hapus juga $videoCount video di perangkat")
                        }
                    } else {
                        Text("Video yang sudah ada di galeri tidak terpengaruh.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJob(item.job.id, deleteVideos = alsoDeleteVideos && videoCount > 0)
                    pendingDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
        )
    }

    pendingVideoDelete?.let { output ->
        AlertDialog(
            onDismissRequest = { pendingVideoDelete = null },
            title = { Text("Hapus video dari perangkat?") },
            text = {
                Text(
                    "File \"${output.displayName ?: output.layoutName}\" dihapus dari galeri dan tidak bisa " +
                        "dikembalikan. Catatan link tetap ada, jadi link ini tetap dianggap sudah diunduh " +
                        "(pakai \"Hapus data\" bila ingin mengunduhnya lagi).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOutputVideo(output)
                    pendingVideoDelete = null
                }) { Text("Hapus video") }
            },
            dismissButton = { TextButton(onClick = { pendingVideoDelete = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun JobCard(
    item: JobWithOutputs,
    running: RunningInfo?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDeleteVideo: (OutputRow) -> Unit,
) {
    val job = item.job
    var expanded by remember { mutableStateOf(false) }
    val container = when (job.status) {
        JobStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        JobStatus.DONE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                job.title ?: job.url,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.title != null) {
                Text(job.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            job.watermark?.let { Text("Watermark: $it", style = MaterialTheme.typography.bodySmall) }

            Text(
                when (job.status) {
                    JobStatus.QUEUED -> "⏳ Menunggu antrian"
                    JobStatus.RUNNING -> "▶ Diproses" + (running?.let { " — ${(it.progress * 100).toInt()}% · ${it.label}" } ?: "")
                    JobStatus.DONE -> "✓ Selesai"
                    JobStatus.FAILED -> "✗ Gagal"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (job.status == JobStatus.RUNNING) {
                LinearProgressIndicator(progress = { running?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }

            job.error?.let { error ->
                SelectionContainer {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (error.lines().size > 3 || error.length > 160) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ringkas" else "Lihat detail") }
                }
            }

            item.outputs.forEach { output -> OutputRowView(output, expanded, onDeleteVideo) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.status == JobStatus.FAILED) {
                    Button(onClick = onRetry) { Text("Ulangi") }
                }
                if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) {
                    OutlinedButton(onClick = onCancel) { Text("Batalkan") }
                }
                if (job.status != JobStatus.RUNNING) {
                    TextButton(onClick = onDelete) { Text("Hapus data") }
                }
            }
        }
    }
}

@Composable
private fun OutputRowView(output: OutputRow, expanded: Boolean, onDeleteVideo: (OutputRow) -> Unit) {
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (output.status) {
                    OutputStatus.DONE -> "✓"
                    OutputStatus.FAILED -> "✗"
                    OutputStatus.PENDING -> "○"
                },
            )
            Column(Modifier.weight(1f)) {
                Text(output.layoutName, style = MaterialTheme.typography.bodyMedium)
                output.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (output.status == OutputStatus.DONE && output.uri == null) {
                    Text(
                        "Video sudah dihapus dari perangkat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                output.error?.let {
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val uri = output.uri?.let(Uri::parse)
            if (output.status == OutputStatus.DONE && uri != null) {
                TextButton(onClick = { playVideo(context, uri) }) { Text("Putar") }
                TextButton(onClick = { shareVideo(context, uri) }) { Text("Bagikan") }
                TextButton(
                    onClick = { onDeleteVideo(output) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Hapus") }
            }
        }
    }
}

/** Menampilkan laporan error terakhir (jika aplikasi sempat berhenti) agar bisa disalin dan dikirim. */
@Composable
private fun CrashReportCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashLogger.read(context)) }
    val text = report ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aplikasi sempat berhenti", fontWeight = FontWeight.Bold)
            Text(
                text.take(700),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Salin laporan") }
                TextButton(onClick = {
                    CrashLogger.clear(context)
                    report = null
                }) { Text("Tutup") }
            }
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
        // Tidak ada pemutar video, atau file sudah dihapus dari galeri.
    } catch (_: SecurityException) {
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
