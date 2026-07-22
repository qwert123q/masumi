package rs.masumi.app.translation

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
import rs.masumi.core.translation.TranslationJobStatus

class TranslationForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var taskWakeLock: ForegroundTaskWakeLock
    private val cancellation = AtomicBoolean(false)

    @Volatile
    private var runner: TranslationRunner? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
        notificationManager = getSystemService(NotificationManager::class.java)
        taskWakeLock = ForegroundTaskWakeLock(this, "translation")
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
                val settings = TranslationSettingsStore(this).loadProviderSettings()
                if (settings == null) {
                    notificationManager.notify(NOTIFICATION_ID, settingsMissingNotification())
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startTranslation(projectId, settings)) {
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
        taskWakeLock.release()
        super.onDestroy()
    }

    private fun startTranslation(projectId: String, settings: TranslationProviderSettings): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        cancellation.set(false)
        taskWakeLock.acquire()
        executor.execute {
            try {
                val activeRunner = TranslationRunner(
                    workspaceRoot = filesDir.toPath().resolve("workspace"),
                    provider = OpenAiCompatibleTranslationProvider(),
                )
                runner = activeRunner
                activeRunner.run(projectId, settings, cancellation::get, ::publishProgress)
            } catch (_: Throwable) {
                notificationManager.notify(NOTIFICATION_ID, unexpectedFailureNotification())
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

    private fun publishProgress(progress: TranslationProgress) {
        sendBroadcast(
            TranslationStatusBroadcast.create(packageName, progress),
            "$packageName.permission.INTERNAL_TRANSLATION_STATUS",
        )
        notificationManager.notify(NOTIFICATION_ID, progressNotification(progress))
    }

    private fun progressNotification(progress: TranslationProgress): Notification {
        val text = when (progress.status) {
            TranslationJobStatus.QUEUED -> getString(R.string.translation_notification_starting)
            TranslationJobStatus.RUNNING -> getString(
                R.string.translation_notification_progress,
                progress.terminalWindowCount,
                progress.totalWindowCount,
            )
            TranslationJobStatus.SUCCEEDED -> getString(R.string.translation_notification_succeeded)
            TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS -> getString(
                R.string.translation_notification_succeeded_protected,
                progress.preservedItemCount + progress.protectedOcrCount,
            )
            TranslationJobStatus.CANCELLED -> getString(R.string.translation_notification_cancelled)
            TranslationJobStatus.FAILED -> getString(
                R.string.translation_notification_failed,
                progress.errorCode.orEmpty(),
            )
        }
        val maximum = progress.totalWindowCount.coerceAtLeast(1)
        return baseNotification(text)
            .setProgress(maximum, progress.terminalWindowCount.coerceIn(0, maximum), progress.totalWindowCount <= 0)
            .setOngoing(progress.status.isActive())
            .build()
    }

    private fun baseNotification(text: String): Notification.Builder = Notification.Builder(
        this,
        NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(getString(R.string.translation_notification_title))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentPendingIntent())
        .addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.cancel_translation),
                cancelPendingIntent(),
            ).build(),
        )

    private fun idleNotification(): Notification =
        baseNotification(getString(R.string.translation_notification_starting)).setOngoing(true).build()

    private fun busyNotification(): Notification =
        baseNotification(getString(R.string.translation_notification_busy)).setOngoing(true).build()

    private fun cancellingNotification(): Notification =
        baseNotification(getString(R.string.translation_notification_cancelling)).setOngoing(true).build()

    private fun settingsMissingNotification(): Notification =
        baseNotification(getString(R.string.translation_notification_settings_missing)).setOngoing(false).build()

    private fun unexpectedFailureNotification(): Notification =
        baseNotification(getString(R.string.translation_notification_failed_unknown)).setOngoing(false).build()

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
                getString(R.string.translation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun TranslationJobStatus.isActive(): Boolean =
        this == TranslationJobStatus.QUEUED || this == TranslationJobStatus.RUNNING

    companion object {
        const val ACTION_START = "rs.masumi.app.action.START_TRANSLATION"
        const val ACTION_CANCEL = "rs.masumi.app.action.CANCEL_TRANSLATION"
        const val EXTRA_PROJECT_ID = "project_id"

        fun startIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, TranslationForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, TranslationForegroundService::class.java).setAction(ACTION_CANCEL)

        fun isTaskActive(): Boolean = ACTIVE_PROJECT.get() != null

        private const val NOTIFICATION_CHANNEL_ID = "chapter_translation"
        private const val NOTIFICATION_ID = 2_003
        private const val CONTENT_REQUEST_CODE = 3_021
        private const val CANCEL_REQUEST_CODE = 3_022
        private const val WORKER_THREAD_NAME = "masumi-translation"
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
