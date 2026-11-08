package com.ekowahyudi.reelreaction.data

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }

enum class OutputStatus { PENDING, DONE, FAILED }

/**
 * Satu layout = satu pasang (gambar bagian bawah, video penutup). Satu unduhan menghasilkan
 * satu video untuk setiap layout yang aktif.
 */
data class Layout(
    val id: Long = 0,
    val name: String,
    val bottomImagePath: String? = null,
    val outroPath: String? = null,
    val outroName: String? = null,
    val active: Boolean = true,
    val position: Int = 0,
    /** Diisi repository saat membaca: file gambar benar-benar ada di penyimpanan. */
    val imageOk: Boolean = false,
) {
    val isReady: Boolean get() = active && imageOk
}

data class JobRow(
    val id: Long,
    val url: String,
    val urlKey: String,
    val status: JobStatus,
    val title: String?,
    val watermark: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class OutputRow(
    val id: Long,
    val jobId: Long,
    val layoutId: Long,
    val layoutName: String,
    val status: OutputStatus,
    val uri: String?,
    val displayName: String?,
    val error: String?,
)

data class JobWithOutputs(val job: JobRow, val outputs: List<OutputRow>)

/** Hasil menambah URL ke antrian. */
sealed interface EnqueueResult {
    data class Added(val id: Long) : EnqueueResult

    /** URL (setelah dinormalisasi) sudah punya catatan; dihapus dulu datanya agar bisa diunduh lagi. */
    data class Duplicate(val status: JobStatus, val title: String?) : EnqueueResult
}
