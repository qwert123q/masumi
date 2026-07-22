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
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import rs.masumi.app.cleanup.CleanupForegroundService
import rs.masumi.app.cleanup.CleanupProgress
import rs.masumi.app.cleanup.CleanupResumePolicy
import rs.masumi.app.cleanup.CleanupStatusBroadcast
import rs.masumi.app.detection.DetectionForegroundService
import rs.masumi.app.detection.DetectionProgress
import rs.masumi.app.detection.DetectionResumePolicy
import rs.masumi.app.detection.DetectionStatusBroadcast
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.exporting.ExportForegroundService
import rs.masumi.app.exporting.ExportProgress
import rs.masumi.app.exporting.ExportResumePolicy
import rs.masumi.app.exporting.ExportStatusBroadcast
import rs.masumi.app.quality.QualityForegroundService
import rs.masumi.app.quality.QualityProgress
import rs.masumi.app.quality.QualityResumePolicy
import rs.masumi.app.quality.QualityStatusBroadcast
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.ocr.OcrProgress
import rs.masumi.app.ocr.OcrResumePolicy
import rs.masumi.app.ocr.OcrStatusBroadcast
import rs.masumi.app.translation.SavedTranslationSettings
import rs.masumi.app.translation.TranslationForegroundService
import rs.masumi.app.translation.TranslationProgress
import rs.masumi.app.translation.TranslationResumePolicy
import rs.masumi.app.translation.TranslationSettingsStore
import rs.masumi.app.translation.TranslationStatusBroadcast
import rs.masumi.app.typesetting.TypesettingForegroundService
import rs.masumi.app.typesetting.TypesettingProgress
import rs.masumi.app.typesetting.TypesettingResumePolicy
import rs.masumi.app.typesetting.TypesettingStatusBroadcast
import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.exporting.ExportPageSource
import rs.masumi.core.exporting.ExportPageState
import rs.masumi.core.importer.ProjectImportException
import rs.masumi.core.importer.ProjectImporter
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.quality.QualityArtifactStore
import rs.masumi.core.quality.QualityJobStatus
import rs.masumi.core.quality.QualityPageState
import rs.masumi.core.quality.QualityPageVerdict
import rs.masumi.core.quality.QualityPolicy
import rs.masumi.core.quality.allowsExport
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationPageState
import rs.masumi.core.translation.TranslationWindowState
import rs.masumi.core.translation.isTerminal
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.app.detection.PublishedQualityRun
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPageState
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingRunEntry
import java.nio.file.Files
import java.nio.file.Path

class MainActivity : Activity() {
    private lateinit var contentPager: HorizontalSwipeViewFlipper
    private lateinit var workspacePage: ScrollView
    private lateinit var detailsPage: ScrollView
    private lateinit var workspaceTabButton: Button
    private lateinit var detailsTabButton: Button
    private lateinit var processButton: Button
    private lateinit var pipelineProgress: ProgressBar
    private lateinit var pipelineProgressText: TextView
    private lateinit var pipelineStatus: TextView
    private lateinit var translationSettingsShortcutButton: Button
    private lateinit var detailsShortcutButton: Button
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
    private lateinit var translationApiUrl: EditText
    private lateinit var translationApiKey: EditText
    private lateinit var translationModel: EditText
    private lateinit var translationSettingsToggleButton: Button
    private lateinit var translationSettingsContainer: View
    private lateinit var saveTranslationSettingsButton: Button
    private lateinit var translationButton: Button
    private lateinit var cancelTranslationButton: Button
    private lateinit var translationProgress: ProgressBar
    private lateinit var translationStatus: TextView
    private lateinit var cleanupButton: Button
    private lateinit var cancelCleanupButton: Button
    private lateinit var cleanupProgress: ProgressBar
    private lateinit var cleanupStatus: TextView
    private lateinit var cleanupPreviewImage: ImageView
    private lateinit var cleanupPreservedPageMarker: TextView
    private lateinit var cleanupPageIndicator: TextView
    private lateinit var previousCleanupPageButton: Button
    private lateinit var nextCleanupPageButton: Button
    private lateinit var cleanupDetailText: TextView
    private lateinit var typesettingButton: Button
    private lateinit var cancelTypesettingButton: Button
    private lateinit var typesettingProgress: ProgressBar
    private lateinit var typesettingStatus: TextView
    private lateinit var typesettingPreviewImage: ImageView
    private lateinit var typesettingPreservedPageMarker: TextView
    private lateinit var typesettingPageIndicator: TextView
    private lateinit var previousTypesettingPageButton: Button
    private lateinit var nextTypesettingPageButton: Button
    private lateinit var typesettingDetailText: TextView
    private lateinit var qualityButton: Button
    private lateinit var cancelQualityButton: Button
    private lateinit var qualityProgress: ProgressBar
    private lateinit var qualityStatus: TextView
    private lateinit var exportButton: Button
    private lateinit var cancelExportButton: Button
    private lateinit var exportProgress: ProgressBar
    private lateinit var exportStatus: TextView
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private lateinit var catalog: ProjectCatalog
    private lateinit var translationSettingsStore: TranslationSettingsStore

    private var importRunning = false
    private var analysisActive = false
    private var ocrActive = false
    private var translationActive = false
    private var cleanupActive = false
    private var typesettingActive = false
    private var qualityActive = false
    private var exportActive = false
    private var automaticPipelineRequested = false
        set(value) {
            field = value
            if (::translationSettingsStore.isInitialized) {
                getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_AUTOMATIC_PIPELINE, value).apply()
            }
        }
    private var receiverRegistered = false
    private var ocrReceiverRegistered = false
    private var translationReceiverRegistered = false
    private var cleanupReceiverRegistered = false
    private var typesettingReceiverRegistered = false
    private var qualityReceiverRegistered = false
    private var exportReceiverRegistered = false
    private var currentProject: ProjectRef? = null
    private var currentRun: PublishedDetectionRun? = null
    private var currentPreviewIndex = 0
    private var currentOcrRun: PublishedOcrRun? = null
    private var currentOcrPreviewIndex = 0
    private var currentTranslationRun: PublishedTranslationRun? = null
    private var currentCleanupRun: PublishedCleanupRun? = null
    private var currentCleanupPreviewIndex = 0
    private var currentTypesettingRun: PublishedTypesettingRun? = null
    private var currentQualityRun: PublishedQualityRun? = null
    private var currentTypesettingPreviewIndex = 0
    private var displayedBitmap: Bitmap? = null
    private var displayedOcrBitmap: Bitmap? = null
    private var displayedCleanupBitmap: Bitmap? = null
    private var displayedTypesettingBitmap: Bitmap? = null
    private var pendingAnalysisProjectId: String? = null
    private var pendingOcrProjectId: String? = null
    private var pendingTranslationProjectId: String? = null
    private var pendingCleanupProjectId: String? = null
    private var pendingTypesettingProjectId: String? = null
    private var pendingQualityProjectId: String? = null
    private var resumeRequestedThisProcess = false
    private var ocrResumeRequestedThisProcess = false
    private var translationResumeRequestedThisProcess = false
    private var cleanupResumeRequestedThisProcess = false
    private var typesettingResumeRequestedThisProcess = false
    private var qualityResumeRequestedThisProcess = false
    private var exportResumeRequestedThisProcess = false

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(DetectionStatusBroadcast::parse) ?: return
            refreshDurableState(progress)
            if (progress.status == DetectionJobStatus.CANCELLED || progress.status == DetectionJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(OcrStatusBroadcast::parse) ?: return
            refreshOcrDurableState(progress)
            if (progress.status == OcrJobStatus.CANCELLED || progress.status == OcrJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val translationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(TranslationStatusBroadcast::parse) ?: return
            refreshTranslationDurableState(progress)
            if (progress.status == TranslationJobStatus.CANCELLED || progress.status == TranslationJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val cleanupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(CleanupStatusBroadcast::parse) ?: return
            refreshCleanupDurableState(progress)
            if (progress.status == CleanupJobStatus.CANCELLED || progress.status == CleanupJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val typesettingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(TypesettingStatusBroadcast::parse) ?: return
            refreshTypesettingDurableState(progress)
            if (progress.status == TypesettingJobStatus.CANCELLED || progress.status == TypesettingJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val qualityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(QualityStatusBroadcast::parse) ?: return
            val project = currentProject
            val job = project?.let { QualityArtifactStore(it.directory).readJob(progress.jobId) }
            if (
                job != null &&
                job.dependencies.typesettingRunArtifactKey != currentTypesettingRun?.artifact?.runArtifactKey
            ) {
                refreshDurableState()
            } else {
                refreshQualityDurableState(progress)
            }
            if (
                progress.status == QualityJobStatus.CANCELLED ||
                progress.status == QualityJobStatus.FAILED ||
                progress.status == QualityJobStatus.BLOCKED
            ) {
                automaticPipelineRequested = false
            }
            onPipelineStateChanged()
        }
    }

    private val exportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(ExportStatusBroadcast::parse) ?: return
            refreshExportDurableState(progress)
            onPipelineStateChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentPager = findViewById(R.id.contentPager)
        workspacePage = findViewById(R.id.workspacePage)
        detailsPage = findViewById(R.id.detailsPage)
        workspaceTabButton = findViewById(R.id.workspaceTabButton)
        detailsTabButton = findViewById(R.id.detailsTabButton)
        processButton = findViewById(R.id.processButton)
        pipelineProgress = findViewById(R.id.pipelineProgress)
        pipelineProgressText = findViewById(R.id.pipelineProgressText)
        pipelineStatus = findViewById(R.id.pipelineStatus)
        translationSettingsShortcutButton = findViewById(R.id.translationSettingsShortcutButton)
        detailsShortcutButton = findViewById(R.id.detailsShortcutButton)
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
        translationApiUrl = findViewById(R.id.translationApiUrl)
        translationApiKey = findViewById(R.id.translationApiKey)
        translationModel = findViewById(R.id.translationModel)
        translationSettingsToggleButton = findViewById(R.id.translationSettingsToggleButton)
        translationSettingsContainer = findViewById(R.id.translationSettingsContainer)
        saveTranslationSettingsButton = findViewById(R.id.saveTranslationSettingsButton)
        translationButton = findViewById(R.id.translationButton)
        cancelTranslationButton = findViewById(R.id.cancelTranslationButton)
        translationProgress = findViewById(R.id.translationProgress)
        translationStatus = findViewById(R.id.translationStatus)
        cleanupButton = findViewById(R.id.cleanupButton)
        cancelCleanupButton = findViewById(R.id.cancelCleanupButton)
        cleanupProgress = findViewById(R.id.cleanupProgress)
        cleanupStatus = findViewById(R.id.cleanupStatus)
        cleanupPreviewImage = findViewById(R.id.cleanupPreviewImage)
        cleanupPreservedPageMarker = findViewById(R.id.cleanupPreservedPageMarker)
        cleanupPageIndicator = findViewById(R.id.cleanupPageIndicator)
        previousCleanupPageButton = findViewById(R.id.previousCleanupPageButton)
        nextCleanupPageButton = findViewById(R.id.nextCleanupPageButton)
        cleanupDetailText = findViewById(R.id.cleanupDetailText)
        typesettingButton = findViewById(R.id.typesettingButton)
        cancelTypesettingButton = findViewById(R.id.cancelTypesettingButton)
        typesettingProgress = findViewById(R.id.typesettingProgress)
        typesettingStatus = findViewById(R.id.typesettingStatus)
        typesettingPreviewImage = findViewById(R.id.typesettingPreviewImage)
        typesettingPreservedPageMarker = findViewById(R.id.typesettingPreservedPageMarker)
        typesettingPageIndicator = findViewById(R.id.typesettingPageIndicator)
        previousTypesettingPageButton = findViewById(R.id.previousTypesettingPageButton)
        nextTypesettingPageButton = findViewById(R.id.nextTypesettingPageButton)
        typesettingDetailText = findViewById(R.id.typesettingDetailText)
        qualityButton = findViewById(R.id.qualityButton)
        cancelQualityButton = findViewById(R.id.cancelQualityButton)
        qualityProgress = findViewById(R.id.qualityProgress)
        qualityStatus = findViewById(R.id.qualityStatus)
        exportButton = findViewById(R.id.exportButton)
        cancelExportButton = findViewById(R.id.cancelExportButton)
        exportProgress = findViewById(R.id.exportProgress)
        exportStatus = findViewById(R.id.exportStatus)
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))
        translationSettingsStore = TranslationSettingsStore(this)
        automaticPipelineRequested = getPreferences(MODE_PRIVATE)
            .getBoolean(PREF_AUTOMATIC_PIPELINE, false)
        val savedTranslationSettings = translationSettingsStore.loadSaved()
        savedTranslationSettings?.let { saved ->
            translationApiUrl.setText(saved.apiUrl)
            translationApiKey.setText(saved.apiKey)
            translationModel.setText(saved.model)
        }
        setTranslationSettingsExpanded(false)
        showPage(
            PageNavigation.normalize(savedInstanceState?.getInt(STATE_SELECTED_PAGE)),
            animate = false,
        )
        contentPager.onSwipe = { direction ->
            showPage(
                if (direction == HorizontalSwipeViewFlipper.Direction.LEFT) {
                    PAGE_DETAILS
                } else {
                    PAGE_WORKSPACE
                },
            )
        }
        registerPredictiveBackCallback()

        workspaceTabButton.setOnClickListener { showPage(PAGE_WORKSPACE) }
        detailsTabButton.setOnClickListener { showPage(PAGE_DETAILS) }
        detailsShortcutButton.setOnClickListener { showPage(PAGE_DETAILS) }
        translationSettingsShortcutButton.setOnClickListener {
            showTranslationSettings()
        }
        processButton.setOnClickListener { startAutomaticPipeline() }
        importButton.setOnClickListener { openChapterFolder() }
        analysisButton.setOnClickListener { requestAnalysisStart() }
        cancelAnalysisButton.setOnClickListener { cancelAnalysis() }
        previousPageButton.setOnClickListener { showPreview(currentPreviewIndex - 1) }
        nextPageButton.setOnClickListener { showPreview(currentPreviewIndex + 1) }
        ocrButton.setOnClickListener { requestOcrStart() }
        cancelOcrButton.setOnClickListener { cancelOcr() }
        previousOcrPageButton.setOnClickListener { showOcrPreview(currentOcrPreviewIndex - 1) }
        nextOcrPageButton.setOnClickListener { showOcrPreview(currentOcrPreviewIndex + 1) }
        translationSettingsToggleButton.setOnClickListener {
            setTranslationSettingsExpanded(translationSettingsContainer.visibility != View.VISIBLE)
        }
        saveTranslationSettingsButton.setOnClickListener { saveTranslationSettings() }
        translationButton.setOnClickListener { requestTranslationStart() }
        cancelTranslationButton.setOnClickListener { cancelTranslation() }
        cleanupButton.setOnClickListener { requestCleanupStart() }
        cancelCleanupButton.setOnClickListener { cancelCleanup() }
        previousCleanupPageButton.setOnClickListener { showCleanupPreview(currentCleanupPreviewIndex - 1) }
        nextCleanupPageButton.setOnClickListener { showCleanupPreview(currentCleanupPreviewIndex + 1) }
        typesettingButton.setOnClickListener { requestTypesettingStart() }
        cancelTypesettingButton.setOnClickListener { cancelTypesetting() }
        previousTypesettingPageButton.setOnClickListener {
            showTypesettingPreview(currentTypesettingPreviewIndex - 1)
        }
        nextTypesettingPageButton.setOnClickListener {
            showTypesettingPreview(currentTypesettingPreviewIndex + 1)
        }
        qualityButton.setOnClickListener { requestQualityStart() }
        cancelQualityButton.setOnClickListener { cancelQuality() }
        exportButton.setOnClickListener { openExportFolder() }
        cancelExportButton.setOnClickListener { cancelExport() }
        syncWorkspaceState()
    }

    override fun onStart() {
        super.onStart()
        registerDetectionReceiver()
        registerOcrReceiver()
        registerTranslationReceiver()
        registerCleanupReceiver()
        registerTypesettingReceiver()
        registerQualityReceiver()
        registerExportReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshDurableState()
        onPipelineStateChanged()
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
        if (translationReceiverRegistered) {
            unregisterReceiver(translationReceiver)
            translationReceiverRegistered = false
        }
        if (cleanupReceiverRegistered) {
            unregisterReceiver(cleanupReceiver)
            cleanupReceiverRegistered = false
        }
        if (typesettingReceiverRegistered) {
            unregisterReceiver(typesettingReceiver)
            typesettingReceiverRegistered = false
        }
        if (qualityReceiverRegistered) {
            unregisterReceiver(qualityReceiver)
            qualityReceiverRegistered = false
        }
        if (exportReceiverRegistered) {
            unregisterReceiver(exportReceiver)
            exportReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterPredictiveBackCallback()
        contentPager.onSwipe = null
        clearDisplayedBitmap()
        clearDisplayedOcrBitmap()
        clearDisplayedCleanupBitmap()
        clearDisplayedTypesettingBitmap()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_PAGE, contentPager.displayedChild)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (returnToWorkspaceIfNeeded()) return
        super.onBackPressed()
    }

    private fun registerPredictiveBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback {
            if (!returnToWorkspaceIfNeeded()) finishAfterTransition()
        }
        backInvokedCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
    }

    private fun unregisterPredictiveBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        backInvokedCallback = null
    }

    private fun returnToWorkspaceIfNeeded(): Boolean {
        val destination = PageNavigation.backDestination(contentPager.displayedChild) ?: return false
        showPage(destination)
        return true
    }

    private fun showPage(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(PAGE_WORKSPACE, PAGE_DETAILS)
        val current = contentPager.displayedChild
        if (!animate) {
            contentPager.inAnimation = null
            contentPager.outAnimation = null
        }
        if (current != target) {
            repeat(contentPager.childCount) { index ->
                contentPager.getChildAt(index).clearAnimation()
            }
            if (animate) {
                val movingForward = target > current
                contentPager.inAnimation = AnimationUtils.loadAnimation(
                    this,
                    if (movingForward) R.anim.masumi_slide_in_right else R.anim.masumi_slide_in_left,
                )
                contentPager.outAnimation = AnimationUtils.loadAnimation(
                    this,
                    if (movingForward) R.anim.masumi_slide_out_left else R.anim.masumi_slide_out_right,
                )
            }
            contentPager.displayedChild = target
        }
        workspaceTabButton.isSelected = target == PAGE_WORKSPACE
        detailsTabButton.isSelected = target == PAGE_DETAILS
    }

    private fun showTranslationSettings() {
        showPage(PAGE_DETAILS)
        setTranslationSettingsExpanded(true)
        translationSettingsToggleButton.post {
            translationSettingsToggleButton.requestFocus()
        }
    }

    private fun startAutomaticPipeline() {
        if (currentProject == null) {
            openChapterFolder()
            return
        }
        if (translationSettingsStore.loadProviderSettings() == null) {
            automaticPipelineRequested = false
            pipelineStatus.setText(R.string.process_waiting_settings)
            showTranslationSettings()
            return
        }
        if (currentQualityRun != null && currentQualityRun?.report?.status?.allowsExport() != true) {
            automaticPipelineRequested = false
            pipelineStatus.setText(R.string.process_quality_blocked)
            showPage(PAGE_DETAILS)
            return
        }
        automaticPipelineRequested = true
        advanceAutomaticPipeline()
    }

    private fun advanceAutomaticPipeline() {
        if (!automaticPipelineRequested) return
        when (AutomaticPipelinePlanner.next(automaticPipelineSnapshot())) {
            AutomaticPipelineAction.START_DETECTION -> requestAnalysisStart()
            AutomaticPipelineAction.START_OCR -> requestOcrStart()
            AutomaticPipelineAction.START_TRANSLATION -> requestTranslationStart()
            AutomaticPipelineAction.START_CLEANUP -> requestCleanupStart()
            AutomaticPipelineAction.START_TYPESETTING -> requestTypesettingStart()
            AutomaticPipelineAction.START_QUALITY -> requestQualityStart()
            AutomaticPipelineAction.CONFIGURE_TRANSLATION -> {
                automaticPipelineRequested = false
                showTranslationSettings()
            }
            AutomaticPipelineAction.REVIEW_QUALITY -> {
                automaticPipelineRequested = false
                showPage(PAGE_DETAILS)
            }
            AutomaticPipelineAction.COMPLETE,
            AutomaticPipelineAction.WAIT_FOR_IMPORT,
            -> automaticPipelineRequested = false
            AutomaticPipelineAction.WAIT_FOR_ACTIVE_STAGE -> Unit
        }
        syncWorkspaceState()
    }

    private fun onPipelineStateChanged() {
        syncWorkspaceState()
        if (automaticPipelineRequested) {
            contentPager.post { advanceAutomaticPipeline() }
        }
    }

    private fun syncWorkspaceState() {
        val snapshot = automaticPipelineSnapshot()
        val completed = AutomaticPipelinePlanner.completedStages(snapshot)
        val total = AutomaticPipelinePlanner.STAGE_COUNT
        val hasProject = currentProject != null
        val hasSettings = translationSettingsStore.loadProviderSettings() != null
        val exportReady = currentQualityRun?.report?.status?.allowsExport() == true
        val active = hasActiveWork()
        val qualityBlocked = currentQualityRun != null && !exportReady

        pipelineProgress.max = total
        pipelineProgress.progress = completed
        pipelineProgressText.text = getString(R.string.process_progress, completed, total)
        pipelineStatus.text = when {
            !hasProject -> getString(R.string.process_waiting_import)
            !hasSettings -> getString(R.string.process_waiting_settings)
            exportReady -> getString(R.string.process_ready_export)
            qualityBlocked -> getString(R.string.process_quality_blocked)
            active || automaticPipelineRequested -> getString(R.string.process_background)
            completed == 0 -> getString(R.string.process_idle)
            else -> getString(R.string.process_paused, completed, total)
        }
        processButton.setText(
            when {
                exportReady -> R.string.process_done
                active || automaticPipelineRequested -> R.string.process_running
                completed > 0 -> R.string.process_continue
                else -> R.string.process_start
            },
        )
        processButton.isEnabled = hasProject && hasSettings && !exportReady && !active && !qualityBlocked
        translationSettingsShortcutButton.visibility = if (hasSettings) View.GONE else View.VISIBLE

        val project = currentProject
        if (
            project != null &&
            !importRunning &&
            statusText.text.toString() == getString(R.string.import_status_idle)
        ) {
            statusText.text = getString(R.string.import_status_existing, project.manifest.pages.size)
        }
    }

    private fun automaticPipelineSnapshot(): AutomaticPipelineSnapshot {
        val qualityReady = currentQualityRun?.report?.status?.allowsExport() == true
        return AutomaticPipelineSnapshot(
            hasProject = currentProject != null,
            hasTranslationSettings = translationSettingsStore.loadProviderSettings() != null,
            hasActiveWork = hasActiveWork(),
            detectionReady = currentRun != null,
            ocrReady = currentOcrRun != null,
            translationReady = currentTranslationRun != null,
            cleanupReady = currentCleanupRun != null,
            typesettingReady = currentTypesettingRun != null,
            qualityReady = qualityReady,
            qualityBlocked = currentQualityRun != null && !qualityReady,
        )
    }

    private fun hasActiveWork(): Boolean = importRunning || analysisActive || ocrActive ||
        translationActive || cleanupActive || typesettingActive || qualityActive || exportActive

    @Deprecated("Uses the platform result API to keep the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val treeUri = data?.data ?: return
        when (requestCode) {
            REQUEST_OPEN_CHAPTER -> {
                retainReadPermission(treeUri, data.flags)
                importChapter(treeUri)
            }
            REQUEST_EXPORT_FOLDER -> {
                retainReadWritePermission(treeUri, data.flags)
                startExport(treeUri)
            }
        }
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
        pendingTranslationProjectId?.let(::startTranslation)
        pendingTranslationProjectId = null
        pendingCleanupProjectId?.let(::startCleanup)
        pendingCleanupProjectId = null
        pendingTypesettingProjectId?.let(::startTypesetting)
        pendingTypesettingProjectId = null
        pendingQualityProjectId?.let(::startQuality)
        pendingQualityProjectId = null
    }

    private fun openChapterFolder() {
        if (
            importRunning || analysisActive || ocrActive || translationActive || cleanupActive ||
            typesettingActive || qualityActive || exportActive
        ) return
        automaticPipelineRequested = false

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

    private fun openExportFolder() {
        if (
            currentQualityRun?.report?.status?.allowsExport() != true || importRunning || analysisActive || ocrActive ||
            translationActive || cleanupActive || typesettingActive || qualityActive || exportActive
        ) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXPORT_FOLDER)
    }

    private fun retainReadWritePermission(treeUri: Uri, resultFlags: Int) {
        val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val granted = resultFlags and requested
        if (granted == 0) return
        runCatching { contentResolver.takePersistableUriPermission(treeUri, granted) }
    }

    private fun startExport(treeUri: Uri) {
        if (!ensureUnrestrictedBackgroundExecution()) return
        val projectId = currentProject?.manifest?.projectId ?: return
        exportResumeRequestedThisProcess = true
        startForegroundService(ExportForegroundService.startIntent(this, projectId, treeUri))
        setExportActive(true)
        exportStatus.setText(R.string.export_status_starting)
        exportProgress.visibility = View.VISIBLE
        exportProgress.isIndeterminate = false
        exportProgress.max = currentProject?.manifest?.pages?.size?.coerceAtLeast(1) ?: 1
        exportProgress.progress = 0
    }

    private fun cancelExport() {
        if (!exportActive) return
        startService(ExportForegroundService.cancelIntent(this))
        cancelExportButton.isEnabled = false
        exportStatus.setText(R.string.export_notification_cancelling)
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
                if (result.isSuccess && translationSettingsStore.loadProviderSettings() != null) {
                    automaticPipelineRequested = true
                }
                setImportRunning(false)
                refreshDurableState()
                onPipelineStateChanged()
            }
        }, IMPORT_THREAD_NAME).start()
    }

    private fun requestAnalysisStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (analysisActive || ocrActive || translationActive || cleanupActive || typesettingActive || exportActive) return
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
        if (!ensureUnrestrictedBackgroundExecution()) return
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
        if (
            currentRun == null || importRunning || analysisActive || ocrActive || translationActive ||
            cleanupActive || typesettingActive || exportActive
        ) return
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
        if (!ensureUnrestrictedBackgroundExecution()) return
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

    private fun saveTranslationSettings() {
        val result = runCatching {
            translationSettingsStore.save(
                SavedTranslationSettings(
                    apiUrl = translationApiUrl.text.toString(),
                    apiKey = translationApiKey.text.toString(),
                    model = translationModel.text.toString(),
                ),
            )
        }
        if (result.isSuccess) {
            Toast.makeText(this, R.string.translation_settings_saved, Toast.LENGTH_SHORT).show()
            setTranslationSettingsExpanded(false)
            refreshTranslationDurableState()
            if (currentProject != null && currentQualityRun?.report?.status?.allowsExport() != true) {
                automaticPipelineRequested = true
                onPipelineStateChanged()
            }
        } else {
            Toast.makeText(this, R.string.translation_settings_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestTranslationStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (
            currentOcrRun == null || importRunning || analysisActive || ocrActive || translationActive ||
            cleanupActive || typesettingActive || exportActive
        ) return
        if (translationSettingsStore.loadProviderSettings() == null) {
            translationStatus.setText(R.string.translation_status_settings_missing)
            setTranslationSettingsExpanded(true)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingTranslationProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startTranslation(projectId)
    }

    private fun startTranslation(projectId: String) {
        if (!ensureUnrestrictedBackgroundExecution()) return
        translationResumeRequestedThisProcess = true
        startForegroundService(TranslationForegroundService.startIntent(this, projectId))
        setTranslationActive(true)
        translationStatus.setText(R.string.translation_status_starting)
        translationProgress.visibility = View.VISIBLE
        translationProgress.isIndeterminate = false
        translationProgress.max = 1
        translationProgress.progress = 0
    }

    private fun cancelTranslation() {
        if (!translationActive) return
        startService(TranslationForegroundService.cancelIntent(this))
        cancelTranslationButton.isEnabled = false
        translationStatus.setText(R.string.translation_notification_cancelling)
    }

    private fun setTranslationSettingsExpanded(expanded: Boolean) {
        translationSettingsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        translationSettingsToggleButton.setText(
            if (expanded) R.string.translation_settings_hide else R.string.translation_settings_show,
        )
    }

    private fun requestCleanupStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (
            currentTranslationRun == null || importRunning || analysisActive || ocrActive ||
            translationActive || cleanupActive || typesettingActive || exportActive
        ) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingCleanupProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startCleanup(projectId)
    }

    private fun startCleanup(projectId: String) {
        if (!ensureUnrestrictedBackgroundExecution()) return
        cleanupResumeRequestedThisProcess = true
        startForegroundService(CleanupForegroundService.startIntent(this, projectId))
        setCleanupActive(true)
        cleanupStatus.setText(R.string.cleanup_status_starting)
        cleanupProgress.visibility = View.VISIBLE
        cleanupProgress.isIndeterminate = false
        cleanupProgress.max = currentProject?.manifest?.pages?.size?.coerceAtLeast(1) ?: 1
        cleanupProgress.progress = 0
    }

    private fun cancelCleanup() {
        if (!cleanupActive) return
        startService(CleanupForegroundService.cancelIntent(this))
        cancelCleanupButton.isEnabled = false
        cleanupStatus.setText(R.string.cleanup_notification_cancelling)
    }

    private fun requestTypesettingStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (
            currentCleanupRun == null || importRunning || analysisActive || ocrActive ||
            translationActive || cleanupActive || typesettingActive || exportActive
        ) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingTypesettingProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startTypesetting(projectId)
    }

    private fun startTypesetting(projectId: String) {
        if (!ensureUnrestrictedBackgroundExecution()) return
        typesettingResumeRequestedThisProcess = true
        startForegroundService(TypesettingForegroundService.startIntent(this, projectId))
        setTypesettingActive(true)
        typesettingStatus.setText(R.string.typesetting_status_starting)
        typesettingProgress.visibility = View.VISIBLE
        typesettingProgress.isIndeterminate = false
        typesettingProgress.max = currentProject?.manifest?.pages?.size?.coerceAtLeast(1) ?: 1
        typesettingProgress.progress = 0
    }

    private fun cancelTypesetting() {
        if (!typesettingActive) return
        startService(TypesettingForegroundService.cancelIntent(this))
        cancelTypesettingButton.isEnabled = false
        typesettingStatus.setText(R.string.typesetting_notification_cancelling)
    }

    private fun requestQualityStart() {
        val projectId = currentProject?.manifest?.projectId ?: return
        if (
            currentTypesettingRun == null || importRunning || analysisActive || ocrActive ||
            translationActive || cleanupActive || typesettingActive || qualityActive || exportActive
        ) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()
        ) {
            pendingQualityProjectId = projectId
            markNotificationPermissionRequested()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        startQuality(projectId)
    }

    private fun startQuality(projectId: String) {
        if (!ensureUnrestrictedBackgroundExecution()) return
        qualityResumeRequestedThisProcess = true
        startForegroundService(QualityForegroundService.startIntent(this, projectId))
        setQualityActive(true)
        qualityStatus.setText(R.string.quality_status_starting)
        qualityProgress.visibility = View.VISIBLE
        qualityProgress.isIndeterminate = false
        qualityProgress.max = currentProject?.manifest?.pages?.size?.coerceAtLeast(1) ?: 1
        qualityProgress.progress = 0
    }

    private fun cancelQuality() {
        if (!qualityActive) return
        startService(QualityForegroundService.cancelIntent(this))
        cancelQualityButton.isEnabled = false
        qualityStatus.setText(R.string.quality_notification_cancelling)
    }

    private fun ensureUnrestrictedBackgroundExecution(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return true
        val packageUri = Uri.parse("package:$packageName")
        val requested = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(packageUri),
            )
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.isSuccess
        Toast.makeText(
            this,
            if (requested) R.string.battery_optimization_required else R.string.battery_optimization_unavailable,
            Toast.LENGTH_LONG,
        ).show()
        return false
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
            analysisButton.isEnabled = !importRunning && !ocrActive && !translationActive && !cleanupActive && !typesettingActive
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
            ocrButton.isEnabled = !importRunning && !analysisActive && !translationActive && !cleanupActive && !typesettingActive
            ocrProgress.visibility = View.GONE
            ocrStatus.setText(R.string.ocr_status_ready)
        }
        showOcrPreview(currentOcrPreviewIndex)
        refreshTranslationDurableState()
    }

    private fun refreshTranslationDurableState(progressOverride: TranslationProgress? = null) {
        val project = currentProject
        val ocrRun = currentOcrRun
        if (project == null || ocrRun == null) {
            resetTranslationState()
            return
        }
        currentTranslationRun = catalog.latestPublishedTranslationRun(project.manifest.projectId)
            ?.takeIf { it.artifact.dependencies.ocrRunArtifactKey == ocrRun.artifact.runArtifactKey }
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    translationProgressMatchesOcr(project, progress, ocrRun.artifact.runArtifactKey)
            }
            ?: progressFromTranslationDurableState(project, ocrRun.artifact.runArtifactKey)
        if (durableProgress != null) {
            renderTranslationProgress(durableProgress)
            if (
                progressOverride == null &&
                !TranslationForegroundService.isTaskActive() &&
                translationSettingsStore.loadProviderSettings() != null &&
                TranslationResumePolicy.shouldResume(
                    durableProgress.status,
                    translationResumeRequestedThisProcess,
                )
            ) {
                translationResumeRequestedThisProcess = true
                startForegroundService(
                    TranslationForegroundService.startIntent(this, project.manifest.projectId),
                )
            }
        } else {
            setTranslationActive(false)
            translationButton.isEnabled = translationSettingsStore.loadProviderSettings() != null &&
                !importRunning && !analysisActive && !ocrActive && !cleanupActive && !typesettingActive
            translationProgress.visibility = View.GONE
            translationStatus.setText(
                if (translationSettingsStore.loadProviderSettings() == null) {
                    R.string.translation_status_settings_missing
                } else {
                    R.string.translation_status_ready
                },
            )
        }
        refreshCleanupDurableState()
    }

    private fun refreshCleanupDurableState(progressOverride: CleanupProgress? = null) {
        val project = currentProject
        val translationRun = currentTranslationRun
        if (project == null || translationRun == null) {
            resetCleanupState()
            return
        }
        val priorRunKey = currentCleanupRun?.artifact?.runArtifactKey
        currentCleanupRun = catalog.latestPublishedCleanupRun(
            project.manifest.projectId,
            translationRun.artifact.runArtifactKey,
            CleanupPolicy(),
        )
        if (currentCleanupRun?.artifact?.runArtifactKey != priorRunKey) currentCleanupPreviewIndex = 0
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    cleanupProgressMatchesTranslation(project, progress, translationRun.artifact.runArtifactKey)
            }
            ?: progressFromCleanupDurableState(project, translationRun.artifact.runArtifactKey)
        if (durableProgress != null) {
            renderCleanupProgress(durableProgress)
            if (
                progressOverride == null &&
                !CleanupForegroundService.isTaskActive() &&
                CleanupResumePolicy.shouldResume(
                    durableProgress.status,
                    cleanupResumeRequestedThisProcess,
                )
            ) {
                cleanupResumeRequestedThisProcess = true
                startForegroundService(
                    CleanupForegroundService.startIntent(this, project.manifest.projectId),
                )
            }
        } else {
            setCleanupActive(false)
            cleanupButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !translationActive && !typesettingActive
            cleanupProgress.visibility = View.GONE
            cleanupStatus.setText(R.string.cleanup_status_ready)
        }
        showCleanupPreview(currentCleanupPreviewIndex)
        refreshTypesettingDurableState()
    }

    private fun refreshTypesettingDurableState(progressOverride: TypesettingProgress? = null) {
        val project = currentProject
        val cleanupRun = currentCleanupRun
        if (project == null || cleanupRun == null) {
            resetTypesettingState()
            return
        }
        val priorRunKey = currentTypesettingRun?.artifact?.runArtifactKey
        currentTypesettingRun = catalog.latestPublishedTypesettingRun(
            project.manifest.projectId,
            cleanupRun.artifact.runArtifactKey,
        )
        if (currentTypesettingRun?.artifact?.runArtifactKey != priorRunKey) currentTypesettingPreviewIndex = 0
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    typesettingProgressMatchesCleanup(project, progress, cleanupRun.artifact.runArtifactKey)
            }
            ?: progressFromTypesettingDurableState(project, cleanupRun.artifact.runArtifactKey)
        if (durableProgress != null) {
            renderTypesettingProgress(durableProgress)
            if (
                progressOverride == null &&
                !TypesettingForegroundService.isTaskActive() &&
                TypesettingResumePolicy.shouldResume(
                    durableProgress.status,
                    typesettingResumeRequestedThisProcess,
                )
            ) {
                typesettingResumeRequestedThisProcess = true
                startForegroundService(
                    TypesettingForegroundService.startIntent(this, project.manifest.projectId),
                )
            }
        } else {
            setTypesettingActive(false)
            typesettingButton.isEnabled = !importRunning && !analysisActive && !ocrActive &&
                !translationActive && !cleanupActive
            typesettingProgress.visibility = View.GONE
            typesettingStatus.setText(R.string.typesetting_status_ready)
        }
        showTypesettingPreview(currentTypesettingPreviewIndex)
        refreshQualityDurableState()
    }

    private fun refreshQualityDurableState(progressOverride: QualityProgress? = null) {
        val project = currentProject
        val typesettingRun = currentTypesettingRun
        if (project == null || typesettingRun == null) {
            resetQualityState()
            return
        }
        currentQualityRun = catalog.latestPublishedQualityRun(
            project.manifest.projectId,
            typesettingRun.artifact.runArtifactKey,
            QualityPolicy(),
        )
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    qualityProgressMatchesTypesetting(project, progress, typesettingRun.artifact.runArtifactKey)
            }
            ?: progressFromQualityDurableState(project, typesettingRun.artifact.runArtifactKey)
        if (durableProgress != null) {
            renderQualityProgress(durableProgress)
            if (
                progressOverride == null &&
                !QualityForegroundService.isTaskActive() &&
                QualityResumePolicy.shouldResume(durableProgress.status, qualityResumeRequestedThisProcess)
            ) {
                qualityResumeRequestedThisProcess = true
                startForegroundService(QualityForegroundService.startIntent(this, project.manifest.projectId))
            }
        } else {
            setQualityActive(false)
            qualityButton.isEnabled = !importRunning && !analysisActive && !ocrActive &&
                !translationActive && !cleanupActive && !typesettingActive && !exportActive
            qualityProgress.visibility = View.GONE
            qualityStatus.setText(R.string.quality_status_ready)
        }
        refreshExportDurableState()
    }

    private fun qualityProgressMatchesTypesetting(
        project: ProjectRef,
        progress: QualityProgress,
        typesettingRunArtifactKey: String,
    ): Boolean {
        val job = QualityArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.typesettingRunArtifactKey == typesettingRunArtifactKey &&
            job.dependencies.policy == QualityPolicy()
    }

    private fun progressFromQualityDurableState(
        project: ProjectRef,
        typesettingRunArtifactKey: String,
    ): QualityProgress? {
        val job = QualityArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.typesettingRunArtifactKey == typesettingRunArtifactKey &&
            job.dependencies.policy == QualityPolicy() &&
            (
                job.status == QualityJobStatus.QUEUED ||
                    job.status == QualityJobStatus.RUNNING ||
                    currentQualityRun == null ||
                    job.updatedAtEpochMillis >= currentQualityRun!!.report.finishedAtEpochMillis
                )
        ) {
            return job.toQualityProgress()
        }
        return currentQualityRun?.report?.let { report ->
            QualityProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalPageCount = report.totalPageCount,
                totalPageCount = report.totalPageCount,
                warningPageCount = report.warningPageCount,
                blockedPageCount = report.blockedPageCount,
                warningCount = report.warningCount,
                blockingCount = report.blockingCount,
                errorCode = report.error?.code,
            )
        }
    }

    private fun rs.masumi.core.quality.QualityJobRecord.toQualityProgress(): QualityProgress = QualityProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        terminalPageCount = pages.count { it.state == QualityPageState.COMMITTED },
        totalPageCount = pages.size,
        warningPageCount = pages.count { it.verdict == QualityPageVerdict.PASS_WITH_WARNINGS },
        blockedPageCount = pages.count { it.verdict == QualityPageVerdict.BLOCKED },
        warningCount = pages.sumOf { it.warningCount },
        blockingCount = pages.sumOf { it.blockingCount },
        currentPageOrder = pages.firstOrNull { it.state == QualityPageState.RUNNING }?.pageOrder,
        errorCode = error?.code,
    )

    private fun refreshExportDurableState(progressOverride: ExportProgress? = null) {
        val project = currentProject
        val typesettingRun = currentTypesettingRun
        val qualityRun = currentQualityRun
        if (project == null || typesettingRun == null || qualityRun?.report?.status?.allowsExport() != true) {
            resetExportState()
            return
        }
        val job = ExportArtifactStore(project.directory).findLatestJob()?.takeIf {
            it.projectId == project.manifest.projectId &&
                it.dependencies.typesettingRunArtifactKey == typesettingRun.artifact.runArtifactKey &&
                it.dependencies.qualityRunArtifactKey == qualityRun.artifact.runArtifactKey
        }
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    job?.jobId == progress.jobId &&
                    job.exportKey == progress.exportKey
            }
            ?: job?.let { exportJob ->
                val committed = exportJob.pages.filter { it.state == ExportPageState.COMMITTED }
                ExportProgress(
                    projectId = exportJob.projectId,
                    jobId = exportJob.jobId,
                    exportKey = exportJob.exportKey,
                    status = exportJob.status,
                    terminalPageCount = committed.size,
                    totalPageCount = exportJob.pages.size,
                    flattenedPageCount = committed.count { it.source == ExportPageSource.FLATTENED },
                    cleanedFallbackPageCount = committed.count { it.source == ExportPageSource.CLEANED_FALLBACK },
                    sourceFallbackPageCount = committed.count { it.source == ExportPageSource.SOURCE_FALLBACK },
                    reusedPageCount = committed.count { it.reusedExisting },
                    currentPageOrder = exportJob.pages.firstOrNull { it.state == ExportPageState.RUNNING }?.pageOrder,
                    errorCode = exportJob.error?.code,
                )
            }
        if (durableProgress != null) {
            renderExportProgress(durableProgress)
            if (
                progressOverride == null &&
                !ExportForegroundService.isTaskActive() &&
                ExportResumePolicy.shouldResume(
                    durableProgress.status,
                    exportResumeRequestedThisProcess,
                )
            ) {
                exportResumeRequestedThisProcess = true
                startForegroundService(
                    ExportForegroundService.resumeIntent(this, project.manifest.projectId),
                )
            }
        } else {
            setExportActive(false)
            exportButton.isEnabled = !importRunning && !analysisActive && !ocrActive &&
                !translationActive && !cleanupActive && !typesettingActive && !qualityActive
            exportProgress.visibility = View.GONE
            exportStatus.setText(R.string.export_status_ready)
        }
    }

    private fun typesettingProgressMatchesCleanup(
        project: ProjectRef,
        progress: TypesettingProgress,
        cleanupRunArtifactKey: String,
    ): Boolean {
        val job = TypesettingArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.cleanupRunArtifactKey == cleanupRunArtifactKey &&
            job.dependencies.policy == TypesettingPolicy()
    }

    private fun progressFromTypesettingDurableState(
        project: ProjectRef,
        cleanupRunArtifactKey: String,
    ): TypesettingProgress? {
        val job = TypesettingArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.cleanupRunArtifactKey == cleanupRunArtifactKey &&
            job.dependencies.policy == TypesettingPolicy() &&
            (
                job.status == TypesettingJobStatus.QUEUED ||
                    job.status == TypesettingJobStatus.RUNNING ||
                    currentTypesettingRun == null ||
                    job.updatedAtEpochMillis >= currentTypesettingRun!!.report.finishedAtEpochMillis
                )
        ) {
            return TypesettingProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalPageCount = job.pages.count {
                    it.state == TypesettingPageState.COMMITTED ||
                        it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE
                },
                totalPageCount = job.pages.size,
                typesetRegionCount = job.pages.sumOf { it.typesetRegionCount },
                preservedRegionCount = job.pages.sumOf { it.preservedRegionCount },
                currentPageOrder = job.pages.firstOrNull { it.state == TypesettingPageState.RUNNING }?.pageOrder,
                errorCode = job.error?.code,
            )
        }
        return currentTypesettingRun?.report?.let { report ->
            TypesettingProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalPageCount = report.committedPageCount + report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                typesetRegionCount = report.typesetRegionCount,
                preservedRegionCount = report.preservedRegionCount,
                errorCode = report.error?.code,
            )
        }
    }

    private fun cleanupProgressMatchesTranslation(
        project: ProjectRef,
        progress: CleanupProgress,
        translationRunArtifactKey: String,
    ): Boolean {
        val job = CleanupArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.translationRunArtifactKey == translationRunArtifactKey &&
            job.dependencies.policy == CleanupPolicy()
    }

    private fun progressFromCleanupDurableState(
        project: ProjectRef,
        translationRunArtifactKey: String,
    ): CleanupProgress? {
        val job = CleanupArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.translationRunArtifactKey == translationRunArtifactKey &&
            job.dependencies.policy == CleanupPolicy() &&
            (
                job.status == CleanupJobStatus.QUEUED ||
                    job.status == CleanupJobStatus.RUNNING ||
                    currentCleanupRun == null ||
                    job.updatedAtEpochMillis >= currentCleanupRun!!.report.finishedAtEpochMillis
                )
        ) {
            return CleanupProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalPageCount = job.pages.count {
                    it.state == CleanupPageState.COMMITTED || it.state == CleanupPageState.PRESERVED_SOURCE
                },
                totalPageCount = job.pages.size,
                cleanedRegionCount = job.pages.sumOf { it.cleanedRegionCount },
                preservedRegionCount = job.pages.sumOf { it.preservedRegionCount },
                currentPageOrder = job.pages.firstOrNull { it.state == CleanupPageState.RUNNING }?.pageOrder,
                errorCode = job.error?.code,
            )
        }
        return currentCleanupRun?.report?.let { report ->
            CleanupProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalPageCount = report.committedPageCount + report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                cleanedRegionCount = report.cleanedRegionCount,
                preservedRegionCount = report.preservedRegionCount,
                errorCode = report.error?.code,
            )
        }
    }

    private fun translationProgressMatchesOcr(
        project: ProjectRef,
        progress: TranslationProgress,
        ocrRunArtifactKey: String,
    ): Boolean {
        val job = TranslationArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.ocrRunArtifactKey == ocrRunArtifactKey
    }

    private fun progressFromTranslationDurableState(
        project: ProjectRef,
        ocrRunArtifactKey: String,
    ): TranslationProgress? {
        val job = TranslationArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.ocrRunArtifactKey == ocrRunArtifactKey &&
            (
                job.status == TranslationJobStatus.QUEUED ||
                    job.status == TranslationJobStatus.RUNNING ||
                    currentTranslationRun == null ||
                    job.updatedAtEpochMillis >= currentTranslationRun!!.report.finishedAtEpochMillis
                )
        ) {
            return TranslationProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalWindowCount = job.windows.count { it.state.isTerminal() },
                totalWindowCount = job.windows.size,
                committedPageCount = job.pages.count { it.state == TranslationPageState.COMMITTED },
                totalPageCount = job.pages.size,
                translatedItemCount = job.windows.sumOf { it.translatedItemCount },
                preservedItemCount = job.windows.sumOf { it.preservedItemCount },
                protectedOcrCount = job.pages.sumOf { it.protectedOcrRegionCount },
                currentWindowIndex = job.windows.firstOrNull { it.state == TranslationWindowState.RUNNING }?.windowIndex,
                errorCode = job.error?.code,
            )
        }
        return currentTranslationRun?.report?.let { report ->
            TranslationProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalWindowCount = report.committedWindowCount,
                totalWindowCount = report.totalWindowCount,
                committedPageCount = report.committedPageCount,
                totalPageCount = report.totalPageCount,
                translatedItemCount = report.translatedItemCount,
                preservedItemCount = report.preservedItemCount,
                protectedOcrCount = report.protectedOcrRegionCount,
                errorCode = report.error?.code,
            )
        }
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
        analysisButton.isEnabled = !active && !importRunning && !ocrActive && !translationActive && !cleanupActive && !typesettingActive && currentProject != null
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
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun renderOcrProgress(progress: OcrProgress) {
        val active = progress.status.isActive()
        if (!active) ocrResumeRequestedThisProcess = false
        setOcrActive(active)
        ocrButton.isEnabled = !active && !importRunning && !analysisActive && !translationActive && !cleanupActive && !typesettingActive && currentRun != null
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
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun renderTranslationProgress(progress: TranslationProgress) {
        val active = progress.status.isActive()
        if (!active) translationResumeRequestedThisProcess = false
        setTranslationActive(active)
        translationButton.isEnabled = !active && currentOcrRun != null &&
            translationSettingsStore.loadProviderSettings() != null &&
            !importRunning && !analysisActive && !ocrActive && !cleanupActive && !typesettingActive
        translationProgress.visibility = View.VISIBLE
        translationProgress.isIndeterminate = false
        translationProgress.max = progress.totalWindowCount.coerceAtLeast(1)
        translationProgress.progress = progress.terminalWindowCount.coerceIn(0, translationProgress.max)
        val protectedCount = progress.preservedItemCount + progress.protectedOcrCount
        translationStatus.text = when (progress.status) {
            TranslationJobStatus.QUEUED -> getString(R.string.translation_status_starting)
            TranslationJobStatus.RUNNING -> getString(
                R.string.translation_status_progress,
                progress.terminalWindowCount,
                progress.totalWindowCount,
                progress.translatedItemCount,
                protectedCount,
            )
            TranslationJobStatus.SUCCEEDED -> getString(
                R.string.translation_status_succeeded,
                progress.translatedItemCount,
            )
            TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS -> getString(
                R.string.translation_status_succeeded_protected,
                progress.translatedItemCount,
                protectedCount,
            )
            TranslationJobStatus.CANCELLED -> getString(R.string.translation_status_cancelled)
            TranslationJobStatus.FAILED -> getString(
                R.string.translation_status_failed,
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun renderCleanupProgress(progress: CleanupProgress) {
        val active = progress.status.isActive()
        if (!active) cleanupResumeRequestedThisProcess = false
        setCleanupActive(active)
        cleanupButton.isEnabled = !active && currentTranslationRun != null &&
            !importRunning && !analysisActive && !ocrActive && !translationActive
        cleanupProgress.visibility = View.VISIBLE
        cleanupProgress.isIndeterminate = false
        cleanupProgress.max = progress.totalPageCount.coerceAtLeast(1)
        cleanupProgress.progress = progress.terminalPageCount.coerceIn(0, cleanupProgress.max)
        cleanupStatus.text = when (progress.status) {
            CleanupJobStatus.QUEUED -> getString(R.string.cleanup_status_starting)
            CleanupJobStatus.RUNNING -> getString(
                R.string.cleanup_status_progress,
                progress.terminalPageCount,
                progress.totalPageCount,
                progress.cleanedRegionCount,
                progress.preservedRegionCount,
            )
            CleanupJobStatus.SUCCEEDED -> getString(
                R.string.cleanup_status_succeeded,
                progress.cleanedRegionCount,
            )
            CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> getString(
                R.string.cleanup_status_succeeded_preserved,
                progress.cleanedRegionCount,
                progress.preservedRegionCount,
            )
            CleanupJobStatus.CANCELLED -> getString(R.string.cleanup_status_cancelled)
            CleanupJobStatus.FAILED -> getString(
                R.string.cleanup_status_failed,
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun showCleanupPreview(requestedIndex: Int) {
        val run = currentCleanupRun
        val project = currentProject
        if (run == null || project == null || run.artifact.entries.isEmpty()) {
            clearCleanupPreview()
            return
        }
        val entries = run.artifact.entries.sortedBy(CleanupRunEntry::pageOrder)
        currentCleanupPreviewIndex = requestedIndex.coerceIn(entries.indices)
        val entry = entries[currentCleanupPreviewIndex]
        val imagePath = when (entry.state) {
            CleanupPageState.COMMITTED -> entry.imagePath?.let { relative ->
                resolveRegularFileInside(run.directory, relative)
            }
            CleanupPageState.PRESERVED_SOURCE -> project.manifest.pages
                .firstOrNull { it.order == entry.pageOrder }
                ?.storedPath
                ?.let { relative -> resolveRegularFileInside(project.directory, relative) }
            CleanupPageState.PENDING,
            CleanupPageState.RUNNING,
            -> null
        }
        val pageArtifact = if (entry.state == CleanupPageState.COMMITTED) {
            catalog.readPublishedCleanupPage(run, entry.pageOrder)
        } else {
            null
        }
        val bitmap = imagePath?.let(::decodePreviewBitmap)
        clearDisplayedCleanupBitmap()
        if (bitmap != null) {
            displayedCleanupBitmap = bitmap
            cleanupPreviewImage.setImageBitmap(bitmap)
            cleanupPreviewImage.visibility = View.VISIBLE
        } else {
            cleanupPreviewImage.setImageDrawable(null)
            cleanupPreviewImage.visibility = View.GONE
        }
        val preservedCount = pageArtifact?.regions?.count {
            it.state == CleanupRegionState.PRESERVED_SOURCE
        } ?: 0
        cleanupPreservedPageMarker.visibility = when {
            entry.state == CleanupPageState.PRESERVED_SOURCE -> View.VISIBLE
            bitmap == null -> View.VISIBLE
            preservedCount > 0 -> View.VISIBLE
            else -> View.GONE
        }
        cleanupPreservedPageMarker.text = when {
            entry.state == CleanupPageState.PRESERVED_SOURCE -> getString(
                R.string.cleanup_preview_preserved_page,
                describePipelineError(entry.error?.code),
            )
            bitmap == null -> getString(R.string.preview_unavailable)
            preservedCount > 0 -> getString(R.string.cleanup_preview_protected_regions, preservedCount)
            else -> ""
        }
        val cleanedCount = pageArtifact?.regions?.count { it.state == CleanupRegionState.CLEANED } ?: 0
        cleanupDetailText.text = pageArtifact?.let {
            getString(
                R.string.cleanup_preview_detail,
                cleanedCount,
                preservedCount,
                it.regions.sumOf { region -> region.changedPixelCount },
            )
        }.orEmpty()
        cleanupPageIndicator.text = getString(
            R.string.cleanup_preview_page_indicator,
            currentCleanupPreviewIndex + 1,
            entries.size,
        )
        previousCleanupPageButton.isEnabled = currentCleanupPreviewIndex > 0
        nextCleanupPageButton.isEnabled = currentCleanupPreviewIndex < entries.lastIndex
    }

    private fun clearCleanupPreview() {
        clearDisplayedCleanupBitmap()
        cleanupPreviewImage.setImageDrawable(null)
        cleanupPreviewImage.visibility = View.GONE
        cleanupPreservedPageMarker.visibility = View.GONE
        cleanupPageIndicator.setText(R.string.cleanup_preview_empty)
        cleanupDetailText.text = ""
        previousCleanupPageButton.isEnabled = false
        nextCleanupPageButton.isEnabled = false
    }

    private fun renderTypesettingProgress(progress: TypesettingProgress) {
        val active = progress.status.isActive()
        if (!active) typesettingResumeRequestedThisProcess = false
        setTypesettingActive(active)
        typesettingButton.isEnabled = !active && currentCleanupRun != null &&
            !importRunning && !analysisActive && !ocrActive && !translationActive && !cleanupActive
        typesettingProgress.visibility = View.VISIBLE
        typesettingProgress.isIndeterminate = false
        typesettingProgress.max = progress.totalPageCount.coerceAtLeast(1)
        typesettingProgress.progress = progress.terminalPageCount.coerceIn(0, typesettingProgress.max)
        typesettingStatus.text = when (progress.status) {
            TypesettingJobStatus.QUEUED -> getString(R.string.typesetting_status_starting)
            TypesettingJobStatus.RUNNING -> getString(
                R.string.typesetting_status_progress,
                progress.terminalPageCount,
                progress.totalPageCount,
                progress.typesetRegionCount,
                progress.preservedRegionCount,
            )
            TypesettingJobStatus.SUCCEEDED -> getString(
                R.string.typesetting_status_succeeded,
                progress.typesetRegionCount,
            )
            TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> getString(
                R.string.typesetting_status_succeeded_preserved,
                progress.typesetRegionCount,
                progress.preservedRegionCount,
            )
            TypesettingJobStatus.CANCELLED -> getString(R.string.typesetting_status_cancelled)
            TypesettingJobStatus.FAILED -> getString(
                R.string.typesetting_status_failed,
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun renderQualityProgress(progress: QualityProgress) {
        val active = progress.status.isActive()
        if (!active) qualityResumeRequestedThisProcess = false
        setQualityActive(active)
        qualityButton.isEnabled = !active && currentTypesettingRun != null &&
            !importRunning && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !typesettingActive && !exportActive
        qualityProgress.visibility = View.VISIBLE
        qualityProgress.isIndeterminate = false
        qualityProgress.max = progress.totalPageCount.coerceAtLeast(1)
        qualityProgress.progress = progress.terminalPageCount.coerceIn(0, qualityProgress.max)
        qualityStatus.text = when (progress.status) {
            QualityJobStatus.QUEUED -> getString(R.string.quality_status_starting)
            QualityJobStatus.RUNNING -> if (progress.errorCode == "QUALITY_REPAIRING") {
                getString(
                    R.string.quality_status_repairing,
                    progress.terminalPageCount,
                    progress.totalPageCount,
                )
            } else {
                getString(
                    R.string.quality_status_progress,
                    progress.terminalPageCount,
                    progress.totalPageCount,
                    progress.warningCount,
                    progress.blockingCount,
                )
            }
            QualityJobStatus.SUCCEEDED -> getString(
                R.string.quality_status_succeeded,
                progress.totalPageCount,
            )
            QualityJobStatus.SUCCEEDED_WITH_WARNINGS -> getString(
                R.string.quality_status_succeeded_warnings,
                progress.warningCount,
            )
            QualityJobStatus.BLOCKED -> getString(
                R.string.quality_status_blocked,
                progress.blockedPageCount,
                progress.blockingCount,
            )
            QualityJobStatus.CANCELLED -> getString(R.string.quality_status_cancelled)
            QualityJobStatus.FAILED -> getString(
                R.string.quality_status_failed,
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun renderExportProgress(progress: ExportProgress) {
        val active = progress.status.isActive()
        if (!active) exportResumeRequestedThisProcess = false
        setExportActive(active)
        exportButton.isEnabled = !active && currentQualityRun?.report?.status?.allowsExport() == true &&
            !importRunning && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !typesettingActive && !qualityActive
        exportProgress.visibility = View.VISIBLE
        exportProgress.isIndeterminate = false
        exportProgress.max = progress.totalPageCount.coerceAtLeast(1)
        exportProgress.progress = progress.terminalPageCount.coerceIn(0, exportProgress.max)
        exportStatus.text = when (progress.status) {
            ExportJobStatus.QUEUED -> getString(R.string.export_status_starting)
            ExportJobStatus.RUNNING -> getString(
                R.string.export_status_progress,
                progress.terminalPageCount,
                progress.totalPageCount,
            )
            ExportJobStatus.SUCCEEDED -> getString(
                R.string.export_status_succeeded,
                progress.totalPageCount,
                progress.cleanedFallbackPageCount + progress.sourceFallbackPageCount,
                progress.reusedPageCount,
            )
            ExportJobStatus.CANCELLED -> getString(R.string.export_status_cancelled)
            ExportJobStatus.FAILED -> getString(
                R.string.export_status_failed,
                describePipelineError(progress.errorCode),
            )
        }
    }

    private fun showTypesettingPreview(requestedIndex: Int) {
        val run = currentTypesettingRun
        val project = currentProject
        if (run == null || project == null || run.artifact.entries.isEmpty()) {
            clearTypesettingPreview()
            return
        }
        val entries = run.artifact.entries.sortedBy(TypesettingRunEntry::pageOrder)
        currentTypesettingPreviewIndex = requestedIndex.coerceIn(entries.indices)
        val entry = entries[currentTypesettingPreviewIndex]
        val imagePath = when (entry.state) {
            TypesettingPageState.COMMITTED -> entry.imagePath?.let { relative ->
                resolveRegularFileInside(run.directory, relative)
            }
            TypesettingPageState.PRESERVED_CLEANED_PAGE -> currentCleanupRun
                ?.artifact
                ?.entries
                ?.singleOrNull { it.pageOrder == entry.pageOrder }
                ?.imagePath
                ?.let { relative -> currentCleanupRun?.directory?.let { resolveRegularFileInside(it, relative) } }
                ?: project.manifest.pages
                    .firstOrNull { it.order == entry.pageOrder }
                    ?.storedPath
                    ?.let { relative -> resolveRegularFileInside(project.directory, relative) }
            TypesettingPageState.PENDING,
            TypesettingPageState.RUNNING,
            -> null
        }
        val pageArtifact = if (entry.state == TypesettingPageState.COMMITTED) {
            catalog.readPublishedTypesettingPage(run, entry.pageOrder)
        } else {
            null
        }
        val bitmap = imagePath?.let(::decodePreviewBitmap)
        clearDisplayedTypesettingBitmap()
        if (bitmap != null) {
            displayedTypesettingBitmap = bitmap
            typesettingPreviewImage.setImageBitmap(bitmap)
            typesettingPreviewImage.visibility = View.VISIBLE
        } else {
            typesettingPreviewImage.setImageDrawable(null)
            typesettingPreviewImage.visibility = View.GONE
        }
        val preservedCount = pageArtifact?.regions?.count {
            it.state == TypesettingRegionState.PRESERVED_CLEANED_PAGE
        } ?: 0
        typesettingPreservedPageMarker.visibility = when {
            entry.state == TypesettingPageState.PRESERVED_CLEANED_PAGE -> View.VISIBLE
            bitmap == null -> View.VISIBLE
            preservedCount > 0 -> View.VISIBLE
            else -> View.GONE
        }
        typesettingPreservedPageMarker.text = when {
            entry.state == TypesettingPageState.PRESERVED_CLEANED_PAGE -> getString(
                R.string.typesetting_preview_preserved_page,
                describePipelineError(entry.error?.code),
            )
            bitmap == null -> getString(R.string.preview_unavailable)
            preservedCount > 0 -> getString(R.string.typesetting_preview_protected_regions, preservedCount)
            else -> ""
        }
        val typesetCount = pageArtifact?.regions?.count { it.state == TypesettingRegionState.TYPESET } ?: 0
        typesettingDetailText.text = pageArtifact?.let {
            getString(
                R.string.typesetting_preview_detail,
                typesetCount,
                preservedCount,
                it.regions.sumOf { region -> region.changedPixelCount },
            )
        }.orEmpty()
        typesettingPageIndicator.text = getString(
            R.string.typesetting_preview_page_indicator,
            currentTypesettingPreviewIndex + 1,
            entries.size,
        )
        previousTypesettingPageButton.isEnabled = currentTypesettingPreviewIndex > 0
        nextTypesettingPageButton.isEnabled = currentTypesettingPreviewIndex < entries.lastIndex
    }

    private fun clearTypesettingPreview() {
        clearDisplayedTypesettingBitmap()
        typesettingPreviewImage.setImageDrawable(null)
        typesettingPreviewImage.visibility = View.GONE
        typesettingPreservedPageMarker.visibility = View.GONE
        typesettingPageIndicator.setText(R.string.typesetting_preview_empty)
        typesettingDetailText.text = ""
        previousTypesettingPageButton.isEnabled = false
        nextTypesettingPageButton.isEnabled = false
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
            getString(
                R.string.preview_preserved_page,
                describePipelineError(entry.error?.code),
            )
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
                describePipelineError(entry.error?.code),
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
        resetTranslationState()
    }

    private fun resetTranslationState() {
        currentTranslationRun = null
        setTranslationActive(false)
        translationButton.isEnabled = false
        translationProgress.visibility = View.GONE
        translationStatus.setText(R.string.translation_status_no_ocr)
        resetCleanupState()
    }

    private fun resetCleanupState() {
        currentCleanupRun = null
        currentCleanupPreviewIndex = 0
        setCleanupActive(false)
        cleanupButton.isEnabled = false
        cleanupProgress.visibility = View.GONE
        cleanupStatus.setText(R.string.cleanup_status_no_translation)
        clearCleanupPreview()
        resetTypesettingState()
    }

    private fun resetTypesettingState() {
        currentTypesettingRun = null
        currentTypesettingPreviewIndex = 0
        setTypesettingActive(false)
        typesettingButton.isEnabled = false
        typesettingProgress.visibility = View.GONE
        typesettingStatus.setText(R.string.typesetting_status_no_cleanup)
        clearTypesettingPreview()
        resetQualityState()
    }

    private fun resetQualityState() {
        currentQualityRun = null
        setQualityActive(false)
        qualityButton.isEnabled = false
        qualityProgress.visibility = View.GONE
        qualityStatus.setText(R.string.quality_status_no_typesetting)
        resetExportState()
    }

    private fun resetExportState() {
        setExportActive(false)
        exportButton.isEnabled = false
        exportProgress.visibility = View.GONE
        exportStatus.setText(
            if (currentTypesettingRun == null) R.string.export_status_no_typesetting
            else R.string.export_status_no_quality,
        )
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

    private fun clearDisplayedCleanupBitmap() {
        displayedCleanupBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        displayedCleanupBitmap = null
    }

    private fun clearDisplayedTypesettingBitmap() {
        displayedTypesettingBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        displayedTypesettingBitmap = null
    }

    private fun setImportRunning(running: Boolean) {
        importRunning = running
        importButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive
        analysisButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive && currentProject != null
        ocrButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive && currentRun != null
        translationButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive &&
            currentOcrRun != null && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive &&
            currentTranslationRun != null
        typesettingButton.isEnabled = !running && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !typesettingActive && currentCleanupRun != null
        importProgress.visibility = if (running) View.VISIBLE else View.GONE
        syncWorkspaceState()
    }

    private fun setAnalysisActive(active: Boolean) {
        analysisActive = active
        importButton.isEnabled = !importRunning && !active && !ocrActive && !translationActive && !cleanupActive && !typesettingActive
        analysisButton.isEnabled = currentProject != null && !active && !ocrActive && !translationActive && !cleanupActive && !typesettingActive
        cancelAnalysisButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelAnalysisButton.isEnabled = active
        ocrButton.isEnabled = currentRun != null && !importRunning && !active && !ocrActive && !translationActive && !cleanupActive && !typesettingActive
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !active && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !active && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !active && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive
        syncWorkspaceState()
    }

    private fun setOcrActive(active: Boolean) {
        ocrActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !active && !translationActive && !cleanupActive && !typesettingActive
        analysisButton.isEnabled = currentProject != null && !analysisActive && !active && !translationActive && !cleanupActive && !typesettingActive
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !active && !translationActive && !cleanupActive && !typesettingActive
        cancelOcrButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelOcrButton.isEnabled = active
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !active &&
            !translationActive && !cleanupActive && !typesettingActive && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !active &&
            !translationActive && !cleanupActive && !typesettingActive
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive && !active &&
            !translationActive && !cleanupActive && !typesettingActive
        syncWorkspaceState()
    }

    private fun setTranslationActive(active: Boolean) {
        translationActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !active && !cleanupActive && !typesettingActive
        analysisButton.isEnabled = currentProject != null && !analysisActive && !ocrActive && !active && !cleanupActive && !typesettingActive
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !ocrActive && !active && !cleanupActive && !typesettingActive
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !ocrActive &&
            !active && !cleanupActive && !typesettingActive && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !ocrActive &&
            !active && !cleanupActive && !typesettingActive
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive && !ocrActive &&
            !active && !cleanupActive && !typesettingActive
        saveTranslationSettingsButton.isEnabled = !active && !cleanupActive && !typesettingActive
        cancelTranslationButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelTranslationButton.isEnabled = active
        syncWorkspaceState()
    }

    private fun setCleanupActive(active: Boolean) {
        cleanupActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !translationActive && !active && !typesettingActive
        analysisButton.isEnabled = currentProject != null && !analysisActive && !ocrActive && !translationActive && !active && !typesettingActive
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !ocrActive && !translationActive && !active && !typesettingActive
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !active && !typesettingActive && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !active && !typesettingActive
        saveTranslationSettingsButton.isEnabled = !translationActive && !active && !typesettingActive
        cancelCleanupButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelCleanupButton.isEnabled = active
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive &&
            !ocrActive && !translationActive && !active && !typesettingActive
        syncWorkspaceState()
    }

    private fun setTypesettingActive(active: Boolean) {
        typesettingActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !active
        analysisButton.isEnabled = currentProject != null && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !active
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !active
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !active && translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !active
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !active
        saveTranslationSettingsButton.isEnabled = !translationActive && !cleanupActive && !active
        cancelTypesettingButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelTypesettingButton.isEnabled = active
        qualityButton.isEnabled = currentTypesettingRun != null && !importRunning && !analysisActive &&
            !ocrActive && !translationActive && !cleanupActive && !active && !qualityActive && !exportActive
        exportButton.isEnabled = currentQualityRun?.report?.status?.allowsExport() == true &&
            !importRunning && !analysisActive && !ocrActive && !translationActive && !cleanupActive &&
            !active && !qualityActive && !exportActive
        syncWorkspaceState()
    }

    private fun setQualityActive(active: Boolean) {
        qualityActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !typesettingActive && !active && !exportActive
        analysisButton.isEnabled = currentProject != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive &&
            translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive
        qualityButton.isEnabled = currentTypesettingRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active && !exportActive
        exportButton.isEnabled = currentQualityRun?.report?.status?.allowsExport() == true && !importRunning &&
            !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive &&
            !active && !exportActive
        saveTranslationSettingsButton.isEnabled = !translationActive && !cleanupActive &&
            !typesettingActive && !active && !exportActive
        cancelQualityButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelQualityButton.isEnabled = active
        syncWorkspaceState()
    }

    private fun setExportActive(active: Boolean) {
        exportActive = active
        importButton.isEnabled = !importRunning && !analysisActive && !ocrActive && !translationActive &&
            !cleanupActive && !typesettingActive && !active
        analysisButton.isEnabled = currentProject != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active
        ocrButton.isEnabled = currentRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active
        translationButton.isEnabled = currentOcrRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active &&
            translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = currentTranslationRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active
        typesettingButton.isEnabled = currentCleanupRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !active
        qualityButton.isEnabled = currentTypesettingRun != null && !importRunning && !analysisActive && !ocrActive &&
            !translationActive && !cleanupActive && !typesettingActive && !qualityActive && !active
        exportButton.isEnabled = currentQualityRun?.report?.status?.allowsExport() == true && !importRunning &&
            !analysisActive && !ocrActive && !translationActive && !cleanupActive && !typesettingActive &&
            !qualityActive && !active
        saveTranslationSettingsButton.isEnabled = !translationActive && !cleanupActive && !typesettingActive && !active
        cancelExportButton.visibility = if (active) View.VISIBLE else View.GONE
        cancelExportButton.isEnabled = active
        syncWorkspaceState()
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerTranslationReceiver() {
        if (translationReceiverRegistered) return
        val filter = IntentFilter(TranslationStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                translationReceiver,
                filter,
                internalTranslationStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(translationReceiver, filter, internalTranslationStatusPermission(), null)
        }
        translationReceiverRegistered = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerCleanupReceiver() {
        if (cleanupReceiverRegistered) return
        val filter = IntentFilter(CleanupStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                cleanupReceiver,
                filter,
                internalCleanupStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(cleanupReceiver, filter, internalCleanupStatusPermission(), null)
        }
        cleanupReceiverRegistered = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerTypesettingReceiver() {
        if (typesettingReceiverRegistered) return
        val filter = IntentFilter(TypesettingStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                typesettingReceiver,
                filter,
                internalTypesettingStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(typesettingReceiver, filter, internalTypesettingStatusPermission(), null)
        }
        typesettingReceiverRegistered = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerQualityReceiver() {
        if (qualityReceiverRegistered) return
        val filter = IntentFilter(QualityStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                qualityReceiver,
                filter,
                internalQualityStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(qualityReceiver, filter, internalQualityStatusPermission(), null)
        }
        qualityReceiverRegistered = true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerExportReceiver() {
        if (exportReceiverRegistered) return
        val filter = IntentFilter(ExportStatusBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                exportReceiver,
                filter,
                internalExportStatusPermission(),
                null,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(exportReceiver, filter, internalExportStatusPermission(), null)
        }
        exportReceiverRegistered = true
    }

    private fun internalStatusPermission(): String =
        "$packageName.permission.INTERNAL_DETECTION_STATUS"

    private fun internalOcrStatusPermission(): String =
        "$packageName.permission.INTERNAL_OCR_STATUS"

    private fun internalTranslationStatusPermission(): String =
        "$packageName.permission.INTERNAL_TRANSLATION_STATUS"

    private fun internalCleanupStatusPermission(): String =
        "$packageName.permission.INTERNAL_CLEANUP_STATUS"

    private fun internalTypesettingStatusPermission(): String =
        "$packageName.permission.INTERNAL_TYPESETTING_STATUS"

    private fun internalQualityStatusPermission(): String =
        "$packageName.permission.INTERNAL_QUALITY_STATUS"

    private fun internalExportStatusPermission(): String =
        "$packageName.permission.INTERNAL_EXPORT_STATUS"

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

    private fun TranslationJobStatus.isActive(): Boolean =
        this == TranslationJobStatus.QUEUED || this == TranslationJobStatus.RUNNING

    private fun CleanupJobStatus.isActive(): Boolean =
        this == CleanupJobStatus.QUEUED || this == CleanupJobStatus.RUNNING

    private fun TypesettingJobStatus.isActive(): Boolean =
        this == TypesettingJobStatus.QUEUED || this == TypesettingJobStatus.RUNNING

    private fun QualityJobStatus.isActive(): Boolean =
        this == QualityJobStatus.QUEUED || this == QualityJobStatus.RUNNING

    private fun ExportJobStatus.isActive(): Boolean =
        this == ExportJobStatus.QUEUED || this == ExportJobStatus.RUNNING

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
        const val PAGE_WORKSPACE = PageNavigation.WORKSPACE
        const val PAGE_DETAILS = PageNavigation.DETAILS
        const val STATE_SELECTED_PAGE = "selected_page"
        const val REQUEST_OPEN_CHAPTER = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
        const val REQUEST_EXPORT_FOLDER = 1003
        const val IMPORT_THREAD_NAME = "masumi-import"
        const val PREF_NOTIFICATION_REQUESTED = "notification_permission_requested"
        const val PREF_AUTOMATIC_PIPELINE = "automatic_pipeline_requested"
    }
}
