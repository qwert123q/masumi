package rs.masumi.app.detection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.core.detection.DetectionJobStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DetectionForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private val cancellation = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var activeProjectId: String? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, WORKER_THREAD_NAME)
        }
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, idleNotification())
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                if (projectId == null || !SAFE_ID.matches(projectId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startDetection(projectId, startId)) {
                    notificationManager.notify(NOTIFICATION_ID, busyNotification())
                }
            }

            ACTION_CANCEL -> {
                cancellation.set(true)
                if (activeProjectId != null) {
                    notificationManager.notify(NOTIFICATION_ID, cancellingNotification())
                } else {
                    stopSelf(startId)
                }
            }

            else -> return START_NOT_STICKY
        }
        return if (activeProjectId != null) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancellation.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startDetection(projectId: String, startId: Int): Boolean {
        synchronized(stateLock) {
            if (activeProjectId != null) return false
            activeProjectId = projectId
            cancellation.set(false)
        }

        executor.execute {
            try {
                createRunner().run(projectId, cancellation::get) { progress ->
                    publishProgress(progress)
                }
            } catch (_: Throwable) {
                notificationManager.notify(NOTIFICATION_ID, unexpectedFailureNotification())
            } finally {
                synchronized(stateLock) {
                    activeProjectId = null
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return true
    }

    private fun createRunner(): DetectionRunner {
        val workspace = filesDir.toPath().resolve("workspace")
        return DetectionRunner(
            workspaceRoot = workspace,
            modelProvider = DefaultDetectorModelProvider(
                workspaceRoot = workspace,
                signatureValidator = OnnxModelSignatureValidator(),
            ),
            detectorFactory = OnnxComicDetectorFactory(),
            decoder = PageBitmapDecoder(),
            previewRenderer = DetectionPreviewRenderer(),
        )
    }

    private fun publishProgress(progress: DetectionProgress) {
        sendBroadcast(
            DetectionStatusBroadcast.create(packageName, progress),
            internalStatusPermission(),
        )
        notificationManager.notify(NOTIFICATION_ID, progressNotification(progress))
    }

    private fun internalStatusPermission(): String =
        "$packageName.permission.INTERNAL_DETECTION_STATUS"

    private fun progressNotification(progress: DetectionProgress): Notification {
        val completed = progress.committedPageCount + progress.preservedPageCount
        val text = when (progress.status) {
            DetectionJobStatus.DOWNLOADING_MODEL -> getString(
                R.string.detection_notification_downloading,
                progress.downloadedBytes,
                progress.totalBytes,
            )

            DetectionJobStatus.SUCCEEDED -> getString(R.string.detection_notification_succeeded)
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES -> getString(
                R.string.detection_notification_succeeded_preserved,
                progress.preservedPageCount,
            )

            DetectionJobStatus.CANCELLED -> getString(R.string.detection_notification_cancelled)
            DetectionJobStatus.FAILED -> getString(
                R.string.detection_notification_failed,
                progress.errorCode.orEmpty(),
            )

            else -> getString(
                R.string.detection_notification_progress,
                completed,
                progress.totalPageCount,
            )
        }
        return baseNotification(text)
            .setProgress(
                progress.totalPageCount.coerceAtLeast(0),
                completed.coerceAtLeast(0),
                progress.totalPageCount <= 0,
            )
            .setOngoing(progress.status.isActive())
            .build()
    }

    private fun idleNotification(): Notification = baseNotification(
        getString(R.string.detection_notification_starting),
    ).setOngoing(true).build()

    private fun busyNotification(): Notification = baseNotification(
        getString(R.string.detection_notification_busy),
    ).setOngoing(true).build()

    private fun cancellingNotification(): Notification = baseNotification(
        getString(R.string.detection_notification_cancelling),
    ).setOngoing(true).build()

    private fun unexpectedFailureNotification(): Notification = baseNotification(
        getString(R.string.detection_notification_failed_unknown),
    ).setOngoing(false).build()

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(
        this,
        NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.detection_notification_title))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentPendingIntent())
        .addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.cancel_detection),
                cancelPendingIntent(),
            ).build(),
        )

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        CONTENT_REQUEST_CODE,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        CANCEL_REQUEST_CODE,
        cancelIntent(this),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.detection_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun DetectionJobStatus.isActive(): Boolean = when (this) {
        DetectionJobStatus.QUEUED,
        DetectionJobStatus.DOWNLOADING_MODEL,
        DetectionJobStatus.RUNNING,
        -> true

        DetectionJobStatus.SUCCEEDED,
        DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES,
        DetectionJobStatus.CANCELLED,
        DetectionJobStatus.FAILED,
        -> false
    }

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_DETECTION"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_DETECTION"
        const val EXTRA_PROJECT_ID = "project_id"

        fun startIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId)) { "project ID is invalid" }
            return Intent(context, DetectionForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, DetectionForegroundService::class.java).setAction(ACTION_CANCEL)

        private const val NOTIFICATION_CHANNEL_ID = "page_detection"
        private const val NOTIFICATION_ID = 2_001
        private const val CONTENT_REQUEST_CODE = 3_001
        private const val CANCEL_REQUEST_CODE = 3_002
        private const val WORKER_THREAD_NAME = "masumi-detection"
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
