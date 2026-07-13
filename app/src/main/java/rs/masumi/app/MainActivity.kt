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
import rs.masumi.app.detection.DetectionStatusBroadcast
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.importer.ProjectImportException
import rs.masumi.core.importer.ProjectImporter
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
    private lateinit var catalog: ProjectCatalog

    private var importRunning = false
    private var analysisActive = false
    private var receiverRegistered = false
    private var currentProject: ProjectRef? = null
    private var currentRun: PublishedDetectionRun? = null
    private var currentPreviewIndex = 0
    private var displayedBitmap: Bitmap? = null
    private var pendingAnalysisProjectId: String? = null

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(DetectionStatusBroadcast::parse) ?: return
            refreshDurableState(progress)
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
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))

        importButton.setOnClickListener { openChapterFolder() }
        analysisButton.setOnClickListener { requestAnalysisStart() }
        cancelAnalysisButton.setOnClickListener { cancelAnalysis() }
        previousPageButton.setOnClickListener { showPreview(currentPreviewIndex - 1) }
        nextPageButton.setOnClickListener { showPreview(currentPreviewIndex + 1) }
    }

    override fun onStart() {
        super.onStart()
        registerDetectionReceiver()
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
        super.onStop()
    }

    override fun onDestroy() {
        clearDisplayedBitmap()
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
    }

    private fun openChapterFolder() {
        if (importRunning || analysisActive) return

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
            return
        }

        if (priorProjectId != project.manifest.projectId) currentPreviewIndex = 0
        currentRun = catalog.latestPublishedRun(project.manifest.projectId)
        val durableProgress = progressOverride
            ?.takeIf { it.projectId == project.manifest.projectId }
            ?: progressFromDurableState(project)
        if (durableProgress != null) {
            renderProgress(durableProgress)
        } else {
            setAnalysisActive(false)
            analysisButton.isEnabled = true
            detectionProgress.visibility = View.GONE
            detectionStatus.setText(R.string.detection_status_ready)
        }
        showPreview(currentPreviewIndex)
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
        setAnalysisActive(active)
        analysisButton.isEnabled = !active && currentProject != null
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

    private fun setImportRunning(running: Boolean) {
        importRunning = running
        importButton.isEnabled = !running && !analysisActive
        importProgress.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun setAnalysisActive(active: Boolean) {
        analysisActive = active
        importButton.isEnabled = !importRunning && !active
        analysisButton.isEnabled = currentProject != null && !active
        cancelAnalysisButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelAnalysisButton.isEnabled = active
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

    private fun internalStatusPermission(): String =
        "$packageName.permission.INTERNAL_DETECTION_STATUS"

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

    private companion object {
        const val REQUEST_OPEN_CHAPTER = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
        const val IMPORT_THREAD_NAME = "masumi-import"
        const val PREF_NOTIFICATION_REQUESTED = "notification_permission_requested"
    }
}
