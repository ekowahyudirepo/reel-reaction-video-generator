package com.ekowahyudi.reelreaction.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * Menggambar teks watermark ke PNG transparan yang di-overlay oleh FFmpeg.
 * Memakai Canvas Android (bukan filter drawtext) sehingga tidak butuh font/libfreetype di FFmpeg
 * dan mendukung semua karakter (mis. nama akun berhuruf non-Latin).
 */
object WatermarkRenderer {

    private const val ALPHA_TEXT = 150 // 0..255, ±60% opacity
    private const val ALPHA_SHADOW = 100

    fun render(text: String, output: File) {
        val width = FfmpegCommands.WIDTH
        val height = FfmpegCommands.WATERMARK_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.argb(ALPHA_TEXT, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = 56f
            setShadowLayer(4f, 2f, 2f, Color.argb(ALPHA_SHADOW, 0, 0, 0))
        }
        // Kecilkan font bila teks terlalu panjang agar tetap muat di lebar bingkai.
        val maxWidth = width - 120f
        while (paint.measureText(text) > maxWidth && paint.textSize > 24f) {
            paint.textSize -= 2f
        }
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, width / 2f, baseline, paint)

        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
