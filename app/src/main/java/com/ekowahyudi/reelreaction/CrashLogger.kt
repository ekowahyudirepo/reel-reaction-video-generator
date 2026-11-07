package com.ekowahyudi.reelreaction

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menyimpan stack trace crash ke file sehingga penyebabnya bisa dilihat di layar saat aplikasi dibuka lagi
 * (tanpa perlu komputer / logcat).
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 6000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, "Thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, where: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            file(context).writeText("[$stamp] $where\n${trace.take(MAX_CHARS)}")
        }
    }

    /** Mengembalikan isi laporan crash terakhir (jika ada). */
    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
