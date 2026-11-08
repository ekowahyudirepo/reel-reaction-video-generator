package com.ekowahyudi.reelreaction.pipeline

import com.ekowahyudi.reelreaction.CrashLogger
import com.ekowahyudi.reelreaction.QueueService
import com.ekowahyudi.reelreaction.ReelApp
import com.ekowahyudi.reelreaction.data.JobStatus
import com.ekowahyudi.reelreaction.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Job yang sedang dikerjakan beserta progresnya. */
data class RunningInfo(val jobId: Long, val progress: Float, val label: String)

/**
 * Antrian serial: satu job dikerjakan sampai selesai (atau gagal) sebelum job berikutnya. Job yang
 * gagal tidak menghentikan antrian, dan bisa diulang kapan saja lewat [retry] / [retryAllFailed].
 */
class QueueManager(
    private val app: ReelApp,
    private val repo: Repository,
    private val scope: CoroutineScope,
) {
    private val processor = JobProcessor(app, repo)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _running = MutableStateFlow<RunningInfo?>(null)
    val running: StateFlow<RunningInfo?> = _running.asStateFlow()

    /** Hasil satu putaran antrian (sejak mulai sampai kosong). */
    data class RunSummary(val done: Int, val failed: Int)

    private val _active = MutableStateFlow(false)

    /** True selama ada putaran antrian yang sedang berjalan; dipakai [com.ekowahyudi.reelreaction.QueueService]. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    var lastSummary: RunSummary = RunSummary(0, 0)
        private set

    @Volatile
    private var currentJob: Job? = null

    init {
        // Satu pekerja tunggal: menjamin pengerjaan serial dan tidak ada sinyal yang terlewat.
        scope.launch {
            for (signal in wake) drain()
        }
    }

    /**
     * Membangunkan pekerja; aman dipanggil berkali-kali. Layanan latar depan dihidupkan hanya bila memang
     * ada job yang menunggu, supaya antrian tetap jalan walau layar mati / aplikasi ditinggal.
     */
    fun kick() {
        scope.launch {
            if (repo.nextQueued() != null) QueueService.start(app)
            wake.trySend(Unit)
        }
    }

    private suspend fun drain() {
        _active.value = true
        var done = 0
        var failed = 0
        try {
            while (true) {
                val job = repo.nextQueued() ?: break
                _running.value = RunningInfo(job.id, 0f, "Memulai…")
                val task = scope.launch {
                    try {
                        processor.process(job) { p -> _running.value = RunningInfo(job.id, p.fraction, p.label) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        CrashLogger.record(app, "Antrian job ${job.id}", e)
                    }
                }
                currentJob = task
                task.join()
                currentJob = null
                _running.value = null
                // Pengaman: bila karena kesalahan internal job masih berstatus antri, jangan diulang tanpa henti.
                if (repo.job(job.id)?.status == JobStatus.QUEUED) {
                    repo.setJobStatus(job.id, JobStatus.FAILED, "Kesalahan internal. Tekan Ulangi.")
                }
                when (repo.job(job.id)?.status) {
                    JobStatus.DONE -> done++
                    JobStatus.FAILED -> failed++
                    else -> Unit
                }
            }
        } finally {
            lastSummary = RunSummary(done, failed)
            _active.value = false
        }
    }

    /** Membatalkan job: yang sedang berjalan dihentikan, yang masih antri ditandai gagal (bisa diulang). */
    suspend fun cancel(jobId: Long) {
        if (_running.value?.jobId == jobId) {
            currentJob?.cancel()
        } else if (repo.job(jobId)?.status == JobStatus.QUEUED) {
            repo.setJobStatus(jobId, JobStatus.FAILED, "Dibatalkan.")
        }
    }

    suspend fun retry(jobId: Long) {
        repo.retryJob(jobId)
        kick()
    }

    suspend fun retryAllFailed(): Int {
        val count = repo.retryAllFailed()
        if (count > 0) kick()
        return count
    }
}
