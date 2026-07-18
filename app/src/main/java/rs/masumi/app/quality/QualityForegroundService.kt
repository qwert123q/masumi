package rs.masumi.app.quality

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.core.quality.QualityJobStatus

class QualityForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private val cancellation = AtomicBoolean(false)

    @Volatile private var runner: QualityRepairCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.quality_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.quality_notification_starting)).build(),
                )
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                if (projectId == null || !SAFE_ID.matches(projectId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startQuality(projectId)) notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.quality_notification_busy)).build(),
                )
            }
            ACTION_CANCEL -> {
                cancellation.set(true)
                runner?.cancel()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.quality_notification_cancelling)).build(),
                )
            }
            else -> return START_NOT_STICKY
        }
        return if (ACTIVE_PROJECT.get() != null) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancellation.set(true)
        runner?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startQuality(projectId: String): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        cancellation.set(false)
        executor.execute {
            try {
                val active = QualityRepairCoordinator(filesDir.toPath().resolve("workspace"))
                runner = active
                active.run(projectId, cancellation::get, ::publishProgress)
            } catch (_: Throwable) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.quality_notification_failed_unknown))
                        .setOngoing(false)
                        .build(),
                )
            } finally {
                runner = null
                ACTIVE_PROJECT.compareAndSet(projectId, null)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return true
    }

    private fun publishProgress(progress: QualityProgress) {
        sendBroadcast(
            QualityStatusBroadcast.create(packageName, progress),
            "$packageName.permission.INTERNAL_QUALITY_STATUS",
        )
        val text = when (progress.status) {
            QualityJobStatus.QUEUED -> getString(R.string.quality_notification_starting)
            QualityJobStatus.RUNNING -> if (progress.errorCode == "QUALITY_REPAIRING") {
                getString(
                    R.string.quality_notification_repairing,
                    progress.terminalPageCount,
                    progress.totalPageCount,
                )
            } else {
                getString(
                    R.string.quality_notification_progress,
                    progress.terminalPageCount,
                    progress.totalPageCount,
                )
            }
            QualityJobStatus.SUCCEEDED -> getString(R.string.quality_notification_succeeded)
            QualityJobStatus.SUCCEEDED_WITH_WARNINGS -> getString(
                R.string.quality_notification_succeeded_warnings,
                progress.warningCount,
            )
            QualityJobStatus.BLOCKED -> getString(
                R.string.quality_notification_blocked,
                progress.blockingCount,
            )
            QualityJobStatus.CANCELLED -> getString(R.string.quality_notification_cancelled)
            QualityJobStatus.FAILED -> getString(
                R.string.quality_notification_failed,
                progress.errorCode.orEmpty(),
            )
        }
        val maximum = progress.totalPageCount.coerceAtLeast(1)
        notificationManager.notify(
            NOTIFICATION_ID,
            baseNotification(text)
                .setProgress(maximum, progress.terminalPageCount.coerceIn(0, maximum), false)
                .setOngoing(progress.status == QualityJobStatus.QUEUED || progress.status == QualityJobStatus.RUNNING)
                .build(),
        )
    }

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(getString(R.string.quality_notification_title))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                CONTENT_REQUEST_CODE,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.cancel_quality),
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE,
                    cancelIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_QUALITY"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_QUALITY"
        const val EXTRA_PROJECT_ID = "project_id"

        fun startIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, QualityForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, QualityForegroundService::class.java).setAction(ACTION_CANCEL)

        fun isTaskActive(): Boolean = ACTIVE_PROJECT.get() != null

        private const val NOTIFICATION_CHANNEL_ID = "automatic_visual_quality"
        private const val NOTIFICATION_ID = 2_006
        private const val CONTENT_REQUEST_CODE = 3_051
        private const val CANCEL_REQUEST_CODE = 3_052
        private const val WORKER_THREAD_NAME = "masumi-quality"
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
