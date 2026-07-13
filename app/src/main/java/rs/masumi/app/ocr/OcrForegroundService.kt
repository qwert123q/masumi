package rs.masumi.app.ocr

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
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.core.ocr.OcrJobStatus

class OcrForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private val cancellation = AtomicBoolean(false)

    @Volatile
    private var runner: OcrRunner? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
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
                if (!startOcr(projectId)) {
                    notificationManager.notify(NOTIFICATION_ID, busyNotification())
                }
            }
            ACTION_CANCEL -> {
                cancellation.set(true)
                runner?.cancel()
                if (ACTIVE_PROJECT.get() != null) {
                    notificationManager.notify(NOTIFICATION_ID, cancellingNotification())
                } else {
                    stopSelf(startId)
                }
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

    private fun startOcr(projectId: String): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        cancellation.set(false)
        executor.execute {
            try {
                val activeRunner = createRunner()
                runner = activeRunner
                activeRunner.run(projectId, cancellation::get, ::publishProgress)
            } catch (_: Throwable) {
                notificationManager.notify(NOTIFICATION_ID, unexpectedFailureNotification())
            } finally {
                runner = null
                ACTIVE_PROJECT.compareAndSet(projectId, null)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return true
    }

    private fun createRunner(): OcrRunner {
        val workspace = filesDir.toPath().resolve("workspace")
        return OcrRunner(
            workspaceRoot = workspace,
            modelProvider = DefaultOcrModelProvider(
                workspaceRoot = workspace,
                capabilityValidator = NativePaddleOcrCapabilityValidator(),
            ),
            engineFactory = OcrEngineFactory(NativePaddleOcrEngine::open),
            decoder = PageBitmapDecoder(),
            cropRenderer = OcrCropRenderer(),
            previewRenderer = OcrPreviewRenderer(),
        )
    }

    private fun publishProgress(progress: OcrProgress) {
        sendBroadcast(OcrStatusBroadcast.create(packageName, progress), internalStatusPermission())
        notificationManager.notify(NOTIFICATION_ID, progressNotification(progress))
    }

    private fun internalStatusPermission(): String = "$packageName.permission.INTERNAL_OCR_STATUS"

    private fun progressNotification(progress: OcrProgress): Notification {
        val text = when (progress.status) {
            OcrJobStatus.DOWNLOADING_MODEL -> getString(
                R.string.ocr_notification_downloading,
                progress.downloadedBytes,
                progress.totalDownloadBytes,
            )
            OcrJobStatus.LOADING_MODEL -> getString(R.string.ocr_notification_loading)
            OcrJobStatus.RUNNING -> getString(
                R.string.ocr_notification_progress,
                progress.terminalRegionCount,
                progress.totalRegionCount,
            )
            OcrJobStatus.SUCCEEDED -> getString(R.string.ocr_notification_succeeded)
            OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> getString(
                R.string.ocr_notification_succeeded_preserved,
            )
            OcrJobStatus.CANCELLED -> getString(R.string.ocr_notification_cancelled)
            OcrJobStatus.FAILED -> getString(
                R.string.ocr_notification_failed,
                progress.errorCode.orEmpty(),
            )
            OcrJobStatus.QUEUED -> getString(R.string.ocr_notification_starting)
        }
        val maximum = progress.totalRegionCount.coerceAtLeast(1)
        return baseNotification(text)
            .setProgress(maximum, progress.terminalRegionCount.coerceIn(0, maximum), progress.totalRegionCount <= 0)
            .setOngoing(progress.status.isActive())
            .build()
    }

    private fun idleNotification(): Notification = baseNotification(
        getString(R.string.ocr_notification_starting),
    ).setOngoing(true).build()

    private fun busyNotification(): Notification = baseNotification(
        getString(R.string.ocr_notification_busy),
    ).setOngoing(true).build()

    private fun cancellingNotification(): Notification = baseNotification(
        getString(R.string.ocr_notification_cancelling),
    ).setOngoing(true).build()

    private fun unexpectedFailureNotification(): Notification = baseNotification(
        getString(R.string.ocr_notification_failed_unknown),
    ).setOngoing(false).build()

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(
        this,
        NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(getString(R.string.ocr_notification_title))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentPendingIntent())
        .addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.cancel_ocr),
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
                getString(R.string.ocr_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun OcrJobStatus.isActive(): Boolean = when (this) {
        OcrJobStatus.QUEUED,
        OcrJobStatus.DOWNLOADING_MODEL,
        OcrJobStatus.LOADING_MODEL,
        OcrJobStatus.RUNNING,
        -> true
        OcrJobStatus.SUCCEEDED,
        OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        OcrJobStatus.CANCELLED,
        OcrJobStatus.FAILED,
        -> false
    }

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_OCR"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_OCR"
        const val EXTRA_PROJECT_ID = "project_id"

        fun startIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, OcrForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, OcrForegroundService::class.java).setAction(ACTION_CANCEL)

        fun isTaskActive(): Boolean = ACTIVE_PROJECT.get() != null

        private const val NOTIFICATION_CHANNEL_ID = "local_ocr"
        private const val NOTIFICATION_ID = 2_002
        private const val CONTENT_REQUEST_CODE = 3_011
        private const val CANCEL_REQUEST_CODE = 3_012
        private const val WORKER_THREAD_NAME = "masumi-ocr"
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
