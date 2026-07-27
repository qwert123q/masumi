package rs.masumi.app.exporting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.app.ForegroundTaskWakeLock
import rs.masumi.app.describePipelineError
import rs.masumi.app.library.MangaLibraryPreferences
import rs.masumi.app.library.MangaLibraryStore
import rs.masumi.app.pipeline.PipelineResourceLease
import rs.masumi.core.exporting.ExportJobStatus

class ExportForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var taskWakeLock: ForegroundTaskWakeLock
    private val cancellation = AtomicBoolean(false)

    @Volatile private var runner: ExportRunner? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
        notificationManager = getSystemService(NotificationManager::class.java)
        taskWakeLock = ForegroundTaskWakeLock(this, "export")
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.export_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.export_notification_starting)).build(),
                )
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                val destinationUri = intent.getStringExtra(EXTRA_DESTINATION_URI)
                if (projectId == null || !SAFE_ID.matches(projectId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startExport(projectId, destinationUri)) notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.export_notification_busy)).build(),
                )
            }
            ACTION_CANCEL -> {
                cancellation.set(true)
                runner?.cancel()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.export_notification_cancelling)).build(),
                )
            }
            else -> return START_NOT_STICKY
        }
        // Never redeliver: removing the app from recents must leave pipeline
        // work stopped; durable checkpoints preserve the progress for resume.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        cancellation.set(true)
        runner?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellation.set(true)
        runner?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        cancellation.set(true)
        runner?.cancel()
        executor.shutdownNow()
        taskWakeLock.release()
        super.onDestroy()
    }

    private fun startExport(projectId: String, destinationUri: String?): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        cancellation.set(false)
        taskWakeLock.acquire()
        executor.execute {
            try {
                val workspace = filesDir.toPath().resolve("workspace")
                PipelineResourceLease.acquire(workspace, cancellation::get)?.use {
                    val active = ExportRunner(
                        workspaceRoot = workspace,
                        destinationFactory = { uri, jobId ->
                            SafFolderExportDestination(contentResolver, uri, jobId)
                        },
                    )
                    runner = active
                    active.run(projectId, destinationUri, cancellation::get, ::publishProgress)
                }
            } catch (_: Throwable) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    baseNotification(getString(R.string.export_notification_failed_unknown))
                        .setOngoing(false)
                        .build(),
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

    private fun publishProgress(progress: ExportProgress) {
        if (progress.status == ExportJobStatus.SUCCEEDED) {
            runCatching {
                val root = MangaLibraryPreferences(this).rootUri() ?: return@runCatching
                MangaLibraryStore(contentResolver, root).markProjectCompleted(
                    projectId = progress.projectId,
                    completedAtEpochMillis = System.currentTimeMillis(),
                )
            }
        }
        sendBroadcast(
            ExportStatusBroadcast.create(packageName, progress),
            "$packageName.permission.INTERNAL_EXPORT_STATUS",
        )
        val text = when (progress.status) {
            ExportJobStatus.QUEUED -> getString(R.string.export_notification_starting)
            ExportJobStatus.RUNNING -> getString(
                R.string.export_notification_progress,
                progress.terminalPageCount,
                progress.totalPageCount,
            )
            ExportJobStatus.SUCCEEDED -> getString(R.string.export_notification_succeeded)
            ExportJobStatus.CANCELLED -> getString(R.string.export_notification_cancelled)
            ExportJobStatus.FAILED -> getString(
                R.string.export_notification_failed,
                describePipelineError(progress.errorCode),
            )
        }
        val maximum = progress.totalPageCount.coerceAtLeast(1)
        notificationManager.notify(
            NOTIFICATION_ID,
            baseNotification(text)
                .setProgress(maximum, progress.terminalPageCount.coerceIn(0, maximum), false)
                .setOngoing(progress.status == ExportJobStatus.QUEUED || progress.status == ExportJobStatus.RUNNING)
                .build(),
        )
    }

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(getString(R.string.export_notification_title))
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
                getString(R.string.cancel_export),
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE,
                    cancelIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_EXPORT"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_EXPORT"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_DESTINATION_URI = "destination_uri"

        fun startIntent(context: Context, projectId: String, destinationUri: Uri): Intent {
            require(SAFE_ID.matches(projectId) && destinationUri.scheme == CONTENT_RESOLVER_SCHEME)
            return Intent(context, ExportForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_DESTINATION_URI, destinationUri.toString())
        }

        fun resumeIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, ExportForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, ExportForegroundService::class.java).setAction(ACTION_CANCEL)

        fun isTaskActive(): Boolean = ACTIVE_PROJECT.get() != null

        private const val CONTENT_RESOLVER_SCHEME = "content"
        private const val NOTIFICATION_CHANNEL_ID = "folder_export"
        private const val NOTIFICATION_ID = 2_006
        private const val CONTENT_REQUEST_CODE = 3_051
        private const val CANCEL_REQUEST_CODE = 3_052
        private const val WORKER_THREAD_NAME = "masumi-export"
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
