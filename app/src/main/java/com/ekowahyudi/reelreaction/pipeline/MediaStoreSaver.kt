package com.ekowahyudi.reelreaction.pipeline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Hasil menghapus video dari galeri. */
enum class DeleteResult {
    /** File berhasil dihapus. */
    DELETED,

    /** File sudah tidak ada (mis. sudah dihapus lewat Galeri); dianggap berhasil. */
    ALREADY_GONE,

    /** Sistem menolak (file bukan milik aplikasi ini, mis. setelah pasang ulang). Hapus lewat Galeri. */
    DENIED,
}

object MediaStoreSaver {

    const val FOLDER = "ReelReaction"

    /** Menghapus video final dari galeri/penyimpanan perangkat. */
    fun deleteVideo(context: Context, uriString: String?): DeleteResult {
        if (uriString.isNullOrBlank()) return DeleteResult.ALREADY_GONE
        return try {
            val rows = context.contentResolver.delete(Uri.parse(uriString), null, null)
            if (rows > 0) DeleteResult.DELETED else DeleteResult.ALREADY_GONE
        } catch (_: SecurityException) {
            DeleteResult.DENIED
        } catch (_: IllegalArgumentException) {
            DeleteResult.ALREADY_GONE
        }
    }

    /** Menyimpan video final ke galeri (Movies/ReelReaction). */
    fun saveVideo(context: Context, source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat file di galeri")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Gagal menulis file di galeri")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
