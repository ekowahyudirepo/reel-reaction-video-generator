package com.ekowahyudi.reelreaction.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ekowahyudi.reelreaction.MainViewModel
import com.ekowahyudi.reelreaction.data.Layout
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val layouts by viewModel.layouts.collectAsState()
    val fit by viewModel.videoFit.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.settingsMessage.collectAsState()

    // Layout yang sedang dipilihkan file-nya (picker sistem hanya mengembalikan Uri).
    var pickTarget by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<Layout?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pickTarget
        if (uri != null && id != null) viewModel.importLayoutImage(id, uri)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pickTarget
        if (uri != null && id != null) viewModel.importLayoutOutro(id, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            message?.let {
                SelectionContainer { Text(it, color = MaterialTheme.colorScheme.primary) }
            }

            LayoutPreviewCard(activeCount = layouts.count { it.isReady })

            Text("Layout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Setiap link yang diunduh menghasilkan satu video untuk setiap layout yang aktif. " +
                    "Layout hanya berbeda pada gambar bagian bawah dan video penutup.",
                style = MaterialTheme.typography.bodySmall,
            )

            layouts.forEach { layout ->
                LayoutCard(
                    layout = layout,
                    importing = importing,
                    onRename = { viewModel.renameLayout(layout.id, it) },
                    onActive = { viewModel.setLayoutActive(layout.id, it) },
                    onPickImage = {
                        pickTarget = layout.id
                        pickImage.launch("image/*")
                    },
                    onPickOutro = {
                        pickTarget = layout.id
                        pickVideo.launch("video/*")
                    },
                    onClearOutro = { viewModel.clearLayoutOutro(layout.id) },
                    onDelete = { pendingDelete = layout },
                )
            }
            Button(onClick = viewModel::addLayout) { Text("+ Tambah layout") }

            SectionCard("Tampilan video utama (bagian atas)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fit == VideoFit.CROP,
                        onClick = { viewModel.setVideoFit(VideoFit.CROP) },
                        label = { Text("Isi penuh (crop)") },
                    )
                    FilterChip(
                        selected = fit == VideoFit.FIT,
                        onClick = { viewModel.setVideoFit(VideoFit.FIT) },
                        label = { Text("Utuh (bar hitam)") },
                    )
                }
            }

            SectionCard("yt-dlp") {
                Text(
                    "Situs seperti YouTube sering berubah. Aplikasi memperbarui yt-dlp otomatis saat memproses, " +
                        "tetapi kamu juga bisa memperbaruinya manual jika unduhan gagal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::updateYtDlp) { Text("Perbarui yt-dlp") }
            }

            SectionCard("Diagnostik") {
                Text(
                    "Jika pembuatan video gagal di tahap edit, jalankan tes ini lalu kirim hasilnya.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = viewModel::runDiagnostics) { Text("Tes FFmpeg") }
            }
        }
    }

    pendingDelete?.let { layout ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus \"${layout.name}\"?") },
            text = { Text("Gambar dan video penutup milik layout ini ikut dihapus. Video yang sudah jadi tidak terpengaruh.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLayout(layout.id)
                    pendingDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun LayoutCard(
    layout: Layout,
    importing: Boolean,
    onRename: (String) -> Unit,
    onActive: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onPickOutro: () -> Unit,
    onClearOutro: () -> Unit,
    onDelete: () -> Unit,
) {
    // Teks nama dipegang lokal agar ketikan tidak terganggu oleh pembacaan ulang dari database.
    var name by remember(layout.id) { mutableStateOf(layout.name) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) onRename(it.trim())
                    },
                    label = { Text("Nama layout") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = layout.active, onCheckedChange = onActive)
                    Text(if (layout.active) "Aktif" else "Nonaktif", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text("Gambar bagian bawah", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val bitmap by produceState<ImageBitmap?>(null, layout.bottomImagePath) {
                val path = layout.bottomImagePath
                value = if (path == null) null else withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                        ?.asImageBitmap()
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Gambar bagian bawah ${layout.name}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text("Belum dipilih — layout ini belum bisa dipakai.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(enabled = !importing, onClick = onPickImage) {
                Text(if (layout.bottomImagePath == null) "Pilih gambar" else "Ganti gambar")
            }

            Text("Video penutup (outro)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                layout.outroName?.takeIf { layout.outroPath != null } ?: "Tidak ada — video final tanpa penutup.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !importing, onClick = onPickOutro) {
                    Text(if (layout.outroPath == null) "Pilih video" else "Ganti video")
                }
                if (layout.outroPath != null) {
                    TextButton(onClick = onClearOutro) { Text("Hapus") }
                }
            }

            TextButton(onClick = onDelete) { Text("Hapus layout", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Ilustrasi layout video final 9:16. */
@Composable
private fun LayoutPreviewCard(activeCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .size(width = 72.dp, height = 128.dp)
                    .background(Color.Black),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(2f)
                        .background(Color(0xFF5C6BC0)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Video", color = Color.White, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF8D6E63)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Gambar", color = Color.White, fontSize = 11.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Layout video 9:16 (1080×1920)", fontWeight = FontWeight.Bold)
                Text("Atas: video dari link (2/3 tinggi)", style = MaterialTheme.typography.bodySmall)
                Text("Bawah: gambar layout (1/3 tinggi)", style = MaterialTheme.typography.bodySmall)
                Text("Tengah: watermark “Platform @akun”", style = MaterialTheme.typography.bodySmall)
                Text("Akhir: video penutup layout (opsional)", style = MaterialTheme.typography.bodySmall)
                Text(
                    "$activeCount layout siap dipakai",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
