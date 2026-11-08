package com.ekowahyudi.reelreaction.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Database SQLite aplikasi (reel.db). Tanpa library tambahan agar build tetap sederhana. */
class AppDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE layouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                bottom_image_path TEXT,
                outro_path TEXT,
                outro_name TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                position INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                url_key TEXT NOT NULL,
                status TEXT NOT NULL,
                title TEXT,
                watermark TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        // Satu catatan per URL kanonis: inilah yang mencegah unduhan ganda.
        db.execSQL("CREATE UNIQUE INDEX idx_jobs_url_key ON jobs(url_key)")
        db.execSQL("CREATE INDEX idx_jobs_status ON jobs(status)")
        db.execSQL(
            """
            CREATE TABLE outputs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                layout_id INTEGER NOT NULL,
                layout_name TEXT NOT NULL,
                status TEXT NOT NULL,
                uri TEXT,
                display_name TEXT,
                error TEXT,
                UNIQUE(job_id, layout_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Belum ada versi lama yang perlu dimigrasi.
    }

    private companion object {
        const val NAME = "reel.db"
        const val VERSION = 1
    }
}
