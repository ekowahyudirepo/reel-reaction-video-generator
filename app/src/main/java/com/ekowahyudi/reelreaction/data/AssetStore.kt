package com.ekowahyudi.reelreaction.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Menyalin gambar/video pilihan pengguna ke penyimpanan internal aplikasi agar selalu bisa diakses. */
object AssetStore {

    private const val MAX_IMAGE_SIDE = 2160

    private fun dir(context: Context) = File(context.filesDir, "assets").apply { mkdirs() }

    /** Gambar dinormalisasi ke JPEG (orientasi EXIF diterapkan, sisi terpanjang maks 2160 px). */
    suspend fun importImage(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
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
        val file = File(dir(context), "bottom_${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        file
    }

    /** Mengembalikan (file salinan, nama asli). */
    suspend fun importVideo(context: Context, uri: Uri): Pair<File, String> = withContext(Dispatchers.IO) {
        val name = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "outro.mp4"
        val file = File(dir(context), "outro_${System.nanoTime()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: error("Tidak bisa membuka video yang dipilih")
        file to name
    }

    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }
}
