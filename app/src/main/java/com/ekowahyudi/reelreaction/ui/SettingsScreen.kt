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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.settingsMessage.collectAsState()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importBottomImage)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importOutro)
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

            LayoutPreviewCard()

            SectionCard("Gambar bagian bawah") {
                val bitmap by produceState<ImageBitmap?>(null, settings.bottomImagePath) {
                    val path = settings.bottomImagePath
                    value = if (path == null) null else withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                            ?.asImageBitmap()
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Gambar bagian bawah",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text("Belum dipilih", color = MaterialTheme.colorScheme.error)
                Button(enabled = !importing, onClick = { pickImage.launch("image/*") }) {
                    Text(if (settings.bottomImagePath == null) "Pilih gambar" else "Ganti gambar")
                }
            }

            SectionCard("Video penutup (outro)") {
                Text(
                    settings.outroName ?: "Belum dipilih — video final tanpa penutup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !importing, onClick = { pickVideo.launch("video/*") }) {
                        Text(if (settings.outroPath == null) "Pilih video" else "Ganti video")
                    }
                    if (settings.outroPath != null) {
                        TextButton(onClick = viewModel::clearOutro) { Text("Hapus") }
                    }
                }
            }

            SectionCard("Tampilan video utama (bagian atas)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.videoFit == VideoFit.CROP,
                        onClick = { viewModel.setVideoFit(VideoFit.CROP) },
                        label = { Text("Isi penuh (crop)") },
                    )
                    FilterChip(
                        selected = settings.videoFit == VideoFit.FIT,
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
private fun LayoutPreviewCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.size(width = 72.dp, height = 128.dp).background(Color.Black)) {
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
                Text("Bawah: gambar pilihanmu (1/3 tinggi)", style = MaterialTheme.typography.bodySmall)
                Text("Tengah: watermark “Platform @akun”", style = MaterialTheme.typography.bodySmall)
                Text("Akhir: video penutup (opsional)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
