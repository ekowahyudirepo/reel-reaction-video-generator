package com.ekowahyudi.reelreaction.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.ekowahyudi.reelreaction.pipeline.UrlNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Akses database. Semua fungsi berjalan di thread IO. Setiap perubahan menaikkan [version]
 * sehingga flow `observe*` otomatis memuat ulang data.
 */
class Repository(context: Context) {

    private val helper = AppDatabase(context)
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }
    private val version = MutableStateFlow(0L)
    private val enqueueLock = Mutex()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun <T> write(block: () -> T): T = io(block).also { version.update { it + 1 } }

    // ───────────────────────── Layout ─────────────────────────

    fun observeLayouts(): Flow<List<Layout>> = version.map { loadLayouts() }.flowOn(Dispatchers.IO)

    suspend fun layouts(): List<Layout> = io { loadLayouts() }

    suspend fun layout(id: Long): Layout? = io { loadLayouts().firstOrNull { it.id == id } }

    suspend fun layoutCount(): Int = io {
        db.rawQuery("SELECT COUNT(*) FROM layouts", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun addLayout(layout: Layout): Long = write {
        val position = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM layouts", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.insertOrThrow("layouts", null, layoutValues(layout.copy(position = position)))
    }

    suspend fun updateLayout(layout: Layout) = write {
        db.update("layouts", layoutValues(layout), "id = ?", arrayOf(layout.id.toString()))
    }

    suspend fun deleteLayout(id: Long) = write {
        db.delete("layouts", "id = ?", arrayOf(id.toString()))
    }

    private fun layoutValues(l: Layout) = ContentValues().apply {
        put("name", l.name)
        put("bottom_image_path", l.bottomImagePath)
        put("outro_path", l.outroPath)
        put("outro_name", l.outroName)
        put("active", if (l.active) 1 else 0)
        put("position", l.position)
    }

    private fun loadLayouts(): List<Layout> =
        db.rawQuery(
            "SELECT id, name, bottom_image_path, outro_path, outro_name, active, position " +
                "FROM layouts ORDER BY position ASC, id ASC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val image = c.str(2)
                    val outro = c.str(3)
                    add(
                        Layout(
                            id = c.getLong(0),
                            name = c.getString(1),
                            bottomImagePath = image,
                            // Outro yang filenya hilang dianggap tidak ada.
                            outroPath = outro?.takeIf { File(it).exists() },
                            outroName = c.str(4),
                            active = c.getInt(5) == 1,
                            position = c.getInt(6),
                            imageOk = image?.let { File(it).exists() } == true,
                        ),
                    )
                }
            }
        }

    // ───────────────────────── Job / antrian ─────────────────────────

    fun observeJobs(): Flow<List<JobWithOutputs>> = version.map { loadJobs() }.flowOn(Dispatchers.IO)

    /**
     * Menambah URL ke antrian. Bila URL (kanonis) sudah punya catatan — selesai, gagal, atau sedang antri —
     * hasilnya [EnqueueResult.Duplicate]. Catatan hanya hilang bila datanya dihapus pengguna;
     * menghapus file video di galeri TIDAK menghapus catatan, jadi URL itu tetap dianggap sudah diunduh.
     */
    suspend fun enqueue(url: String): EnqueueResult = enqueueLock.withLock {
        val key = UrlNormalizer.key(url)
        val result = io<EnqueueResult> {
            db.rawQuery("SELECT status, title FROM jobs WHERE url_key = ? LIMIT 1", arrayOf(key)).use { c ->
                if (c.moveToFirst()) {
                    return@io EnqueueResult.Duplicate(parseJobStatus(c.getString(0)), c.str(1))
                }
            }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("url", url)
                put("url_key", key)
                put("status", JobStatus.QUEUED.name)
                put("created_at", now)
                put("updated_at", now)
            }
            try {
                EnqueueResult.Added(db.insertOrThrow("jobs", null, values))
            } catch (e: SQLiteConstraintException) {
                EnqueueResult.Duplicate(JobStatus.QUEUED, null)
            }
        }
        if (result is EnqueueResult.Added) version.update { it + 1 }
        result
    }

    suspend fun nextQueued(): JobRow? = io {
        db.rawQuery("SELECT $JOB_COLUMNS FROM jobs WHERE status = ? ORDER BY id ASC LIMIT 1", arrayOf(JobStatus.QUEUED.name))
            .use { if (it.moveToFirst()) it.toJob() else null }
    }

    suspend fun job(id: Long): JobRow? = io {
        db.rawQuery("SELECT $JOB_COLUMNS FROM jobs WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toJob() else null }
    }

    suspend fun setJobStatus(id: Long, status: JobStatus, error: String? = null) = write {
        val values = ContentValues().apply {
            put("status", status.name)
            put("error", error)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("jobs", values, "id = ?", arrayOf(id.toString()))
    }

    suspend fun setJobMeta(id: Long, title: String?, watermark: String?) = write {
        val values = ContentValues().apply {
            put("title", title)
            put("watermark", watermark)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("jobs", values, "id = ?", arrayOf(id.toString()))
    }

    /** Menghapus data job (dan output-nya). Setelah ini URL yang sama boleh diantrekan lagi. */
    suspend fun deleteJob(id: Long) = write {
        db.delete("jobs", "id = ?", arrayOf(id.toString()))
    }

    /** Job yang masih berstatus RUNNING saat aplikasi dibuka berarti terputus; tandai gagal agar bisa diulang. */
    suspend fun recoverInterrupted() = write {
        val values = ContentValues().apply {
            put("status", JobStatus.FAILED.name)
            put("error", "Terhenti karena aplikasi ditutup. Tekan Ulangi.")
            put("updated_at", System.currentTimeMillis())
        }
        db.update("jobs", values, "status = ?", arrayOf(JobStatus.RUNNING.name))
    }

    /** Mengembalikan job gagal ke antrian; hanya output yang gagal yang akan dikerjakan ulang. */
    suspend fun retryJob(id: Long) = write {
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.update(
                "jobs",
                ContentValues().apply {
                    put("status", JobStatus.QUEUED.name)
                    putNull("error")
                    put("updated_at", now)
                },
                "id = ? AND status = ?",
                arrayOf(id.toString(), JobStatus.FAILED.name),
            )
            db.update(
                "outputs",
                ContentValues().apply {
                    put("status", OutputStatus.PENDING.name)
                    putNull("error")
                },
                "job_id = ? AND status = ?",
                arrayOf(id.toString(), OutputStatus.FAILED.name),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Mengembalikan semua job gagal ke antrian; mengembalikan jumlahnya. */
    suspend fun retryAllFailed(): Int {
        val ids = io {
            db.rawQuery("SELECT id FROM jobs WHERE status = ? ORDER BY id ASC", arrayOf(JobStatus.FAILED.name))
                .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }
        ids.forEach { retryJob(it) }
        return ids.size
    }

    private fun loadJobs(): List<JobWithOutputs> {
        val jobs = db.rawQuery("SELECT $JOB_COLUMNS FROM jobs ORDER BY id DESC LIMIT $MAX_JOBS_SHOWN", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toJob()) } }
        if (jobs.isEmpty()) return emptyList()
        val byJob = loadOutputsFor(jobs.map { it.id }).groupBy { it.jobId }
        return jobs.map { JobWithOutputs(it, byJob[it.id].orEmpty()) }
    }

    // ───────────────────────── Output per layout ─────────────────────────

    suspend fun outputs(jobId: Long): List<OutputRow> = io { loadOutputsFor(listOf(jobId)) }

    /**
     * Menyiapkan satu baris output per layout aktif. Output yang sudah selesai dibiarkan; output belum
     * selesai milik layout yang sudah tidak aktif/dihapus dibuang agar job bisa dinyatakan selesai.
     */
    suspend fun ensureOutputs(jobId: Long, layouts: List<Layout>) = write {
        db.beginTransaction()
        try {
            for (l in layouts) {
                val values = ContentValues().apply {
                    put("job_id", jobId)
                    put("layout_id", l.id)
                    put("layout_name", l.name)
                    put("status", OutputStatus.PENDING.name)
                }
                db.insertWithOnConflict("outputs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL(
                    "UPDATE outputs SET layout_name = ? WHERE job_id = ? AND layout_id = ? AND status != ?",
                    arrayOf<Any?>(l.name, jobId, l.id, OutputStatus.DONE.name),
                )
            }
            if (layouts.isNotEmpty()) {
                val placeholders = layouts.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM outputs WHERE job_id = ? AND status != ? AND layout_id NOT IN ($placeholders)",
                    arrayOf<Any?>(jobId, OutputStatus.DONE.name, *layouts.map { it.id }.toTypedArray()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun setOutput(
        id: Long,
        status: OutputStatus,
        uri: String? = null,
        displayName: String? = null,
        error: String? = null,
    ) = write {
        val values = ContentValues().apply {
            put("status", status.name)
            put("uri", uri)
            put("display_name", displayName)
            put("error", error)
        }
        db.update("outputs", values, "id = ?", arrayOf(id.toString()))
    }

    private fun loadOutputsFor(jobIds: List<Long>): List<OutputRow> {
        if (jobIds.isEmpty()) return emptyList()
        val placeholders = jobIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT id, job_id, layout_id, layout_name, status, uri, display_name, error " +
                "FROM outputs WHERE job_id IN ($placeholders) ORDER BY id ASC",
            jobIds.map { it.toString() }.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        OutputRow(
                            id = c.getLong(0),
                            jobId = c.getLong(1),
                            layoutId = c.getLong(2),
                            layoutName = c.getString(3),
                            status = parseOutputStatus(c.getString(4)),
                            uri = c.str(5),
                            displayName = c.str(6),
                            error = c.str(7),
                        ),
                    )
                }
            }
        }
    }

    // ───────────────────────── Util ─────────────────────────

    private fun Cursor.str(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.toJob() = JobRow(
        id = getLong(0),
        url = getString(1),
        urlKey = getString(2),
        status = parseJobStatus(getString(3)),
        title = str(4),
        watermark = str(5),
        error = str(6),
        createdAt = getLong(7),
        updatedAt = getLong(8),
    )

    private fun parseJobStatus(s: String): JobStatus = runCatching { JobStatus.valueOf(s) }.getOrDefault(JobStatus.FAILED)

    private fun parseOutputStatus(s: String): OutputStatus =
        runCatching { OutputStatus.valueOf(s) }.getOrDefault(OutputStatus.FAILED)

    private companion object {
        const val JOB_COLUMNS = "id, url, url_key, status, title, watermark, error, created_at, updated_at"
        const val MAX_JOBS_SHOWN = 300
    }
}
