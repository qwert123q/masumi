package rs.masumi.app.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.app.ForegroundTaskWakeLock
import rs.masumi.app.describePipelineError
import rs.masumi.app.pipeline.PipelineDeviceCapacity
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.WorkspaceGlossaryStore

class TranslationForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var taskWakeLock: ForegroundTaskWakeLock
    private val activeTasks = ConcurrentHashMap<String, ActiveTranslation>()
    private val stateLock = Any()

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newFixedThreadPool(MAXIMUM_PARALLEL_TRANSLATIONS) { task ->
            Thread(task, "$WORKER_THREAD_NAME-${THREAD_SEQUENCE.incrementAndGet()}")
        }
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
                cancelTranslations(intent.getStringExtra(EXTRA_PROJECT_ID))
                if (activeTasks.isNotEmpty()) {
                    notificationManager.notify(NOTIFICATION_ID, cancellingNotification())
                } else {
                    stopSelf(startId)
                }
            }
            else -> return START_NOT_STICKY
        }
        // Never redeliver: removing the app from recents must leave pipeline
        // work stopped; durable checkpoints preserve the progress for resume.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        cancelTranslations(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelTranslations(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        cancelTranslations(null)
        executor.shutdownNow()
        taskWakeLock.release()
        super.onDestroy()
    }

    private fun startTranslation(projectId: String, settings: TranslationProviderSettings): Boolean {
        val active = synchronized(stateLock) {
            if (
                activeTasks.containsKey(projectId) ||
                activeTasks.size >= PipelineDeviceCapacity.translationSlots(this)
            ) {
                return false
            }
            ActiveTranslation().also {
                activeTasks[projectId] = it
                ACTIVE_PROJECTS += projectId
                taskWakeLock.acquire()
            }
        }
        executor.execute {
            try {
                val activeRunner = TranslationRunner(
                    workspaceRoot = filesDir.toPath().resolve("workspace"),
                    provider = OpenAiCompatibleTranslationProvider(),
                    glossaryMemory = WorkspaceGlossaryStore(filesDir.toPath().resolve("workspace")),
                )
                active.runner = activeRunner
                activeRunner.run(projectId, settings, active.cancellation::get, ::publishProgress)
            } catch (_: Throwable) {
                notificationManager.notify(NOTIFICATION_ID, unexpectedFailureNotification())
            } finally {
                active.runner = null
                synchronized(stateLock) {
                    activeTasks.remove(projectId, active)
                    ACTIVE_PROJECTS -= projectId
                    if (activeTasks.isEmpty()) {
                        taskWakeLock.release()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        }
        return true
    }

    private fun cancelTranslations(projectId: String?) {
        val targets = if (projectId == null) {
            activeTasks.values.toList()
        } else {
            listOfNotNull(activeTasks[projectId])
        }
        targets.forEach { active ->
            active.cancellation.set(true)
            active.runner?.cancel()
        }
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
                describePipelineError(progress.errorCode),
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

        fun cancelIntent(context: Context, projectId: String? = null): Intent =
            Intent(context, TranslationForegroundService::class.java)
                .setAction(ACTION_CANCEL)
                .apply {
                    if (projectId != null) {
                        require(SAFE_ID.matches(projectId))
                        putExtra(EXTRA_PROJECT_ID, projectId)
                    }
                }

        fun isTaskActive(projectId: String? = null): Boolean =
            if (projectId == null) ACTIVE_PROJECTS.isNotEmpty() else projectId in ACTIVE_PROJECTS

        private const val NOTIFICATION_CHANNEL_ID = "chapter_translation"
        private const val NOTIFICATION_ID = 2_003
        private const val CONTENT_REQUEST_CODE = 3_021
        private const val CANCEL_REQUEST_CODE = 3_022
        private const val WORKER_THREAD_NAME = "masumi-translation"
        private const val MAXIMUM_PARALLEL_TRANSLATIONS = 2
        private val ACTIVE_PROJECTS = ConcurrentHashMap.newKeySet<String>()
        private val THREAD_SEQUENCE = java.util.concurrent.atomic.AtomicInteger()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }

    private class ActiveTranslation {
        val cancellation = AtomicBoolean(false)

        @Volatile
        var runner: TranslationRunner? = null
    }
}
