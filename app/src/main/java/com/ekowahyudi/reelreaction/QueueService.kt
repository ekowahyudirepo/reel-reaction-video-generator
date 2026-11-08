package com.ekowahyudi.reelreaction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ekowahyudi.reelreaction.data.JobStatus
import com.ekowahyudi.reelreaction.data.JobWithOutputs
import com.ekowahyudi.reelreaction.pipeline.RunningInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Layanan latar depan yang menjaga antrian tetap berjalan saat layar mati atau aplikasi ditinggal.
 * Ia tidak mengerjakan antrian sendiri (itu tugas [com.ekowahyudi.reelreaction.pipeline.QueueManager]);
 * ia hanya menjaga proses tetap hidup (notifikasi + wake lock), menampilkan progres, lalu berhenti sendiri
 * ketika antrian kosong.
 */
class QueueService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        // Wajib segera memanggil startForeground (batas 5 detik sejak startForegroundService).
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Menyiapkan antrian…", null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
        // Jika sistem mematikan layanan, tidak perlu dihidupkan ulang: job yang terputus ditandai
        // gagal dan antrian dilanjutkan saat aplikasi dibuka lagi.
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val app = application as ReelApp
        val queue = app.queue

        // Antrian dikerjakan di QueueManager; tunggu sampai ia benar-benar mulai (tidak ada pekerjaan = berhenti).
        val started = withTimeoutOrNull(START_WAIT_MS) { queue.active.first { it } }
        if (started == null) {
            shutDown()
            return
        }

        val updater = scope.launch {
            combine(queue.running, app.repo.observeJobs()) { running, jobs -> describe(running, jobs) }
                .distinctUntilChanged()
                .collect { s -> notify(buildNotification(s.title, s.text, s.percent, s.subText)) }
        }
        queue.active.first { !it }
        updater.cancel()

        val summary = queue.lastSummary
        if (summary.done + summary.failed > 0) postSummary(summary.done, summary.failed)
        shutDown()
    }

    private data class State(val title: String, val text: String, val percent: Int?, val subText: String?)

    private fun describe(running: RunningInfo?, jobs: List<JobWithOutputs>): State {
        val waiting = jobs.count { it.job.status == JobStatus.QUEUED }
        val subText = if (waiting > 0) "$waiting menunggu" else null
        if (running == null) return State("Reel Reaction", "Menyiapkan job berikutnya…", null, subText)
        val job = jobs.firstOrNull { it.job.id == running.jobId }?.job
        val title = job?.title ?: job?.url ?: "Memproses link"
        return State(title, running.label, (running.progress * 100).toInt().coerceIn(0, 100), subText)
    }

    private fun buildNotification(
        title: String,
        text: String?,
        percent: Int?,
        subText: String? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()
    }

    private fun postSummary(done: Int, failed: Int) {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("$done job selesai")
            if (failed > 0) append(", $failed gagal (buka aplikasi lalu tekan Ulangi)")
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Antrian selesai")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(notification, RESULT_ID)
    }

    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        // Tanpa izin notifikasi, notify() diabaikan sistem; layanan tetap berjalan.
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Progres antrian", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menampilkan progres video yang sedang dibuat"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Antrian selesai", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Pemberitahuan saat seluruh antrian selesai"
            },
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReelReaction:queue").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: layanan jenis dataSync dibatasi sekitar 6 jam per 24 jam; harus berhenti saat waktunya habis. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutDown()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "queue_progress"
        private const val CHANNEL_RESULT = "queue_result"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_ID = 1002
        private const val START_WAIT_MS = 10_000L
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        /** Menghidupkan layanan; kegagalan (mis. aplikasi di latar belakang) tidak fatal. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java))
            }
        }
    }
}
