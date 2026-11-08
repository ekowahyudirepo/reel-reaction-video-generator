package com.ekowahyudi.reelreaction.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.settingsStore by preferencesDataStore(name = "reel_settings")

/** Pengaturan global ringan (DataStore). Layout disimpan di database SQLite. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val VIDEO_FIT = stringPreferencesKey("video_fit")

        // Kunci pengaturan versi lama (satu gambar + satu outro), hanya dibaca untuk migrasi.
        val LEGACY_IMAGE = stringPreferencesKey("bottom_image_path")
        val LEGACY_OUTRO = stringPreferencesKey("outro_path")
        val LEGACY_OUTRO_NAME = stringPreferencesKey("outro_name")
    }

    val videoFit: Flow<VideoFit> = context.settingsStore.data.map { p ->
        runCatching { VideoFit.valueOf(p[Keys.VIDEO_FIT].orEmpty()) }.getOrDefault(VideoFit.CROP)
    }

    suspend fun setVideoFit(fit: VideoFit) {
        context.settingsStore.edit { it[Keys.VIDEO_FIT] = fit.name }
    }

    /**
     * Mengambil pengaturan lama sebagai Layout (sekali pakai) lalu menghapus kuncinya, supaya pengguna
     * versi sebelumnya tidak kehilangan gambar/outro yang sudah dipilih.
     */
    suspend fun takeLegacyLayout(): Layout? {
        val prefs = context.settingsStore.data.first()
        val image = prefs[Keys.LEGACY_IMAGE]
        val outro = prefs[Keys.LEGACY_OUTRO]
        val outroName = prefs[Keys.LEGACY_OUTRO_NAME]
        if (image == null && outro == null && outroName == null) return null
        context.settingsStore.edit {
            it.remove(Keys.LEGACY_IMAGE)
            it.remove(Keys.LEGACY_OUTRO)
            it.remove(Keys.LEGACY_OUTRO_NAME)
        }
        if (image == null || !File(image).exists()) return null
        return Layout(
            name = "Layout 1",
            bottomImagePath = image,
            outroPath = outro?.takeIf { File(it).exists() },
            outroName = outroName,
        )
    }
}
