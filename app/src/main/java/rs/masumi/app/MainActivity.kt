package rs.masumi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import rs.masumi.app.detection.DetectionForegroundService
import rs.masumi.app.detection.DetectionProgress
import rs.masumi.app.detection.DetectionResumePolicy
import rs.masumi.app.detection.DetectionStatusBroadcast
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.ocr.OcrProgress
import rs.masumi.app.ocr.OcrResumePolicy
import rs.masumi.app.ocr.OcrStatusBroadcast
import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.importer.ProjectImportException
import rs.masumi.core.importer.ProjectImporter
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrRunEntry
import java.nio.file.Files
import java.nio.file.Path

class MainActivity : Activity() {
    private lateinit var importButton: Button
    private lateinit var importProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var analysisButton: Button
    private lateinit var cancelAnalysisButton: Button
    private lateinit var detectionProgress: ProgressBar
    private lateinit var detectionStatus: TextView
    private lateinit var previewImage: ImageView
    private lateinit var preservedPageMarker: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var previousPageButton: Button
    private lateinit var nextPageButton: Button
    private lateinit var ocrButton: Button
    private lateinit var cancelOcrButton: Button
    private lateinit var ocrProgress: ProgressBar
    private lateinit var ocrStatus: TextView
    private lateinit var ocrPreviewImage: ImageView
    private lateinit var ocrPreservedPageMarker: TextView
    private lateinit var ocrPageIndicator: TextView
    private lateinit var previousOcrPageButton: Button
    private lateinit var nextOcrPageButton: Button
    private lateinit var ocrDetailText: TextView
    private lateinit var catalog: ProjectCatalog

    private var importRunning = false
    private var analysisActive = false
    private var ocrActive = false
    private var receiverRegistered = false
    private var ocrReceiverRegistered = false
    private var currentProject: ProjectRef? = null
    private var currentRun: PublishedDetectionRun? = null
    private var currentPreviewIndex = 0
    private var currentOcrRun: PublishedOcrRun? = null
    private var currentOcrPreviewIndex = 0
    private var displayedBitmap: Bitmap? = null
    private var displayedOcrBitmap: Bitmap? = null
    private var pendingAnalysisProjectId: String? = null
    private var pendingOcrProjectId: String? = null
    private var resumeRequestedThisProcess = false
    private var ocrResumeRequestedThisProcess = false

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(DetectionStatusBroadcast::parse) ?: return
            refreshDurableState(progress)
        }
    }

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(OcrStatusBroadcast::parse) ?: return
            refreshOcrDurableState(progress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        importButton = findViewById(R.id.importButton)
        importProgress = findViewById(R.id.importProgress)
        statusText = findViewById(R.id.statusText)
        analysisButton = findViewById(R.id.analysisButton)
        cancelAnalysisButton = findViewById(R.id.cancelAnalysisButton)
        detectionProgress = findViewById(R.id.detectionProgress)
        detectionStatus = findViewById(R.id.detectionStatus)
        previewImage = findViewById(R.id.previewImage)
        preservedPageMarker = findViewById(R.id.preservedPageMarker)
        pageIndicator = findViewById(R.id.pageIndicator)
        previousPageButton = findViewById(R.id.previousPageButton)
        nextPageButton = findViewById(R.id.nextPageButton)
        ocrButton = findViewById(R.id.ocrButton)
        cancelOcrButton = findViewById(R.id.cancelOcrButton)
        ocrProgress = findViewById(R.id.ocrProgress)
        ocrStatus = findViewById(R.id.ocrStatus)
        ocrPreviewImage = findViewById(R.id.ocrPreviewImage)
        ocrPreservedPageMarker = findViewById(R.id.ocrPreservedPageMarker)
        ocrPageIndicator = findViewById(R.id.ocrPageIndicator)
        previousOcrPageButton = findViewById(R.id.previousOcrPageButton)
        nextOcrPageButton = findViewById(R.id.nextOcrPageButton)
        ocrDetailText = findViewById(R.id.ocrDetailText)
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))

        importButton.setOnClickListener { openChapterFolder() }
        analysisButton.setOnClickListener { requestAnalysisStart() }
        cancelAnalysisButton.setOnClickListener { cancelAnalysis() }
        previousPageButton.setOnClickListener { showPreview(currentPreviewIndex - 1) }
        nextPageButton.setOnClickListener { showPreview(currentPreviewIndex + 1) }
        ocrButton.setOnClickListener { requestOcrStart() }
        cancelOcrButton.setOnClickListener { cancelOcr() }
        previousOcrPageButton.setOnClickListener { showOcrPreview(currentOcrPreviewIndex - 1) }
        nextOcrPageButton.setOnClickListener { showOcrPreview(currentOcrPreviewIndex + 1) }
    }

    override fun onStart() {
        super.onStart()
        registerDetectionReceiver()
        registerOcrReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshDurableState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(detectionReceiver)
            receiverRegistered = false
        }
        if (ocrReceiverRegistered) {
            unregisterReceiver(ocrReceiver)
            ocrReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        clearDisplayedBitmap()
        clearDisplayedOcrBitmap()
        super.onDestroy()
    }

    @Deprecated("Uses the platform result API to keep the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_CHAPTER || resultCode != RESULT_OK) return

        val treeUri = data?.data ?: return
        retainReadPermission(treeUri, data.flags)
        importChapter(treeUri)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATION_PERMISSION) return
        pendingAnalysisProjectId?.let(::startAnalysis)
        pendingAnalysisProjectId = null
        pendingOcrProjectId?.let(::startOcr)
        pendingOcrProjectId = null
    }

    private fun openChapterFolder() {
        if (importRunning || analysisActive || ocrActive) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_CHAPTER)
    }

    private fun retainReadPermission(treeUri: Uri, resultFlags: Int) {
        if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun importChapter(treeUri: Uri) {
        setImportRunning(true)
        statusText.setText(R.string.import_status_running)

        Thread({
            val result = runCatching {
                val sources = DocumentTreeReader(contentResolver).read(treeUri)
                ProjectImporter(filesDir.toPath().resolve("workspace")).importProject(sources)
            }

            runOnUiThread {
                result.fold(
                    onSuccess = { outcome ->
                        statusText.text = getString(
                            R.string.import_status_success,
                            outcome.report.importedCount,
                            outcome.report.byteCount,
                            "projects/${outcome.manifest.projectId}/reports/${outcome.report.jobId}.json",
                        )
                    },
                    onFailure = { failure ->
                        statusText.text = when (failure) {
                            is ProjectImportException -> getString(
                                R.string.import_status_failed,
                                failure.code.name,
                                failure.reportRelativePath,
                            )
                            else -> getString(R.string.import_status_failed_unknown)
                        }
                    },
                )
                setImportRunning(false)
                refreshDurableState()
            }
        }, IMPORT_THREAD_NAME).start()
    }

    private fun requestAnalysisStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (analysisActive) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingAnalysisProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startAnalysis(projectId)
    }

    private fun startAnalysis(projectId: String) {
        val intent = DetectionForegroundService.startIntent(this, projectId)
        resumeRequestedThisProcess = true
        startForegroundService(intent)
        setAnalysisActive(true)
        detectionStatus.setText(R.string.detection_status_starting)
        detectionProgress.visibility = View.VISIBLE
        detectionProgress.isIndeterminate = false
        detectionProgress.max = currentProject?.manifest?.pages?.size?.coerceAtLeast(1) ?: 1
        detectionProgress.progress = 0
    }

    private fun cancelAnalysis() {
        if (!analysisActive) return
        startService(DetectionForegroundService.cancelIntent(this))
        cancelAnalysisButton.isEnabled = false
        detectionStatus.setText(R.string.detection_notification_cancelling)
    }

    private fun requestOcrStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (currentRun == null || importRunning || analysisActive || ocrActive) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingOcrProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startOcr(projectId)
    }

    private fun startOcr(projectId: String) {
        ocrResumeRequestedThisProcess = true
        startForegroundService(OcrForegroundService.startIntent(this, projectId))
        setOcrActive(true)
        ocrStatus.setText(R.string.ocr_status_starting)
        ocrProgress.visibility = View.VISIBLE
        ocrProgress.isIndeterminate = false
        ocrProgress.max = 1
        ocrProgress.progress = 0
    }

    private fun cancelOcr() {
        if (!ocrActive) return
        startService(OcrForegroundService.cancelIntent(this))
        cancelOcrButton.isEnabled = false
        ocrStatus.setText(R.string.ocr_notification_cancelling)
    }

    private fun refreshDurableState(progressOverride: DetectionProgress? = null) {
        val priorProjectId = currentProject?.manifest?.projectId
        currentProject = catalog.latestProject()
        val project = currentProject
        if (project == null) {
            currentRun = null
            currentPreviewIndex = 0
            setAnalysisActive(false)
            analysisButton.isEnabled = false
            detectionProgress.visibility = View.GONE
            detectionStatus.setText(R.string.detection_status_no_project)
            clearPreview()
            resetOcrState()
            return
        }

        if (priorProjectId != project.manifest.projectId) currentPreviewIndex = 0
        currentRun = catalog.latestPublishedRun(project.manifest.projectId)
        val durableProgress = progressOverride
            ?.takeIf { it.projectId == project.manifest.projectId }
            ?: progressFromDurableState(project)
        if (durableProgress != null) {
            renderProgress(durableProgress)
            if (
                progressOverride == null &&
                DetectionResumePolicy.shouldResume(
                    durableProgress.status,
                    resumeRequestedThisProcess,
                )
            ) {
                resumeRequestedThisProcess = true
                startForegroundService(
                    DetectionForegroundService.startIntent(
                        this,
                        project.manifest.projectId,
                    ),
                )
            }
        } else {
            setAnalysisActive(false)
            analysisButton.isEnabled = !importRunning && !ocrActive
            detectionProgress.visibility = View.GONE
            detectionStatus.setText(R.string.detection_status_ready)
        }
        showPreview(currentPreviewIndex)
        refreshOcrDurableState()
    }

    private fun refreshOcrDurableState(progressOverride: OcrProgress? = null) {
        val project = currentProject
        val detectionRun = currentRun
        if (project == null || detectionRun == null) {
            resetOcrState()
            return
        }

        val priorRunKey = currentOcrRun?.artifact?.runArtifactKey
        currentOcrRun = catalog.latestPublishedOcrRun(project.manifest.projectId)
            ?.takeIf { it.artifact.detectionRunArtifactKey == detectionRun.artifact.runArtifactKey }
        if (currentOcrRun?.artifact?.runArtifactKey != priorRunKey) currentOcrPreviewIndex = 0

        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    ocrProgressMatchesDetection(project, progress)
            }
            ?: progressFromOcrDurableState(project, detectionRun.artifact.runArtifactKey)
        if (durableProgress != null) {
            renderOcrProgress(durableProgress)
            if (
                progressOverride == null &&
                !OcrForegroundService.isTaskActive() &&
                OcrResumePolicy.shouldResume(
                    durableProgress.status,
                    ocrResumeRequestedThisProcess,
                )
            ) {
                ocrResumeRequestedThisProcess = true
                startForegroundService(
                    OcrForegroundService.startIntent(this, project.manifest.projectId),
                )
            }
        } else {
            setOcrActive(false)
            ocrButton.isEnabled = !importRunning && !analysisActive
            ocrProgress.visibility = View.GONE
            ocrStatus.setText(R.string.ocr_status_ready)
        }
        showOcrPreview(currentOcrPreviewIndex)
    }

    private fun ocrProgressMatchesDetection(project: ProjectRef, progress: OcrProgress): Boolean {
        val job = OcrArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.detectionRunArtifactKey == currentRun?.artifact?.runArtifactKey
    }

    private fun progressFromOcrDurableState(
        project: ProjectRef,
        detectionRunArtifactKey: String,
    ): OcrProgress? {
        currentOcrRun?.report?.let { report ->
            val terminalCount = report.recognizedRegionCount +
                report.needsFallbackRegionCount +
                report.noTextRegionCount +
                report.preservedRegionCount
            return OcrProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalRegionCount = terminalCount,
                totalRegionCount = report.totalRegionCount,
                committedPageCount = report.committedPageCount,
                totalPageCount = report.totalPageCount,
                errorCode = report.error?.code,
            )
        }
        val job = OcrArtifactStore(project.directory).findLatestJob() ?: return null
        if (
            job.projectId != project.manifest.projectId ||
            job.detectionRunArtifactKey != detectionRunArtifactKey
        ) {
            return null
        }
        return OcrProgress(
            projectId = job.projectId,
            jobId = job.jobId,
            runArtifactKey = job.runArtifactKey,
            status = job.status,
            terminalRegionCount = job.pages.sumOf { page ->
                page.regions.count { region -> region.state.isOcrTerminal() }
            },
            totalRegionCount = job.pages.sumOf { it.regions.size },
            committedPageCount = job.pages.count { it.state == OcrPageState.COMMITTED },
            totalPageCount = job.pages.size,
            currentOrder = job.pages.firstOrNull { it.state == OcrPageState.RUNNING }?.order,
            currentPageId = job.pages.firstOrNull { it.state == OcrPageState.RUNNING }?.pageId,
            currentRegionId = job.pages.asSequence()
                .flatMap { it.regions.asSequence() }
                .firstOrNull { it.state == OcrRegionState.RUNNING }
                ?.ocrRegionId,
            errorCode = job.error?.code,
        )
    }

    private fun progressFromDurableState(project: ProjectRef): DetectionProgress? {
        currentRun?.report?.let { report ->
            return DetectionProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                committedPageCount = report.committedPageCount,
                preservedPageCount = report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                errorCode = report.error?.code,
            )
        }
        val job = DetectionArtifactStore(project.directory).findLatestJob() ?: return null
        if (job.projectId != project.manifest.projectId) return null
        return DetectionProgress(
            projectId = job.projectId,
            jobId = job.jobId,
            runArtifactKey = job.runArtifactKey,
            status = job.status,
            committedPageCount = job.pages.count { it.state == DetectionPageState.COMMITTED },
            preservedPageCount = job.pages.count { it.state == DetectionPageState.PRESERVED_SOURCE },
            totalPageCount = job.pages.size,
            currentOrder = job.pages.firstOrNull { it.state == DetectionPageState.RUNNING }?.order,
            errorCode = job.error?.code,
        )
    }

    private fun renderProgress(progress: DetectionProgress) {
        val completed = progress.committedPageCount + progress.preservedPageCount
        val active = progress.status.isActive()
        if (!active) resumeRequestedThisProcess = false
        setAnalysisActive(active)
        analysisButton.isEnabled = !active && !importRunning && !ocrActive && currentProject != null
        detectionProgress.visibility = View.VISIBLE
        detectionProgress.isIndeterminate = false
        detectionProgress.max = progress.totalPageCount.coerceAtLeast(1)
        detectionProgress.progress = completed.coerceIn(0, detectionProgress.max)
        detectionStatus.text = when (progress.status) {
            DetectionJobStatus.QUEUED -> getString(R.string.detection_status_starting)
            DetectionJobStatus.DOWNLOADING_MODEL -> getString(
                R.string.detection_status_downloading,
                progress.downloadedBytes,
                progress.totalBytes,
            )
            DetectionJobStatus.RUNNING -> getString(
                R.string.detection_status_progress,
                completed,
                progress.totalPageCount,
            )
            DetectionJobStatus.SUCCEEDED -> getString(
                R.string.detection_status_succeeded,
                progress.committedPageCount,
            )
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES -> getString(
                R.string.detection_status_succeeded_preserved,
                progress.committedPageCount,
                progress.preservedPageCount,
            )
            DetectionJobStatus.CANCELLED -> getString(R.string.detection_status_cancelled)
            DetectionJobStatus.FAILED -> getString(
                R.string.detection_status_failed,
                progress.errorCode.orEmpty(),
            )
        }
    }

    private fun renderOcrProgress(progress: OcrProgress) {
        val active = progress.status.isActive()
        if (!active) ocrResumeRequestedThisProcess = false
        setOcrActive(active)
        ocrButton.isEnabled = !active && !importRunning && !analysisActive && currentRun != null
        ocrProgress.visibility = View.VISIBLE
        ocrProgress.isIndeterminate = progress.status == OcrJobStatus.LOADING_MODEL
        ocrProgress.max = progress.totalRegionCount.coerceAtLeast(1)
        ocrProgress.progress = progress.terminalRegionCount.coerceIn(0, ocrProgress.max)
        ocrStatus.text = when (progress.status) {
            OcrJobStatus.QUEUED -> getString(R.string.ocr_status_starting)
            OcrJobStatus.DOWNLOADING_MODEL -> getString(
                R.string.ocr_status_downloading,
                progress.downloadedBytes,
                progress.totalDownloadBytes,
            )
            OcrJobStatus.LOADING_MODEL -> getString(R.string.ocr_status_loading)
            OcrJobStatus.RUNNING -> getString(
                R.string.ocr_status_progress,
                progress.terminalRegionCount,
                progress.totalRegionCount,
            )
            OcrJobStatus.SUCCEEDED -> getString(
                R.string.ocr_status_succeeded,
                progress.terminalRegionCount,
            )
            OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> getString(
                R.string.ocr_status_succeeded_preserved,
            )
            OcrJobStatus.CANCELLED -> getString(R.string.ocr_status_cancelled)
            OcrJobStatus.FAILED -> getString(
                R.string.ocr_status_failed,
                progress.errorCode.orEmpty(),
            )
        }
    }

    private fun showPreview(requestedIndex: Int) {
        val run = currentRun
        val project = currentProject
        if (run == null || project == null || run.artifact.entries.isEmpty()) {
            clearPreview()
            return
        }
        val entries = run.artifact.entries.sortedBy(DetectionRunEntry::order)
        currentPreviewIndex = requestedIndex.coerceIn(entries.indices)
        val entry = entries[currentPreviewIndex]
        val imagePath = when (entry.state) {
            DetectionPageState.COMMITTED -> entry.previewPath?.let { relative ->
                resolveRegularFileInside(run.directory, relative)
            }
            DetectionPageState.PRESERVED_SOURCE -> project.manifest.pages
                .firstOrNull { it.order == entry.order }
                ?.storedPath
                ?.let { relative -> resolveRegularFileInside(project.directory, relative) }
            DetectionPageState.PENDING,
            DetectionPageState.RUNNING,
            -> null
        }

        val bitmap = imagePath?.let(::decodePreviewBitmap)
        clearDisplayedBitmap()
        if (bitmap != null) {
            displayedBitmap = bitmap
            previewImage.setImageBitmap(bitmap)
            previewImage.visibility = View.VISIBLE
        } else {
            previewImage.setImageDrawable(null)
            previewImage.visibility = View.GONE
        }
        preservedPageMarker.visibility = if (entry.state == DetectionPageState.PRESERVED_SOURCE) {
            View.VISIBLE
        } else if (bitmap == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        preservedPageMarker.text = if (entry.state == DetectionPageState.PRESERVED_SOURCE) {
            getString(R.string.preview_preserved_page, entry.error?.code.orEmpty())
        } else {
            getString(R.string.preview_unavailable)
        }
        pageIndicator.text = getString(
            R.string.preview_page_indicator,
            currentPreviewIndex + 1,
            entries.size,
        )
        previousPageButton.isEnabled = currentPreviewIndex > 0
        nextPageButton.isEnabled = currentPreviewIndex < entries.lastIndex
    }

    private fun clearPreview() {
        clearDisplayedBitmap()
        previewImage.setImageDrawable(null)
        previewImage.visibility = View.GONE
        preservedPageMarker.visibility = View.GONE
        pageIndicator.setText(R.string.preview_empty)
        previousPageButton.isEnabled = false
        nextPageButton.isEnabled = false
    }

    private fun showOcrPreview(requestedIndex: Int) {
        val run = currentOcrRun
        val project = currentProject
        if (run == null || project == null || run.artifact.entries.isEmpty()) {
            clearOcrPreview()
            return
        }
        val entries = run.artifact.entries.sortedBy(OcrRunEntry::order)
        currentOcrPreviewIndex = requestedIndex.coerceIn(entries.indices)
        val entry = entries[currentOcrPreviewIndex]
        val imagePath = when (entry.state) {
            OcrPageState.COMMITTED -> entry.previewPath?.let { relative ->
                resolveRegularFileInside(run.directory, relative)
            }
            OcrPageState.PRESERVED_SOURCE -> project.manifest.pages
                .firstOrNull { it.order == entry.order }
                ?.storedPath
                ?.let { relative -> resolveRegularFileInside(project.directory, relative) }
            OcrPageState.PENDING,
            OcrPageState.RUNNING,
            -> null
        }
        val pageArtifact = if (entry.state == OcrPageState.COMMITTED) {
            catalog.readPublishedOcrPage(run, entry.pageId)
        } else {
            null
        }
        val bitmap = imagePath?.let(::decodePreviewBitmap)
        clearDisplayedOcrBitmap()
        if (bitmap != null) {
            displayedOcrBitmap = bitmap
            ocrPreviewImage.setImageBitmap(bitmap)
            ocrPreviewImage.visibility = View.VISIBLE
        } else {
            ocrPreviewImage.setImageDrawable(null)
            ocrPreviewImage.visibility = View.GONE
        }

        val protectedCount = pageArtifact?.regions?.count { region ->
            region.state == OcrRegionState.NEEDS_FALLBACK ||
                region.state == OcrRegionState.PRESERVED_SOURCE
        } ?: 0
        ocrPreservedPageMarker.visibility = when {
            entry.state == OcrPageState.PRESERVED_SOURCE -> View.VISIBLE
            bitmap == null -> View.VISIBLE
            protectedCount > 0 -> View.VISIBLE
            else -> View.GONE
        }
        ocrPreservedPageMarker.text = when {
            entry.state == OcrPageState.PRESERVED_SOURCE -> getString(
                R.string.ocr_preview_preserved_page,
                entry.error?.code.orEmpty(),
            )
            bitmap == null -> getString(R.string.preview_unavailable)
            protectedCount > 0 -> getString(
                R.string.ocr_preview_protected_regions,
                protectedCount,
            )
            else -> ""
        }
        ocrDetailText.text = pageArtifact?.regions
            ?.sortedBy { it.candidate.readingOrderRank }
            ?.joinToString("\n") { region ->
                getString(
                    R.string.ocr_region_detail,
                    region.candidate.readingOrderRank + 1,
                    region.state.displayLabel(),
                    region.displayText(),
                )
            }
            .orEmpty()
        ocrPageIndicator.text = getString(
            R.string.ocr_preview_page_indicator,
            currentOcrPreviewIndex + 1,
            entries.size,
        )
        previousOcrPageButton.isEnabled = currentOcrPreviewIndex > 0
        nextOcrPageButton.isEnabled = currentOcrPreviewIndex < entries.lastIndex
    }

    private fun clearOcrPreview() {
        clearDisplayedOcrBitmap()
        ocrPreviewImage.setImageDrawable(null)
        ocrPreviewImage.visibility = View.GONE
        ocrPreservedPageMarker.visibility = View.GONE
        ocrPageIndicator.setText(R.string.ocr_preview_empty)
        ocrDetailText.text = ""
        previousOcrPageButton.isEnabled = false
        nextOcrPageButton.isEnabled = false
    }

    private fun resetOcrState() {
        currentOcrRun = null
        currentOcrPreviewIndex = 0
        setOcrActive(false)
        ocrButton.isEnabled = false
        ocrProgress.visibility = View.GONE
        ocrStatus.setText(R.string.ocr_status_no_detection)
        clearOcrPreview()
    }

    private fun OcrRegionState.displayLabel(): String = when (this) {
        OcrRegionState.PENDING -> "等待"
        OcrRegionState.RUNNING -> "识别中"
        OcrRegionState.RECOGNIZED -> "已识别"
        OcrRegionState.NEEDS_FALLBACK -> "低置信，保留原画"
        OcrRegionState.NO_TEXT_CONFIRMED -> "确认无文字"
        OcrRegionState.PRESERVED_SOURCE -> "失败，保留原画"
    }

    private fun rs.masumi.core.ocr.OcrRegionArtifact.displayText(): String {
        val selectedText = selectedAttemptIndex
            ?.let(attempts::getOrNull)
            ?.normalizedText
            ?.takeIf(String::isNotBlank)
        return selectedText ?: when (state) {
            OcrRegionState.NO_TEXT_CONFIRMED -> "（无文字）"
            OcrRegionState.NEEDS_FALLBACK,
            OcrRegionState.PRESERVED_SOURCE,
            -> "（未采用 OCR 结果）"
            OcrRegionState.PENDING,
            OcrRegionState.RUNNING,
            OcrRegionState.RECOGNIZED,
            -> "（无可用文字）"
        }
    }

    private fun resolveRegularFileInside(root: Path, relativePath: String): Path? = runCatching {
        require(relativePath.isNotBlank())
        val rootReal = root.toRealPath()
        val resolved = root.resolve(relativePath).normalize().toRealPath()
        require(resolved.startsWith(rootReal))
        require(Files.isRegularFile(resolved))
        resolved
    }.getOrNull()

    private fun decodePreviewBitmap(path: Path): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = resources.displayMetrics.widthPixels.coerceAtLeast(1) * 2
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > target || bounds.outHeight / sampleSize > target * 3) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path.toString(),
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun clearDisplayedBitmap() {
        displayedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        displayedBitmap = null
    }

    private fun clearDisplayedOcrBitmap() {
        displayedOcrBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        displayedOcrBitmap = null
    }

    private fun setImportRunning(running: Boolean) {
        importRunning = running
        importButton.isEnabled = !running && !analysisActive && !ocrActive
        analysisButton.isEnabled = !running && !analysisActive && !ocrActive && currentProject != null
        ocrButton.isEnabled = !running && !analysisActive && !ocrActive && currentRun != null
        importProgress.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun setAnalysisActive(active: Boolean) {
        analysisActive = active
        importButton.isEnabled = !importRunning && !active && !ocrActive
        analysisButton.isEnabled = currentProject != null && !active && !ocrActive
        cancelAnalysisButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelAnalysisButton.isEnabled = active
        ocrButton.isEnabled = currentRun != null && !importRunning && !active && !ocrActive
    }

    private fun setOcrActive(active: Boolean) {
        ocrActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !active
        analysisButton.isEnabled = currentProject != null && !analysisActive && !active
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !active
        cancelOcrButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelOcrButton.isEnabled = active
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDetectionReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(DetectionStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                detectionReceiver,
                filter,
                internalStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(detectionReceiver, filter, internalStatusPermission(), null)
        }
        receiverRegistered = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerOcrReceiver() {
        if (ocrReceiverRegistered) return
        val filter = IntentFilter(OcrStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                ocrReceiver,
                filter,
                internalOcrStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter, internalOcrStatusPermission(), null)
        }
        ocrReceiverRegistered = true
    }

    private fun internalStatusPermission(): String =
        "$packageName.permission.INTERNAL_DETECTION_STATUS"

    private fun internalOcrStatusPermission(): String =
        "$packageName.permission.INTERNAL_OCR_STATUS"

    private fun notificationPermissionWasRequested(): Boolean = getPreferences(MODE_PRIVATE)
        .getBoolean(PREF_NOTIFICATION_REQUESTED, false)

    private fun markNotificationPermissionRequested() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_NOTIFICATION_REQUESTED, true).apply()
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

    private fun OcrRegionState.isOcrTerminal(): Boolean = when (this) {
        OcrRegionState.RECOGNIZED,
        OcrRegionState.NEEDS_FALLBACK,
        OcrRegionState.NO_TEXT_CONFIRMED,
        OcrRegionState.PRESERVED_SOURCE,
        -> true
        OcrRegionState.PENDING,
        OcrRegionState.RUNNING,
        -> false
    }

    private companion object {
        const val REQUEST_OPEN_CHAPTER = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
        const val IMPORT_THREAD_NAME = "masumi-import"
        const val PREF_NOTIFICATION_REQUESTED = "notification_permission_requested"
    }
}
