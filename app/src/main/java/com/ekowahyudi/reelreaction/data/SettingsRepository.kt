package com.ekowahyudi.reelreaction.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val Context.settingsStore by preferencesDataStore(name = "reel_settings")

data class ReelSettings(
    val bottomImagePath: String? = null,
    val outroPath: String? = null,
    val outroName: String? = null,
    val videoFit: VideoFit = VideoFit.CROP,
)

/** Menyimpan pengaturan. File yang dipilih disalin ke penyimpanan internal aplikasi agar selalu bisa diakses. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BOTTOM_IMAGE = stringPreferencesKey("bottom_image_path")
        val OUTRO = stringPreferencesKey("outro_path")
        val OUTRO_NAME = stringPreferencesKey("outro_name")
        val VIDEO_FIT = stringPreferencesKey("video_fit")
    }

    val settings: Flow<ReelSettings> = context.settingsStore.data.map { p ->
        ReelSettings(
            bottomImagePath = p[Keys.BOTTOM_IMAGE]?.takeIf { File(it).exists() },
            outroPath = p[Keys.OUTRO]?.takeIf { File(it).exists() },
            outroName = p[Keys.OUTRO_NAME],
            videoFit = runCatching { VideoFit.valueOf(p[Keys.VIDEO_FIT].orEmpty()) }
                .getOrDefault(VideoFit.CROP),
        )
    }

    private val assetsDir: File
        get() = File(context.filesDir, "assets").apply { mkdirs() }

    /** Gambar dinormalisasi ke JPEG (orientasi EXIF diterapkan, sisi terpanjang maks 2160 px). */
    suspend fun importBottomImage(uri: Uri) = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt(),
                    (info.size.height * scale).roundToInt(),
                )
            }
        }
        val file = File(assetsDir, "bottom_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        replace(Keys.BOTTOM_IMAGE, file.absolutePath)
    }

    suspend fun importOutro(uri: Uri) = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "outro.mp4"
        val file = File(assetsDir, "outro_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: error("Tidak bisa membuka video yang dipilih")
        replace(Keys.OUTRO, file.absolutePath)
        context.settingsStore.edit { it[Keys.OUTRO_NAME] = name }
    }

    suspend fun clearOutro() = withContext(Dispatchers.IO) {
        val old = context.settingsStore.data.first()[Keys.OUTRO]
        context.settingsStore.edit {
            it.remove(Keys.OUTRO)
            it.remove(Keys.OUTRO_NAME)
        }
        old?.let { File(it).delete() }
        Unit
    }

    suspend fun setVideoFit(fit: VideoFit) {
        context.settingsStore.edit { it[Keys.VIDEO_FIT] = fit.name }
    }

    /** Simpan path baru lalu hapus file lama supaya penyimpanan tidak menumpuk. */
    private suspend fun replace(key: Preferences.Key<String>, newPath: String) {
        val old = context.settingsStore.data.first()[key]
        context.settingsStore.edit { it[key] = newPath }
        if (old != null && old != newPath) File(old).delete()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private companion object {
        const val MAX_IMAGE_SIDE = 2160
    }
}
