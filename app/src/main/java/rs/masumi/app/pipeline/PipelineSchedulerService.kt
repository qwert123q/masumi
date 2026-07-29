package rs.masumi.app.pipeline

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import rs.masumi.app.R
import rs.masumi.app.cleanup.CleanupForegroundService
import rs.masumi.app.cleanup.CleanupStatusBroadcast
import rs.masumi.app.detection.DetectionForegroundService
import rs.masumi.app.detection.DetectionStatusBroadcast
import rs.masumi.app.exporting.ExportForegroundService
import rs.masumi.app.exporting.ExportStatusBroadcast
import rs.masumi.app.library.LibraryActivity
import rs.masumi.app.library.MangaLibraryPreferences
import rs.masumi.app.library.MangaLibraryStore
import rs.masumi.app.library.invalidateMangaLibraryCache
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.ocr.OcrStatusBroadcast
import rs.masumi.app.quality.QualityForegroundService
import rs.masumi.app.quality.QualityStatusBroadcast
import rs.masumi.app.translation.TranslationForegroundService
import rs.masumi.app.translation.TranslationSettingsStore
import rs.masumi.app.translation.TranslationStatusBroadcast
import rs.masumi.app.typesetting.TypesettingForegroundService
import rs.masumi.app.typesetting.TypesettingStatusBroadcast
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.quality.QualityJobStatus
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.typesetting.TypesettingJobStatus

class PipelineSchedulerService : Service() {
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var queueStore: PipelineQueueStore
    private lateinit var stateReader: ProjectPipelineStateReader
    private val running = ConcurrentHashMap<String, TrackedTask>()
    private val stateCache = ConcurrentHashMap<String, ProjectPipelineState>()

    private val stageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                DetectionStatusBroadcast.ACTION -> DetectionStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.DETECTION,
                        progress.projectId,
                        progress.status in DETECTION_ACTIVE,
                        progress.status in DETECTION_SUCCESS,
                        progress.status == DetectionJobStatus.FAILED || progress.status == DetectionJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
                OcrStatusBroadcast.ACTION -> OcrStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.OCR,
                        progress.projectId,
                        progress.status in OCR_ACTIVE,
                        progress.status in OCR_SUCCESS,
                        progress.status == OcrJobStatus.FAILED || progress.status == OcrJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
                TranslationStatusBroadcast.ACTION -> TranslationStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.TRANSLATION,
                        progress.projectId,
                        progress.status in TRANSLATION_ACTIVE,
                        progress.status in TRANSLATION_SUCCESS,
                        progress.status == TranslationJobStatus.FAILED ||
                            progress.status == TranslationJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
                CleanupStatusBroadcast.ACTION -> CleanupStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.CLEANUP,
                        progress.projectId,
                        progress.status in CLEANUP_ACTIVE,
                        progress.status in CLEANUP_SUCCESS,
                        progress.status == CleanupJobStatus.FAILED || progress.status == CleanupJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
                TypesettingStatusBroadcast.ACTION -> TypesettingStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.TYPESETTING,
                        progress.projectId,
                        progress.status in TYPESETTING_ACTIVE,
                        progress.status in TYPESETTING_SUCCESS,
                        progress.status == TypesettingJobStatus.FAILED ||
                            progress.status == TypesettingJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
                QualityStatusBroadcast.ACTION -> QualityStatusBroadcast.parse(intent)?.let { progress ->
                    handleProgress(
                        PipelineStage.QUALITY,
                        progress.projectId,
                        progress.status in QUALITY_ACTIVE,
                        progress.status in QUALITY_SUCCESS,
                        progress.status == QualityJobStatus.FAILED ||
                            progress.status == QualityJobStatus.CANCELLED ||
                            progress.status == QualityJobStatus.BLOCKED,
                        progress.errorCode ?: if (progress.status == QualityJobStatus.BLOCKED) "QUALITY_BLOCKED" else null,
                    )
                }
                ExportStatusBroadcast.ACTION -> ExportStatusBroadcast.parse(intent)?.let { progress ->
                    if (progress.status !in EXPORT_ACTIVE) {
                        // The export writer creates output pages through
                        // DocumentsContract directly, behind MangaLibraryStore's
                        // back, so the cached snapshot's page counts go stale the
                        // moment a run finishes (even a failed one may have
                        // committed pages).
                        MangaLibraryPreferences(this@PipelineSchedulerService)
                            .rootUri()
                            ?.let(::invalidateMangaLibraryCache)
                        // The chapter's pipeline just went quiet, which is the
                        // one moment superseded artifact runs are provably dead.
                        scheduler.execute {
                            runCatching {
                                WorkspaceJanitor.sweepProject(
                                    filesDir.toPath().resolve("workspace"),
                                    progress.projectId,
                                )
                            }
                        }
                    }
                    handleProgress(
                        PipelineStage.EXPORT,
                        progress.projectId,
                        progress.status in EXPORT_ACTIVE,
                        progress.status == ExportJobStatus.SUCCEEDED,
                        progress.status == ExportJobStatus.FAILED || progress.status == ExportJobStatus.CANCELLED,
                        progress.errorCode,
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scheduler = Executors.newSingleThreadScheduledExecutor(
            PipelineThreading.factory(WORKER_THREAD_NAME),
        )
        notificationManager = getSystemService(NotificationManager::class.java)
        queueStore = PipelineQueueStore(this)
        stateReader = ProjectPipelineStateReader(filesDir.toPath().resolve("workspace"))
        createNotificationChannel()
        registerStageReceiver()
        scheduler.scheduleWithFixedDelay(::safeTick, 0L, RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            ACTION_ENQUEUE -> intent.getStringExtra(EXTRA_PROJECT_ID)
                ?.takeIf(SAFE_ID::matches)
                ?.let(queueStore::enqueue)
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_PROJECT_ID)
                ?.takeIf(SAFE_ID::matches)
                ?.let { queueStore.pause(it, "USER_PAUSED") }
            ACTION_WAKE, null -> Unit
            else -> return START_NOT_STICKY
        }
        scheduler.execute(::safeTick)
        // The queue is explicitly user-controlled. A process restart or an
        // empty redelivery must continue every ACTIVE entry; only ACTION_PAUSE
        // is allowed to turn one into a paused entry.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stageReceiver) }
        scheduler.shutdownNow()
        super.onDestroy()
    }

    private fun safeTick() {
        runCatching { tick() }
            .onFailure {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    notification(getString(R.string.pipeline_scheduler_waiting)),
                )
            }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val states = queueStore.entries().mapNotNull { entry ->
            val state = stateCache[entry.projectId]
                ?: stateReader.read(entry.projectId)?.also { stateCache[entry.projectId] = it }
                ?: return@mapNotNull null
            if (state.complete) {
                queueStore.remove(entry.projectId)
                running.remove(entry.projectId)
                stateCache.remove(entry.projectId)
                return@mapNotNull null
            }
            if (state.blocked && entry.status == PipelineQueueStatus.ACTIVE) {
                queueStore.pause(entry.projectId, state.errorCode ?: "PIPELINE_BLOCKED")
            }
            entry to state
        }

        val newlyDelayed = reconcileRunning(
            states.associate { it.first.projectId to it.second },
            now,
        )
        val hasSettings = TranslationSettingsStore(this).loadProviderSettings() != null
        val projects = states.map { (entry, state) ->
            ScheduledProject(
                projectId = entry.projectId,
                queueOrder = entry.enqueuedAtEpochMillis,
                pageCount = state.pageCount,
                nextStage = state.nextStage,
                waitingForSettings = state.nextStage == PipelineStage.TRANSLATION && !hasSettings,
                blocked = entry.status == PipelineQueueStatus.PAUSED ||
                    entry.retryNotBeforeEpochMillis > now ||
                    state.blocked ||
                    entry.projectId in newlyDelayed,
            )
        }
        val launches = PipelineSchedulePlanner.plan(
            projects = projects,
            running = running.map { (projectId, task) ->
                RunningPipelineTask(projectId, task.stage)
            }.toSet(),
            translationCapacity = PipelineDeviceCapacity.translationSlots(this),
        )
        launches.forEach { launch ->
            val tracked = TrackedTask(launch.stage, now)
            if (running.putIfAbsent(launch.projectId, tracked) == null) {
                if (!launch(launch)) running.remove(launch.projectId, tracked)
            }
        }

        val activeEntries = queueStore.entries().count { it.status == PipelineQueueStatus.ACTIVE }
        notificationManager.notify(
            NOTIFICATION_ID,
            notification(
                getString(
                    R.string.pipeline_scheduler_progress,
                    activeEntries,
                    running.count { PipelineSchedulePlanner.lane(it.value.stage) == PipelineLane.LOCAL },
                    running.count { PipelineSchedulePlanner.lane(it.value.stage) == PipelineLane.NETWORK },
                ),
            ),
        )
        if (activeEntries == 0 && running.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun reconcileRunning(
        states: Map<String, ProjectPipelineState>,
        now: Long,
    ): Set<String> {
        val newlyDelayed = mutableSetOf<String>()
        val ocrProcessAlive = isOcrProcessAlive()
        running.entries.removeIf { (projectId, tracked) ->
            val state = states[projectId]
            val stageChanged = state == null ||
                state.complete ||
                state.blocked ||
                state.nextStage != tracked.stage
            if (stageChanged) {
                return@removeIf true
            }
            val processDied = tracked.stage == PipelineStage.OCR &&
                tracked.confirmed &&
                now - tracked.lastProgressAtEpochMillis > PROCESS_DEATH_GRACE_MILLIS &&
                !ocrProcessAlive
            if (processDied) {
                queueStore.scheduleRetry(projectId, "OCR_PROCESS_DIED", now)
                newlyDelayed += projectId
            }
            processDied ||
                (!tracked.confirmed && now - tracked.lastProgressAtEpochMillis > LAUNCH_CONFIRM_TIMEOUT_MILLIS) ||
                (tracked.confirmed && now - tracked.lastProgressAtEpochMillis > STALL_RECOVERY_TIMEOUT_MILLIS)
        }
        return newlyDelayed
    }

    private fun isOcrProcessAlive(): Boolean =
        getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.any { it.processName == "$packageName:ocr" }
            ?: true

    private fun launch(launch: PipelineLaunch): Boolean = runCatching {
        when (launch.stage) {
            PipelineStage.DETECTION ->
                startForegroundService(DetectionForegroundService.startIntent(this, launch.projectId))
            PipelineStage.OCR ->
                startForegroundService(OcrForegroundService.startIntent(this, launch.projectId))
            PipelineStage.TRANSLATION ->
                startForegroundService(TranslationForegroundService.startIntent(this, launch.projectId))
            PipelineStage.CLEANUP ->
                startForegroundService(CleanupForegroundService.startIntent(this, launch.projectId))
            PipelineStage.TYPESETTING ->
                startForegroundService(TypesettingForegroundService.startIntent(this, launch.projectId))
            PipelineStage.QUALITY ->
                startForegroundService(QualityForegroundService.startIntent(this, launch.projectId))
            PipelineStage.EXPORT -> {
                val root = MangaLibraryPreferences(this).rootUri() ?: return false
                val destination = MangaLibraryStore(contentResolver, root).ensureOutputDirectory(launch.projectId)
                startForegroundService(ExportForegroundService.startIntent(this, launch.projectId, destination))
            }
        }
        true
    }.getOrElse {
        queueStore.scheduleRetry(launch.projectId, "STAGE_START_FAILED")
        false
    }

    private fun handleProgress(
        stage: PipelineStage,
        projectId: String,
        active: Boolean,
        success: Boolean,
        failure: Boolean,
        errorCode: String?,
    ) {
        if (!queueStore.contains(projectId)) return
        if (active) {
            running.compute(projectId) { _, previous ->
                (previous?.takeIf { it.stage == stage } ?: TrackedTask(stage, System.currentTimeMillis())).apply {
                    confirmed = true
                    lastProgressAtEpochMillis = System.currentTimeMillis()
                }
            }
        } else {
            running.remove(projectId)
            stateCache.remove(projectId)
            when {
                // A stage cancellation is the expected acknowledgement of a
                // user pause. Do not overwrite USER_PAUSED with STAGE_FAILED.
                failure &&
                    queueStore.isActive(projectId) &&
                    PipelineRetryPolicy.isRetryable(stage, errorCode) ->
                    queueStore.scheduleRetry(projectId, errorCode ?: "STAGE_FAILED")
                failure && queueStore.isActive(projectId) ->
                    queueStore.pause(projectId, errorCode ?: "STAGE_FAILED")
                success && stage == PipelineStage.EXPORT -> queueStore.remove(projectId)
                success -> queueStore.clearRetry(projectId)
            }
        }
        scheduler.execute(::safeTick)
    }

    private fun registerStageReceiver() {
        val filter = IntentFilter().apply {
            addAction(DetectionStatusBroadcast.ACTION)
            addAction(OcrStatusBroadcast.ACTION)
            addAction(TranslationStatusBroadcast.ACTION)
            addAction(CleanupStatusBroadcast.ACTION)
            addAction(TypesettingStatusBroadcast.ACTION)
            addAction(QualityStatusBroadcast.ACTION)
            addAction(ExportStatusBroadcast.ACTION)
        }
        registerReceiver(stageReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun notification(text: String = getString(R.string.pipeline_scheduler_starting)): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.pipeline_scheduler_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    CONTENT_REQUEST_CODE,
                    Intent(this, LibraryActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.pipeline_scheduler_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private data class TrackedTask(
        val stage: PipelineStage,
        var lastProgressAtEpochMillis: Long,
        var confirmed: Boolean = false,
    )

    companion object {
        const val ACTION_ENQUEUE = "rs.masumi.app.action.ENQUEUE_PIPELINE"
        const val ACTION_PAUSE = "rs.masumi.app.action.PAUSE_PIPELINE"
        const val ACTION_WAKE = "rs.masumi.app.action.WAKE_PIPELINE"
        const val EXTRA_PROJECT_ID = "project_id"

        fun enqueueIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, PipelineSchedulerService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun pauseIntent(context: Context, projectId: String): Intent {
            require(SAFE_ID.matches(projectId))
            return Intent(context, PipelineSchedulerService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_PROJECT_ID, projectId)
        }

        fun wakeIntent(context: Context): Intent =
            Intent(context, PipelineSchedulerService::class.java).setAction(ACTION_WAKE)

        private const val NOTIFICATION_CHANNEL_ID = "pipeline_scheduler"
        private const val NOTIFICATION_ID = 2_000
        private const val CONTENT_REQUEST_CODE = 3_000
        private const val WORKER_THREAD_NAME = "masumi-pipeline-scheduler"
        private const val RECONCILE_INTERVAL_SECONDS = 2L
        private const val LAUNCH_CONFIRM_TIMEOUT_MILLIS = 15_000L
        private const val PROCESS_DEATH_GRACE_MILLIS = 3_000L
        private const val STALL_RECOVERY_TIMEOUT_MILLIS = 15L * 60L * 1_000L
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val DETECTION_ACTIVE = setOf(
            DetectionJobStatus.QUEUED,
            DetectionJobStatus.DOWNLOADING_MODEL,
            DetectionJobStatus.RUNNING,
        )
        private val DETECTION_SUCCESS = setOf(
            DetectionJobStatus.SUCCEEDED,
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES,
        )
        private val OCR_ACTIVE = setOf(
            OcrJobStatus.QUEUED,
            OcrJobStatus.DOWNLOADING_MODEL,
            OcrJobStatus.LOADING_MODEL,
            OcrJobStatus.RUNNING,
        )
        private val OCR_SUCCESS = setOf(
            OcrJobStatus.SUCCEEDED,
            OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        )
        private val TRANSLATION_ACTIVE = setOf(TranslationJobStatus.QUEUED, TranslationJobStatus.RUNNING)
        private val TRANSLATION_SUCCESS = setOf(
            TranslationJobStatus.SUCCEEDED,
            TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS,
        )
        private val CLEANUP_ACTIVE = setOf(CleanupJobStatus.QUEUED, CleanupJobStatus.RUNNING)
        private val CLEANUP_SUCCESS = setOf(
            CleanupJobStatus.SUCCEEDED,
            CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        )
        private val TYPESETTING_ACTIVE = setOf(TypesettingJobStatus.QUEUED, TypesettingJobStatus.RUNNING)
        private val TYPESETTING_SUCCESS = setOf(
            TypesettingJobStatus.SUCCEEDED,
            TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        )
        private val QUALITY_ACTIVE = setOf(QualityJobStatus.QUEUED, QualityJobStatus.RUNNING)
        private val QUALITY_SUCCESS = setOf(
            QualityJobStatus.SUCCEEDED,
            QualityJobStatus.SUCCEEDED_WITH_WARNINGS,
        )
        private val EXPORT_ACTIVE = setOf(ExportJobStatus.QUEUED, ExportJobStatus.RUNNING)
    }
}
