package rs.masumi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import rs.masumi.app.pipeline.PipelineColdStartGuard
import rs.masumi.app.pipeline.DurablePipelineProgress
import rs.masumi.app.pipeline.PipelineQueueStore
import rs.masumi.app.pipeline.PipelineSchedulerService
import rs.masumi.app.pipeline.PipelineThreading
import rs.masumi.app.library.cachedMangaLibraryProjects
import rs.masumi.app.library.cachedMangaLibraryRootName
import rs.masumi.app.library.LibraryHomeSnapshotStore
import rs.masumi.app.library.MangaLibraryPreferences
import rs.masumi.app.library.MangaLibraryProject
import rs.masumi.app.library.MangaLibraryStore
import rs.masumi.app.library.MangaReaderActivity
import rs.masumi.app.library.mangaSourceFingerprint
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.ocr.OcrProgress
import rs.masumi.app.ocr.OcrResumePolicy
import rs.masumi.app.ocr.OcrStatusBroadcast
import rs.masumi.app.translation.TranslationForegroundService
import rs.masumi.app.translation.TranslationProgress
import rs.masumi.app.translation.TranslationResumePolicy
import rs.masumi.app.translation.TranslationSettingsPane
import rs.masumi.app.translation.TranslationSettingsStore
import rs.masumi.app.translation.TranslationStatusBroadcast
import rs.masumi.app.typesetting.TypesettingForegroundService
import rs.masumi.app.typesetting.TypesettingProgress
import rs.masumi.app.typesetting.TypesettingResumePolicy
import rs.masumi.app.typesetting.TypesettingStatusBroadcast
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.importer.ImportOutcome
import rs.masumi.core.importer.ProjectImportException
import rs.masumi.core.importer.ProjectImporter
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.isSuccessful
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPageState
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingRunEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var projectBackButton: Button
    private lateinit var projectTitle: TextView
    private lateinit var projectHeaderStatus: TextView
    private lateinit var importSection: View
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
    private lateinit var libraryLocationText: TextView
    private lateinit var chooseLibraryButton: Button
    private lateinit var libraryEmptyText: TextView
    private lateinit var libraryHistoryContainer: LinearLayout
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
    private lateinit var translationSettingsPane: TranslationSettingsPane
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
    private lateinit var exportButton: Button
    private lateinit var cancelExportButton: Button
    private lateinit var exportProgress: ProgressBar
    private lateinit var exportStatus: TextView
    private lateinit var readProjectButton: Button
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private lateinit var catalog: ProjectCatalog
    private lateinit var translationSettingsStore: TranslationSettingsStore
    private lateinit var libraryPreferences: MangaLibraryPreferences
    private lateinit var pipelineQueueStore: PipelineQueueStore

    private var importRunning = false
    private var analysisActive = false
    private var ocrActive = false
    private var translationActive = false
    private var cleanupActive = false
    private var typesettingActive = false
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
    private var currentTypesettingPreviewIndex = 0
    private lateinit var detectionPreviewPane: PreviewPane
    private lateinit var ocrPreviewPane: PreviewPane
    private lateinit var cleanupPreviewPane: PreviewPane
    private lateinit var typesettingPreviewPane: PreviewPane
    private val previewExecutor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory(PREVIEW_THREAD_NAME),
    )
    private val libraryExecutor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory(LIBRARY_THREAD_NAME),
    )
    private var pendingAnalysisProjectId: String? = null
    private var pendingOcrProjectId: String? = null
    private var pendingTranslationProjectId: String? = null
    private var pendingCleanupProjectId: String? = null
    private var pendingTypesettingProjectId: String? = null
    private var pendingChapterSelectionAfterLibrary = false
    private var pendingExportAfterLibrary = false
    private var libraryRefreshGeneration = 0
    private var requestedProjectId: String? = null
    private var autoSaveCheckInFlight = false
    private var exportSucceededForCurrentRun = false
    private var autoSaveStartedProjectId: String? = null

    private val detectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(DetectionStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderProgress(progress)
                } else {
                    refreshDurableState(progress)
                }
            }
            if (progress.status == DetectionJobStatus.CANCELLED || progress.status == DetectionJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(OcrStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderOcrProgress(progress)
                } else {
                    refreshOcrDurableState(progress)
                }
            }
            if (progress.status == OcrJobStatus.CANCELLED || progress.status == OcrJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }

    private val translationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(TranslationStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderTranslationProgress(progress)
                } else {
                    refreshTranslationDurableState(progress)
                }
            }
            if (progress.status == TranslationJobStatus.CANCELLED || progress.status == TranslationJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }

    private val cleanupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(CleanupStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderCleanupProgress(progress)
                } else {
                    refreshCleanupDurableState(progress)
                }
            }
            if (progress.status == CleanupJobStatus.CANCELLED || progress.status == CleanupJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }

    private val typesettingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(TypesettingStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderTypesettingProgress(progress)
                } else {
                    refreshTypesettingDurableState(progress)
                }
            }
            if (progress.status == TypesettingJobStatus.CANCELLED || progress.status == TypesettingJobStatus.FAILED) {
                automaticPipelineRequested = false
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }


    private val exportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(ExportStatusBroadcast::parse) ?: return
            if (isCurrentProject(progress.projectId)) {
                if (progress.status.isActive()) {
                    renderExportProgress(progress)
                } else {
                    refreshExportDurableState(progress)
                }
            }
            when (progress.status) {
                ExportJobStatus.SUCCEEDED -> refreshLibraryHistory()
                ExportJobStatus.CANCELLED,
                ExportJobStatus.FAILED,
                -> autoSaveStartedProjectId = null
                ExportJobStatus.QUEUED,
                ExportJobStatus.RUNNING,
                -> Unit
            }
            if (!progress.status.isActive()) onPipelineStateChanged()
        }
    }

    private fun isCurrentProject(projectId: String): Boolean =
        currentProject?.manifest?.projectId == projectId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectBackButton = findViewById(R.id.projectBackButton)
        projectTitle = findViewById(R.id.projectTitle)
        projectHeaderStatus = findViewById(R.id.projectHeaderStatus)
        importSection = findViewById(R.id.importSection)
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
        libraryLocationText = findViewById(R.id.libraryLocationText)
        chooseLibraryButton = findViewById(R.id.chooseLibraryButton)
        libraryEmptyText = findViewById(R.id.libraryEmptyText)
        libraryHistoryContainer = findViewById(R.id.libraryHistoryContainer)
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
        exportButton = findViewById(R.id.exportButton)
        cancelExportButton = findViewById(R.id.cancelExportButton)
        exportProgress = findViewById(R.id.exportProgress)
        exportStatus = findViewById(R.id.exportStatus)
        readProjectButton = findViewById(R.id.readProjectButton)
        detectionPreviewPane = PreviewPane(this, previewImage, previewExecutor)
        ocrPreviewPane = PreviewPane(this, ocrPreviewImage, previewExecutor)
        cleanupPreviewPane = PreviewPane(this, cleanupPreviewImage, previewExecutor)
        typesettingPreviewPane = PreviewPane(this, typesettingPreviewImage, previewExecutor)
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))
        translationSettingsStore = TranslationSettingsStore(this)
        translationSettingsPane = TranslationSettingsPane(
            activity = this,
            store = translationSettingsStore,
            onSettingsSaved = ::onTranslationSettingsSaved,
        )
        libraryPreferences = MangaLibraryPreferences(this)
        pipelineQueueStore = PipelineQueueStore(this)
        PipelineColdStartGuard.reconcile(pipelineQueueStore)
        requestedProjectId = intent.getStringExtra(EXTRA_PROJECT_ID)
            ?.takeIf(SAFE_PROJECT_ID::matches)
        val directImportTreeUri = intent.getStringExtra(EXTRA_IMPORT_TREE_URI)
            ?.let(Uri::parse)
            ?.takeIf(DocumentsContract::isTreeUri)
        intent.removeExtra(EXTRA_IMPORT_TREE_URI)
        automaticPipelineRequested = getPreferences(MODE_PRIVATE)
            .getBoolean(PREF_AUTOMATIC_PIPELINE, false)
        val initialPage = if (intent.getBooleanExtra(EXTRA_SHOW_DETAILS, false)) {
            PAGE_DETAILS
        } else {
            PageNavigation.normalize(savedInstanceState?.getInt(STATE_SELECTED_PAGE))
        }
        showPage(initialPage)
        contentPager.onSwipe = null
        registerPredictiveBackCallback()

        projectBackButton.setOnClickListener {
            if (!returnToWorkspaceIfNeeded()) finishAfterTransition()
        }
        workspaceTabButton.setOnClickListener { showPage(PAGE_WORKSPACE) }
        detailsTabButton.setOnClickListener { showPage(PAGE_DETAILS) }
        detailsShortcutButton.setOnClickListener { showPage(PAGE_DETAILS) }
        translationSettingsShortcutButton.setOnClickListener {
            showTranslationSettings()
        }
        chooseLibraryButton.setOnClickListener { openLibraryFolder(continueToChapter = false) }
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
        exportButton.setOnClickListener { saveCurrentProjectToLibrary() }
        cancelExportButton.setOnClickListener { cancelExport() }
        readProjectButton.setOnClickListener { openCurrentProjectReader() }
        syncWorkspaceState()
        directImportTreeUri?.let(::importChapter)
    }

    override fun onStart() {
        super.onStart()
        registerDetectionReceiver()
        registerOcrReceiver()
        registerTranslationReceiver()
        registerCleanupReceiver()
        registerTypesettingReceiver()
        registerExportReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshDurableState()
        if (
            intent.getBooleanExtra(EXTRA_AUTO_CONTINUE, false) &&
            currentProject != null &&
            !typesettingRunComplete() &&
            translationSettingsStore.loadProviderSettings() != null
        ) {
            enqueueAutomaticPipeline(requireNotNull(currentProject).manifest.projectId)
        }
        refreshLibraryHistory()
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
        if (exportReceiverRegistered) {
            unregisterReceiver(exportReceiver)
            exportReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterPredictiveBackCallback()
        contentPager.onSwipe = null
        translationSettingsPane.close()
        previewExecutor.shutdownNow()
        libraryExecutor.shutdownNow()
        detectionPreviewPane.clear()
        ocrPreviewPane.clear()
        cleanupPreviewPane.clear()
        typesettingPreviewPane.clear()
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

    private fun showPage(page: Int) {
        val target = page.coerceIn(PAGE_WORKSPACE, PAGE_DETAILS)
        if (contentPager.displayedChild != target) {
            contentPager.displayedChild = target
        }
        workspaceTabButton.isSelected = target == PAGE_WORKSPACE
        detailsTabButton.isSelected = target == PAGE_DETAILS
    }

    private fun showTranslationSettings() {
        showPage(PAGE_DETAILS)
        translationSettingsPane.showAndFocus()
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
        enqueueAutomaticPipeline(requireNotNull(currentProject).manifest.projectId)
    }

    private fun onPipelineStateChanged() {
        automaticPipelineRequested = currentProject?.manifest?.projectId
            ?.let(pipelineQueueStore::isActive)
            ?: false
        syncWorkspaceState()
        if (automaticPipelineRequested) {
            contentPager.post { startForegroundService(PipelineSchedulerService.wakeIntent(this)) }
        } else {
            contentPager.post { autoSaveCompletedProjectIfNeeded() }
        }
    }

    private fun enqueueAutomaticPipeline(projectId: String) {
        pipelineQueueStore.enqueue(projectId)
        automaticPipelineRequested = true
        startForegroundService(PipelineSchedulerService.wakeIntent(this))
        syncWorkspaceState()
    }

    private fun pauseAutomaticPipeline(projectId: String) {
        pipelineQueueStore.pause(projectId, "USER_PAUSED")
        automaticPipelineRequested = false
        startService(PipelineSchedulerService.pauseIntent(this, projectId))
    }

    private fun syncWorkspaceState() {
        val snapshot = automaticPipelineSnapshot()
        val completed = AutomaticPipelinePlanner.completedStages(snapshot)
        val total = AutomaticPipelinePlanner.STAGE_COUNT
        val hasProject = currentProject != null
        val hasSettings = translationSettingsStore.loadProviderSettings() != null
        val exportReady = typesettingRunComplete()
        val active = hasActiveWork()
        pipelineProgress.max = total
        pipelineProgress.progress = completed
        pipelineProgressText.text = getString(R.string.process_progress, completed, total)
        pipelineStatus.text = when {
            !hasProject -> getString(R.string.process_waiting_import)
            !hasSettings -> getString(R.string.process_waiting_settings)
            exportReady && exportSucceededForCurrentRun -> getString(R.string.process_saved)
            exportReady -> getString(R.string.process_ready_export)
            active || automaticPipelineRequested -> getString(R.string.process_background)
            completed == 0 -> getString(R.string.process_idle)
            else -> getString(R.string.process_paused, completed, total)
        }
        processButton.setText(
            when {
                exportReady && exportSucceededForCurrentRun -> R.string.process_done
                exportReady -> R.string.library_auto_saving
                active || automaticPipelineRequested -> R.string.process_running
                completed > 0 -> R.string.process_continue
                else -> R.string.process_start
            },
        )
        processButton.isEnabled = hasProject && hasSettings && !active && !exportReady
        translationSettingsShortcutButton.visibility = if (hasSettings) View.GONE else View.VISIBLE
        importSection.visibility = if (hasProject) View.GONE else View.VISIBLE
        projectHeaderStatus.text = when {
            exportReady && exportSucceededForCurrentRun -> getString(R.string.project_saved)
            exportReady -> getString(R.string.project_ready_to_save)
            active || automaticPipelineRequested -> getString(R.string.process_running)
            else -> getString(R.string.project_header_status)
        }

        val project = currentProject
        if (
            project != null &&
            !importRunning &&
            statusText.text.toString() == getString(R.string.import_status_idle)
        ) {
            statusText.text = getString(R.string.import_status_existing, project.manifest.pages.size)
        }
        projectTitle.text = project?.manifest?.pages?.firstOrNull()?.originalName
            ?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.project_default_title)
    }

    private fun openCurrentProjectReader() {
        val projectId = currentProject?.manifest?.projectId ?: return
        val rootUri = libraryPreferences.rootUri() ?: return
        libraryExecutor.execute {
            val project = runCatching {
                MangaLibraryStore(contentResolver, rootUri).project(projectId)
            }.getOrNull()
            runOnUiThread {
                if (project != null && project.outputPageCount > 0 && !isFinishing && !isDestroyed) {
                    startActivity(MangaReaderActivity.intent(this, rootUri, project))
                }
            }
        }
    }

    private fun autoSaveCompletedProjectIfNeeded() {
        val project = currentProject ?: return
        if (
            !typesettingRunComplete() ||
            hasActiveWork() ||
            autoSaveCheckInFlight ||
            autoSaveStartedProjectId == project.manifest.projectId
        ) {
            return
        }
        val rootUri = libraryPreferences.rootUri() ?: return
        autoSaveCheckInFlight = true
        libraryExecutor.execute {
            val outputPageCount = runCatching {
                MangaLibraryStore(contentResolver, rootUri)
                    .project(project.manifest.projectId)
                    ?.outputPageCount
                    ?: 0
            }
            runOnUiThread {
                autoSaveCheckInFlight = false
                if (
                    outputPageCount.getOrDefault(0) < project.manifest.pages.size &&
                    currentProject?.manifest?.projectId == project.manifest.projectId &&
                    typesettingRunComplete() &&
                    !hasActiveWork()
                ) {
                    saveCurrentProjectToLibrary()
                }
            }
        }
    }

    private fun refreshLibraryHistory() {
        val generation = ++libraryRefreshGeneration
        val rootUri = libraryPreferences.rootUri()
        if (rootUri == null) {
            renderLibraryHistory(null, null, emptyList())
            return
        }
        // Scanning the document tree costs one provider query per project, which
        // left the list blank for seconds on every resume. Paint the snapshot
        // this process already has, then reconcile in the background.
        cachedMangaLibraryProjects(rootUri)?.let { cached ->
            renderLibraryHistory(rootUri, cachedMangaLibraryRootName(rootUri), cached)
        }
        libraryExecutor.execute {
            val result = runCatching {
                val store = MangaLibraryStore(contentResolver, rootUri)
                val name = store.rootDisplayName()
                val projects = store.refreshProjects()
                LibraryHomeSnapshotStore(this).save(rootUri, name, projects)
                name to projects
            }
            runOnUiThread {
                if (generation != libraryRefreshGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { (name, projects) -> renderLibraryHistory(rootUri, name, projects) },
                    onFailure = { renderLibraryHistory(rootUri, null, emptyList(), unavailable = true) },
                )
            }
        }
    }

    private fun renderLibraryHistory(
        rootUri: Uri?,
        displayName: String?,
        projects: List<MangaLibraryProject>,
        unavailable: Boolean = false,
    ) {
        libraryHistoryContainer.removeAllViews()
        chooseLibraryButton.setText(
            if (rootUri == null) R.string.library_choose else R.string.library_change,
        )
        libraryLocationText.text = when {
            rootUri == null -> getString(R.string.library_not_configured)
            unavailable -> getString(R.string.library_unavailable)
            else -> getString(R.string.library_location, displayName ?: "Masumi", projects.size)
        }
        libraryEmptyText.visibility = if (projects.isEmpty() && !unavailable) View.VISIBLE else View.GONE
        projects.forEach { project ->
            val item = LayoutInflater.from(this).inflate(
                R.layout.item_library_project,
                libraryHistoryContainer,
                false,
            )
            item.findViewById<TextView>(R.id.libraryProjectTitle).text = project.metadata.title
            item.findViewById<TextView>(R.id.libraryProjectStatus).text = if (project.outputPageCount > 0) {
                getString(R.string.library_project_status_ready, project.outputPageCount)
            } else {
                getString(R.string.library_project_status_processing)
            }
            item.findViewById<Button>(R.id.libraryProjectReadButton).apply {
                isEnabled = project.outputPageCount > 0 && rootUri != null
                setText(if (isEnabled) R.string.library_read else R.string.library_waiting)
                setOnClickListener {
                    rootUri?.let { startActivity(MangaReaderActivity.intent(this@MainActivity, it, project)) }
                }
            }
            libraryHistoryContainer.addView(item)
        }
        val currentId = currentProject?.manifest?.projectId
        val currentLibraryProject = projects.firstOrNull { it.metadata.projectId == currentId }
        currentLibraryProject?.let { projectTitle.text = it.metadata.title }
        readProjectButton.visibility = if ((currentLibraryProject?.outputPageCount ?: 0) > 0 && rootUri != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        // "正在入库" used to stick forever whenever the export job record no longer
        // matched the current typesetting run key. Every page already sitting in
        // the library is proof enough that the chapter is saved.
        val expectedPageCount = currentProject?.manifest?.pages?.size ?: 0
        if (
            !exportSucceededForCurrentRun &&
            expectedPageCount > 0 &&
            (currentLibraryProject?.outputPageCount ?: 0) >= expectedPageCount
        ) {
            exportSucceededForCurrentRun = true
            syncWorkspaceState()
        }
    }

    private fun typesettingRunComplete(): Boolean = currentTypesettingRun != null

    private fun automaticPipelineSnapshot(): AutomaticPipelineSnapshot {
        return AutomaticPipelineSnapshot(
            hasProject = currentProject != null,
            hasTranslationSettings = translationSettingsStore.loadProviderSettings() != null,
            hasActiveWork = hasActiveWork(),
            detectionReady = currentRun != null,
            ocrReady = currentOcrRun != null,
            translationReady = currentTranslationRun != null,
            cleanupReady = currentCleanupRun != null,
            typesettingReady = currentTypesettingRun != null,
        )
    }

    private fun hasActiveWork(): Boolean = importRunning || analysisActive || ocrActive ||
        translationActive || cleanupActive || typesettingActive || exportActive

    @Deprecated("Uses the platform result API to keep the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_LIBRARY_FOLDER) {
                pendingChapterSelectionAfterLibrary = false
                pendingExportAfterLibrary = false
            }
            return
        }
        val treeUri = data?.data ?: return
        when (requestCode) {
            REQUEST_LIBRARY_FOLDER -> {
                retainReadWritePermission(treeUri, data.flags)
                libraryPreferences.saveRootUri(treeUri)
                refreshLibraryHistory()
                if (pendingChapterSelectionAfterLibrary) {
                    pendingChapterSelectionAfterLibrary = false
                    contentPager.post { openChapterFolder() }
                } else if (pendingExportAfterLibrary) {
                    pendingExportAfterLibrary = false
                    contentPager.post { saveCurrentProjectToLibrary() }
                }
            }
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
    }

    private fun openChapterFolder() {
        if (importRunning) return
        automaticPipelineRequested = false

        if (libraryPreferences.rootUri() == null) {
            openLibraryFolder(continueToChapter = true)
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_CHAPTER)
    }

    private fun openLibraryFolder(
        continueToChapter: Boolean,
        continueToExport: Boolean = false,
    ) {
        if (hasActiveWork() || importRunning) return
        require(!continueToChapter || !continueToExport)
        pendingChapterSelectionAfterLibrary = continueToChapter
        pendingExportAfterLibrary = continueToExport
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_LIBRARY_FOLDER)
    }

    private fun retainReadPermission(treeUri: Uri, resultFlags: Int) {
        if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun openExportFolder() {
        if (!typesettingRunComplete() || hasActiveWork()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXPORT_FOLDER)
    }

    private fun saveCurrentProjectToLibrary() {
        val project = currentProject ?: return
        if (!typesettingRunComplete() || hasActiveWork()) return
        val rootUri = libraryPreferences.rootUri()
        if (rootUri == null) {
            Toast.makeText(this, R.string.library_export_missing, Toast.LENGTH_LONG).show()
            openLibraryFolder(continueToChapter = false, continueToExport = true)
            return
        }
        autoSaveStartedProjectId = project.manifest.projectId
        exportButton.isEnabled = false
        exportStatus.setText(R.string.export_status_starting)
        libraryExecutor.execute {
            val destination = runCatching {
                val store = MangaLibraryStore(contentResolver, rootUri)
                if (store.project(project.manifest.projectId) == null) {
                    store.ensureProject(
                        projectId = project.manifest.projectId,
                        title = project.manifest.pages.firstOrNull()?.originalName
                            ?.substringBeforeLast('.')
                            .orEmpty()
                            .ifBlank { "漫画项目 ${project.manifest.projectId.take(8)}" },
                        createdAtEpochMillis = project.manifest.createdAtEpochMillis,
                        sourceTreeUri = rootUri,
                        sourceFingerprint = mangaSourceFingerprint(project.manifest.pages),
                    )
                }
                store.archiveSourcePages(
                    projectId = project.manifest.projectId,
                    privateProjectDirectory = project.directory,
                    pages = project.manifest.pages,
                )
                store.ensureOutputDirectory(project.manifest.projectId)
            }
            runOnUiThread {
                destination.fold(
                    onSuccess = { uri ->
                        if (currentProject?.manifest?.projectId == project.manifest.projectId) startExport(uri)
                    },
                    onFailure = {
                        autoSaveStartedProjectId = null
                        exportButton.isEnabled = true
                        exportStatus.setText(R.string.library_unavailable)
                    },
                )
            }
        }
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
        currentProject?.manifest?.projectId?.let(::pauseAutomaticPipeline)
        startService(ExportForegroundService.cancelIntent(this))
        cancelExportButton.isEnabled = false
        exportStatus.setText(R.string.export_notification_cancelling)
    }

    private fun importChapter(treeUri: Uri) {
        setImportRunning(true)
        statusText.setText(R.string.import_status_running)

        PipelineThreading.thread(IMPORT_THREAD_NAME, Runnable {
            val result = runCatching {
                val libraryRoot = requireNotNull(libraryPreferences.rootUri()) { "library root was not selected" }
                val library = MangaLibraryStore(contentResolver, libraryRoot)
                val title = library.documentDisplayName(treeUri).orEmpty().ifBlank { "未命名漫画" }
                val sources = DocumentTreeReader(contentResolver).read(treeUri)
                val outcome = ProjectImporter(filesDir.toPath().resolve("workspace")).importProject(sources)
                val storedInLibrary = runCatching {
                    library.ensureProject(
                        projectId = outcome.manifest.projectId,
                        title = title,
                        createdAtEpochMillis = outcome.manifest.createdAtEpochMillis,
                        sourceTreeUri = treeUri,
                        sourceFingerprint = mangaSourceFingerprint(outcome.manifest.pages),
                    )
                    library.archiveSourcePages(
                        projectId = outcome.manifest.projectId,
                        privateProjectDirectory = outcome.projectDirectory,
                        pages = outcome.manifest.pages,
                    )
                }.isSuccess
                ChapterImportResult(outcome, storedInLibrary)
            }

            runOnUiThread {
                result.fold(
                    onSuccess = { imported ->
                        val outcome = imported.outcome
                        requestedProjectId = outcome.manifest.projectId
                        statusText.text = if (imported.storedInLibrary) {
                            getString(
                                R.string.import_status_success,
                                outcome.report.importedCount,
                                outcome.report.byteCount,
                                "projects/${outcome.manifest.projectId}/reports/${outcome.report.jobId}.json",
                            )
                        } else {
                            getString(R.string.library_project_create_failed)
                        }
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
                    enqueueAutomaticPipeline(requireNotNull(result.getOrNull()).outcome.manifest.projectId)
                }
                setImportRunning(false)
                refreshDurableState()
                refreshLibraryHistory()
                onPipelineStateChanged()
            }
        }).start()
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
        currentProject?.manifest?.projectId?.let(::pauseAutomaticPipeline)
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
        currentProject?.manifest?.projectId?.let(::pauseAutomaticPipeline)
        startService(OcrForegroundService.cancelIntent(this))
        cancelOcrButton.isEnabled = false
        ocrStatus.setText(R.string.ocr_notification_cancelling)
    }

    private fun onTranslationSettingsSaved() {
        refreshTranslationDurableState()
        if (currentProject != null && !typesettingRunComplete()) {
            enqueueAutomaticPipeline(requireNotNull(currentProject).manifest.projectId)
            onPipelineStateChanged()
        } else {
            startForegroundService(PipelineSchedulerService.wakeIntent(this))
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
            translationSettingsPane.showAndFocus()
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
        val projectId = currentProject?.manifest?.projectId
        projectId?.let(::pauseAutomaticPipeline)
        startService(TranslationForegroundService.cancelIntent(this, projectId))
        cancelTranslationButton.isEnabled = false
        translationStatus.setText(R.string.translation_notification_cancelling)
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
        currentProject?.manifest?.projectId?.let(::pauseAutomaticPipeline)
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
        currentProject?.manifest?.projectId?.let(::pauseAutomaticPipeline)
        startService(TypesettingForegroundService.cancelIntent(this))
        cancelTypesettingButton.isEnabled = false
        typesettingStatus.setText(R.string.typesetting_notification_cancelling)
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
        // Direct imports arrive from the library picker. Keep the empty
        // workspace visible while copying instead of briefly selecting and
        // rendering the previously opened manga.
        if (importRunning && requestedProjectId == null && currentProject == null) return
        val priorProjectId = currentProject?.manifest?.projectId
        currentProject = requestedProjectId
            ?.let { projectId -> runCatching { catalog.openProject(projectId) }.getOrNull() }
            ?: catalog.latestProject()
        val project = currentProject
        if (project == null) {
            currentRun = null
            currentPreviewIndex = 0
            setAnalysisActive(false)
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
            ?: DurablePipelineProgress.detection(project, currentRun)
        if (durableProgress != null) {
            renderProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !DetectionForegroundService.isTaskActive() &&
                    DetectionResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setAnalysisActive(false)
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
                    DurablePipelineProgress.ocrMatchesDetection(
                        project,
                        progress,
                        detectionRun.artifact.runArtifactKey,
                    )
            }
            ?: DurablePipelineProgress.ocr(
                project,
                detectionRun.artifact.runArtifactKey,
                currentOcrRun,
            )
        if (durableProgress != null) {
            renderOcrProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !OcrForegroundService.isTaskActive() &&
                    OcrResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setOcrActive(false)
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
            ?.takeIf {
                it.artifact.dependencies.ocrRunArtifactKey == ocrRun.artifact.runArtifactKey &&
                    it.artifact.dependencies.policy == TranslationPolicy() &&
                    it.artifact.dependencies.prompt == TranslationPromptRef() &&
                    it.artifact.dependencies.batching == TranslationBatchingConfig()
            }
        val durableProgress = progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    DurablePipelineProgress.translationMatchesOcr(
                        project,
                        progress,
                        ocrRun.artifact.runArtifactKey,
                    )
            }
            ?: DurablePipelineProgress.translation(
                project,
                ocrRun.artifact.runArtifactKey,
                currentTranslationRun,
            )
        if (durableProgress != null) {
            renderTranslationProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !TranslationForegroundService.isTaskActive(project.manifest.projectId) &&
                    TranslationResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setTranslationActive(false)
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
                    DurablePipelineProgress.cleanupMatchesTranslation(
                        project,
                        progress,
                        translationRun.artifact.runArtifactKey,
                    )
            }
            ?: DurablePipelineProgress.cleanup(
                project,
                translationRun.artifact.runArtifactKey,
                currentCleanupRun,
            )
        if (durableProgress != null) {
            renderCleanupProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !CleanupForegroundService.isTaskActive() &&
                    CleanupResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setCleanupActive(false)
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
                    DurablePipelineProgress.typesettingMatchesCleanup(
                        project,
                        progress,
                        cleanupRun.artifact.runArtifactKey,
                    )
            }
            ?: DurablePipelineProgress.typesetting(
                project,
                cleanupRun.artifact.runArtifactKey,
                currentTypesettingRun,
            )
        if (durableProgress != null) {
            renderTypesettingProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !TypesettingForegroundService.isTaskActive() &&
                    TypesettingResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setTypesettingActive(false)
            typesettingProgress.visibility = View.GONE
            typesettingStatus.setText(R.string.typesetting_status_ready)
        }
        showTypesettingPreview(currentTypesettingPreviewIndex)
        refreshExportDurableState()
    }


    private fun refreshExportDurableState(progressOverride: ExportProgress? = null) {
        val project = currentProject
        val typesettingRun = currentTypesettingRun
        if (project == null || typesettingRun == null) {
            resetExportState()
            return
        }
        val durableProgress = DurablePipelineProgress.export(
            project,
            typesettingRun.artifact.runArtifactKey,
            progressOverride,
        )
        exportSucceededForCurrentRun = durableProgress?.status == ExportJobStatus.SUCCEEDED
        if (durableProgress != null) {
            renderExportProgress(
                durableProgress,
                interrupted = progressOverride == null &&
                    !ExportForegroundService.isTaskActive() &&
                    ExportResumePolicy.shouldResume(durableProgress.status, false),
            )
        } else {
            setExportActive(false)
            exportProgress.visibility = View.GONE
            exportStatus.setText(R.string.export_status_ready)
        }
    }

    private fun renderProgress(progress: DetectionProgress, interrupted: Boolean = false) {
        val completed = progress.committedPageCount + progress.preservedPageCount
        val active = !interrupted && progress.status.isActive()
        if (analysisActive != active) setAnalysisActive(active)
        detectionProgress.visibility = View.VISIBLE
        detectionProgress.isIndeterminate = false
        detectionProgress.max = progress.totalPageCount.coerceAtLeast(1)
        detectionProgress.progress = completed.coerceIn(0, detectionProgress.max)
        detectionStatus.text = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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

    private fun renderOcrProgress(progress: OcrProgress, interrupted: Boolean = false) {
        val active = !interrupted && progress.status.isActive()
        if (ocrActive != active) setOcrActive(active)
        ocrProgress.visibility = View.VISIBLE
        ocrProgress.isIndeterminate = !interrupted && progress.status == OcrJobStatus.LOADING_MODEL
        ocrProgress.max = progress.totalRegionCount.coerceAtLeast(1)
        ocrProgress.progress = progress.terminalRegionCount.coerceIn(0, ocrProgress.max)
        ocrStatus.text = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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

    private fun renderTranslationProgress(progress: TranslationProgress, interrupted: Boolean = false) {
        val active = !interrupted && progress.status.isActive()
        if (translationActive != active) setTranslationActive(active)
        translationProgress.visibility = View.VISIBLE
        translationProgress.isIndeterminate = false
        translationProgress.max = progress.totalWindowCount.coerceAtLeast(1)
        translationProgress.progress = progress.terminalWindowCount.coerceIn(0, translationProgress.max)
        val protectedCount = progress.preservedItemCount + progress.protectedOcrCount
        val statusText = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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
        translationStatus.text = if (progress.status.isSuccessful()) {
            currentTranslationRun?.report?.provider
                ?.takeIf { it.displayName.isNotBlank() }
                ?.let { provider ->
                    getString(
                        R.string.translation_status_provider_record,
                        statusText,
                        provider.displayName,
                        currentTranslationRun?.artifact?.dependencies?.provider?.modelId.orEmpty(),
                    )
                }
                ?: statusText
        } else {
            statusText
        }
    }

    private fun renderCleanupProgress(progress: CleanupProgress, interrupted: Boolean = false) {
        val active = !interrupted && progress.status.isActive()
        if (cleanupActive != active) setCleanupActive(active)
        cleanupProgress.visibility = View.VISIBLE
        cleanupProgress.isIndeterminate = false
        cleanupProgress.max = progress.totalPageCount.coerceAtLeast(1)
        cleanupProgress.progress = progress.terminalPageCount.coerceIn(0, cleanupProgress.max)
        cleanupStatus.text = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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
        val preservedCount = pageArtifact?.regions?.count {
            it.state == CleanupRegionState.PRESERVED_SOURCE
        } ?: 0
        cleanupPreviewPane.render(imagePath) { bitmap ->
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
        cleanupPreviewPane.clear()
        cleanupPreservedPageMarker.visibility = View.GONE
        cleanupPageIndicator.setText(R.string.cleanup_preview_empty)
        cleanupDetailText.text = ""
        previousCleanupPageButton.isEnabled = false
        nextCleanupPageButton.isEnabled = false
    }

    private fun renderTypesettingProgress(progress: TypesettingProgress, interrupted: Boolean = false) {
        val active = !interrupted && progress.status.isActive()
        if (typesettingActive != active) setTypesettingActive(active)
        typesettingProgress.visibility = View.VISIBLE
        typesettingProgress.isIndeterminate = false
        typesettingProgress.max = progress.totalPageCount.coerceAtLeast(1)
        typesettingProgress.progress = progress.terminalPageCount.coerceIn(0, typesettingProgress.max)
        typesettingStatus.text = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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


    private fun renderExportProgress(progress: ExportProgress, interrupted: Boolean = false) {
        val active = !interrupted && progress.status.isActive()
        if (exportActive != active) setExportActive(active)
        exportProgress.visibility = View.VISIBLE
        exportProgress.isIndeterminate = false
        exportProgress.max = progress.totalPageCount.coerceAtLeast(1)
        exportProgress.progress = progress.terminalPageCount.coerceIn(0, exportProgress.max)
        exportStatus.text = if (interrupted) {
            getString(R.string.stage_status_interrupted)
        } else when (progress.status) {
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
        val preservedCount = pageArtifact?.regions?.count {
            it.state == TypesettingRegionState.PRESERVED_CLEANED_PAGE
        } ?: 0
        typesettingPreviewPane.render(imagePath) { bitmap ->
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
        typesettingPreviewPane.clear()
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

        detectionPreviewPane.render(imagePath) { bitmap ->
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
        detectionPreviewPane.clear()
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
        val protectedCount = pageArtifact?.regions?.count { region ->
            region.state == OcrRegionState.NEEDS_FALLBACK ||
                region.state == OcrRegionState.PRESERVED_SOURCE
        } ?: 0
        ocrPreviewPane.render(imagePath) { bitmap ->
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
        ocrPreviewPane.clear()
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
        ocrProgress.visibility = View.GONE
        ocrStatus.setText(R.string.ocr_status_no_detection)
        clearOcrPreview()
        resetTranslationState()
    }

    private fun resetTranslationState() {
        currentTranslationRun = null
        setTranslationActive(false)
        translationProgress.visibility = View.GONE
        translationStatus.setText(R.string.translation_status_no_ocr)
        resetCleanupState()
    }

    private fun resetCleanupState() {
        currentCleanupRun = null
        currentCleanupPreviewIndex = 0
        setCleanupActive(false)
        cleanupProgress.visibility = View.GONE
        cleanupStatus.setText(R.string.cleanup_status_no_translation)
        clearCleanupPreview()
        resetTypesettingState()
    }

    private fun resetTypesettingState() {
        currentTypesettingRun = null
        currentTypesettingPreviewIndex = 0
        setTypesettingActive(false)
        typesettingProgress.visibility = View.GONE
        typesettingStatus.setText(R.string.typesetting_status_no_cleanup)
        clearTypesettingPreview()
        resetExportState()
    }


    private fun resetExportState() {
        exportSucceededForCurrentRun = false
        setExportActive(false)
        exportProgress.visibility = View.GONE
        exportStatus.setText(R.string.export_status_no_typesetting)
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

    /**
     * Single source of truth for every stage button, cancel control, and the
     * settings save button. Stage buttons follow one rule: enabled only when
     * nothing is running and the stage's input artifact exists.
     */
    private fun updateStageControls() {
        val busy = hasActiveWork()
        importButton.isEnabled = !busy
        analysisButton.isEnabled = !busy && currentProject != null
        ocrButton.isEnabled = !busy && currentRun != null
        translationButton.isEnabled = !busy && currentOcrRun != null &&
            translationSettingsStore.loadProviderSettings() != null
        cleanupButton.isEnabled = !busy && currentTranslationRun != null
        typesettingButton.isEnabled = !busy && currentCleanupRun != null
        exportButton.isEnabled = !busy && currentTypesettingRun != null
        translationSettingsPane.setSaveEnabled(
            !translationActive && !cleanupActive && !typesettingActive && !exportActive,
        )
        bindCancelControl(cancelAnalysisButton, analysisActive)
        bindCancelControl(cancelOcrButton, ocrActive)
        bindCancelControl(cancelTranslationButton, translationActive)
        bindCancelControl(cancelCleanupButton, cleanupActive)
        bindCancelControl(cancelTypesettingButton, typesettingActive)
        bindCancelControl(cancelExportButton, exportActive)
    }

    private fun bindCancelControl(button: Button, active: Boolean) {
        button.visibility = if (active) View.VISIBLE else View.GONE
        button.isEnabled = active
    }

    private fun setImportRunning(running: Boolean) {
        importRunning = running
        importProgress.visibility = if (running) View.VISIBLE else View.GONE
        updateStageControls()
        syncWorkspaceState()
    }

    private fun setAnalysisActive(active: Boolean) {
        analysisActive = active
        updateStageControls()
        syncWorkspaceState()
    }

    private fun setOcrActive(active: Boolean) {
        ocrActive = active
        updateStageControls()
        syncWorkspaceState()
    }

    private fun setTranslationActive(active: Boolean) {
        translationActive = active
        updateStageControls()
        syncWorkspaceState()
    }

    private fun setCleanupActive(active: Boolean) {
        cleanupActive = active
        updateStageControls()
        syncWorkspaceState()
    }

    private fun setTypesettingActive(active: Boolean) {
        typesettingActive = active
        updateStageControls()
        syncWorkspaceState()
    }


    private fun setExportActive(active: Boolean) {
        exportActive = active
        updateStageControls()
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


    private fun ExportJobStatus.isActive(): Boolean =
        this == ExportJobStatus.QUEUED || this == ExportJobStatus.RUNNING

    companion object {
        const val PAGE_WORKSPACE = PageNavigation.WORKSPACE
        const val PAGE_DETAILS = PageNavigation.DETAILS
        const val STATE_SELECTED_PAGE = "selected_page"
        const val REQUEST_OPEN_CHAPTER = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
        const val REQUEST_EXPORT_FOLDER = 1003
        const val REQUEST_LIBRARY_FOLDER = 1004
        const val IMPORT_THREAD_NAME = "masumi-import"
        const val LIBRARY_THREAD_NAME = "masumi-library"
        const val PREVIEW_THREAD_NAME = "masumi-preview-decode"
        const val PREF_NOTIFICATION_REQUESTED = "notification_permission_requested"
        const val PREF_AUTOMATIC_PIPELINE = "automatic_pipeline_requested"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_IMPORT_TREE_URI = "import_tree_uri"
        private const val EXTRA_SHOW_DETAILS = "show_details"
        private const val EXTRA_AUTO_CONTINUE = "auto_continue"
        private val SAFE_PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun projectIntent(context: Context, projectId: String, showDetails: Boolean = false): Intent {
            require(SAFE_PROJECT_ID.matches(projectId))
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_SHOW_DETAILS, showDetails)
                .putExtra(EXTRA_AUTO_CONTINUE, !showDetails)
        }

        fun importIntent(context: Context, treeUri: Uri): Intent {
            require(treeUri.scheme == "content" && DocumentsContract.isTreeUri(treeUri))
            return Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_IMPORT_TREE_URI, treeUri.toString())
        }
    }

    private data class ChapterImportResult(
        val outcome: ImportOutcome,
        val storedInLibrary: Boolean,
    )
}
