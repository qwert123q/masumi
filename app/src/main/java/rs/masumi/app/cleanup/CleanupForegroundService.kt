package rs.masumi.app.cleanup

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
import rs.masumi.app.ForegroundTaskWakeLock
import rs.masumi.core.cleanup.CleanupJobStatus

class CleanupForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var taskWakeLock: ForegroundTaskWakeLock
    private val cancellation = AtomicBoolean(false)

    @Volatile private var runner: CleanupRunner? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
        notificationManager = getSystemService(NotificationManager::class.java)
        taskWakeLock = ForegroundTaskWakeLock(this, "cleanup")
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.cleanup_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, baseNotification(getString(R.string.cleanup_notification_starting)).build())
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                if (projectId == null || !SAFE_ID.matches(projectId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startCleanup(projectId)) notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.cleanup_notification_busy)).build(),
                )
            }
            ACTION_CANCEL -> {
                cancellation.set(true)
                runner?.cancel()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.cleanup_notification_cancelling)).build(),
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
        taskWakeLock.release()
        super.onDestroy()
    }

    private fun startCleanup(projectId: String): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        cancellation.set(false)
        taskWakeLock.acquire()
        executor.execute {
            try {
                val active = CleanupRunner(filesDir.toPath().resolve("workspace"))
                runner = active
                active.run(projectId, cancellation::get, ::publishProgress)
            } catch (_: Throwable) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.cleanup_notification_failed_unknown)).setOngoing(false).build(),
                )
            } finally {
                runner = null
                ACTIVE_PROJECT.compareAndSet(projectId, null)
                taskWakeLock.release()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return true
    }

    private fun publishProgress(progress: CleanupProgress) {
        sendBroadcast(
            CleanupStatusBroadcast.create(packageName, progress),
            "$packageName.permission.INTERNAL_CLEANUP_STATUS",
        )
        val text = when (progress.status) {
            CleanupJobStatus.QUEUED -> getString(R.string.cleanup_notification_starting)
            CleanupJobStatus.RUNNING -> getString(
                R.string.cleanup_notification_progress,
                progress.terminalPageCount,
                progress.totalPageCount,
            )
            CleanupJobStatus.SUCCEEDED -> getString(R.string.cleanup_notification_succeeded)
            CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> getString(
                R.string.cleanup_notification_succeeded_preserved,
                progress.preservedRegionCount,
            )
            CleanupJobStatus.CANCELLED -> getString(R.string.cleanup_notification_cancelled)
            CleanupJobStatus.FAILED -> getString(R.string.cleanup_notification_failed, progress.errorCode.orEmpty())
        }
        val maximum = progress.totalPageCount.coerceAtLeast(1)
        notificationManager.notify(
            NOTIFICATION_ID,
            baseNotification(text)
                .setProgress(maximum, progress.terminalPageCount.coerceIn(0, maximum), false)
                .setOngoing(progress.status == CleanupJobStatus.QUEUED || progress.status == CleanupJobStatus.RUNNING)
                .build(),
        )
    }

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(getString(R.string.cleanup_notification_title))
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
                getString(R.string.cancel_cleanup),
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE,
                    cancelIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_CLEANUP"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_CLEANUP"
        const val EXTRA_PROJECT_ID = "project_id"

        fun startIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, CleanupForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, CleanupForegroundService::class.java).setAction(ACTION_CANCEL)

        fun isTaskActive(): Boolean = ACTIVE_PROJECT.get() != null

        private const val NOTIFICATION_CHANNEL_ID = "source_cleanup"
        private const val NOTIFICATION_ID = 2_004
        private const val CONTENT_REQUEST_CODE = 3_031
        private const val CANCEL_REQUEST_CODE = 3_032
        private const val WORKER_THREAD_NAME = "masumi-cleanup"
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
