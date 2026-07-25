package rs.masumi.app.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.app.ForegroundTaskWakeLock
import rs.masumi.app.describePipelineError
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.app.library.MangaLibraryModelCache
import rs.masumi.app.library.MangaLibraryPreferences
import rs.masumi.app.pipeline.PipelineResourceLease
import rs.masumi.core.ocr.OcrJobStatus

class OcrForegroundService : Service() {
    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var cancellationWatchdog: ScheduledExecutorService
    private lateinit var taskWakeLock: ForegroundTaskWakeLock
    private val cancellation = AtomicBoolean(false)
    private val forcedCancellationScheduled = AtomicBoolean(false)
    private val idleGeneration = AtomicLong(0L)
    private val latestStartId = AtomicInteger(0)
    private val progressThrottle = OcrProgressThrottle()
    private lateinit var engineCache: OcrEngineSessionCache
    private var secondaryEngineCache: OcrEngineSessionCache? = null

    @Volatile
    private var runner: OcrRunner? = null

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
        cancellationWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, CANCELLATION_WATCHDOG_THREAD_NAME)
        }
        taskWakeLock = ForegroundTaskWakeLock(this, "ocr")
        notificationManager = getSystemService(NotificationManager::class.java)
        val workspace = filesDir.toPath().resolve("workspace")
        val backendHealth = OcrBackendHealthStore(
            workspace.resolve("runtime/ocr-vulkan-unavailable"),
        )
        // On big-core devices with headroom, split the CPU threads between two
        // engines so two regions of a page are recognized concurrently. The
        // vision encoder is the dominant cost and runs on CPU either way.
        val dualEngine = supportsDualEngines()
        val primaryThreads = if (dualEngine) DUAL_ENGINE_THREADS else SINGLE_ENGINE_THREADS
        engineCache = OcrEngineSessionCache(
            OcrEngineFactory { model, projector ->
                DevicePaddleOcrEngine.open(model, projector, backendHealth, primaryThreads)
            },
        )
        secondaryEngineCache = if (dualEngine) {
            OcrEngineSessionCache(
                OcrEngineFactory { model, projector ->
                    NativePaddleOcrEngine.openCpuOnly(model, projector, DUAL_ENGINE_THREADS)
                },
            )
        } else {
            null
        }
        createNotificationChannel()
    }

    private fun supportsDualEngines(): Boolean {
        if (Runtime.getRuntime().availableProcessors() < MINIMUM_DUAL_ENGINE_PROCESSORS) return false
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem >= MINIMUM_DUAL_ENGINE_MEMORY_BYTES
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
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
                if (ACTIVE_PROJECT.get() != null) scheduleForcedCancellation()
                runner?.cancel()?.let(::publishProgress)
                if (ACTIVE_PROJECT.get() != null) {
                    notificationManager.notify(NOTIFICATION_ID, cancellingNotification())
                } else {
                    stopSelf(startId)
                }
            }
            else -> return START_NOT_STICKY
        }
        // Never redeliver: if the user removes the app from recents (or the
        // system kills the process), pipeline work must stay stopped until it
        // is explicitly resumed; durable checkpoints preserve the progress.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        cancellation.set(true)
        runner?.cancel()
        idleGeneration.incrementAndGet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellation.set(true)
        runner?.cancel()
        idleGeneration.incrementAndGet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        cancellation.set(true)
        runner?.cancel()
        idleGeneration.incrementAndGet()
        executor.shutdownNow()
        cancellationWatchdog.shutdownNow()
        engineCache.close()
        secondaryEngineCache?.close()
        taskWakeLock.release()
        super.onDestroy()
    }

    private fun startOcr(projectId: String): Boolean {
        if (!ACTIVE_PROJECT.compareAndSet(null, projectId)) return false
        idleGeneration.incrementAndGet()
        cancellation.set(false)
        forcedCancellationScheduled.set(false)
        taskWakeLock.acquire()
        executor.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val workspace = filesDir.toPath().resolve("workspace")
                PipelineResourceLease.acquire(workspace, cancellation::get)?.use {
                    val activeRunner = createRunner()
                    runner = activeRunner
                    activeRunner.run(projectId, cancellation::get, ::publishProgress)
                }
            } catch (_: Throwable) {
                notificationManager.notify(NOTIFICATION_ID, unexpectedFailureNotification())
            } finally {
                runner = null
                ACTIVE_PROJECT.compareAndSet(projectId, null)
                taskWakeLock.release()
                notificationManager.notify(NOTIFICATION_ID, modelReadyNotification())
                scheduleIdleShutdown()
            }
        }
        return true
    }

    private fun scheduleForcedCancellation() {
        if (!forcedCancellationScheduled.compareAndSet(false, true)) return
        cancellationWatchdog.schedule(
            {
                if (ACTIVE_PROJECT.get() != null) {
                    runner?.cancel()?.let(::publishProgress)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    Process.killProcess(Process.myPid())
                }
            },
            FORCED_CANCELLATION_GRACE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleIdleShutdown() {
        val generation = idleGeneration.incrementAndGet()
        val startId = latestStartId.get()
        cancellationWatchdog.schedule(
            {
                if (generation != idleGeneration.get() || ACTIVE_PROJECT.get() != null) return@schedule
                if (stopSelfResult(startId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            },
            ENGINE_IDLE_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun createRunner(): OcrRunner {
        val workspace = filesDir.toPath().resolve("workspace")
        return OcrRunner(
            workspaceRoot = workspace,
            modelProvider = DefaultOcrModelProvider(
                workspaceRoot = workspace,
                capabilityValidator = NativePaddleOcrCapabilityValidator(),
                persistentCache = MangaLibraryPreferences(this).rootUri()?.let { root ->
                    MangaLibraryModelCache(contentResolver, root)
                },
            ),
            engineFactory = OcrEngineFactory { model, projector ->
                engineCache.open(model, projector)
            },
            decoder = PageBitmapDecoder(),
            cropRenderer = OcrCropRenderer(),
            previewRenderer = OcrPreviewRenderer(),
            secondaryEngineFactory = secondaryEngineCache?.let { cache ->
                OcrEngineFactory { model, projector -> cache.open(model, projector) }
            },
        )
    }

    private fun publishProgress(progress: OcrProgress) {
        if (!progressThrottle.shouldPublish(progress)) return
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
                describePipelineError(progress.errorCode),
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

    private fun modelReadyNotification(): Notification = baseNotification(
        getString(R.string.ocr_notification_model_ready),
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
        private const val CANCELLATION_WATCHDOG_THREAD_NAME = "masumi-ocr-cancel-watchdog"
        private const val FORCED_CANCELLATION_GRACE_MILLIS = 1_500L
        private const val ENGINE_IDLE_TIMEOUT_MILLIS = 60_000L
        private const val SINGLE_ENGINE_THREADS = 6
        private const val DUAL_ENGINE_THREADS = 4
        private const val MINIMUM_DUAL_ENGINE_PROCESSORS = 8
        private const val MINIMUM_DUAL_ENGINE_MEMORY_BYTES = 7L * 1_024L * 1_024L * 1_024L
        private val ACTIVE_PROJECT = AtomicReference<String?>()
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
