package rs.masumi.app.library

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import rs.masumi.app.AutomaticPipelinePlanner
import rs.masumi.app.HorizontalSwipeViewFlipper
import rs.masumi.app.MainActivity
import rs.masumi.app.ProjectLaunchMode
import rs.masumi.app.R
import rs.masumi.app.describePipelineErrorBrief
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.DetectionForegroundService
import rs.masumi.app.cleanup.CleanupForegroundService
import rs.masumi.app.exporting.ExportForegroundService
import rs.masumi.app.importing.ImportedMangaPipelineRegistrar
import rs.masumi.app.importing.SelectedMangaDocumentReader
import rs.masumi.app.importing.SelectedMangaImportResult
import rs.masumi.app.importing.SelectedMangaImportProcessExecutor
import rs.masumi.app.importing.SelectedMangaImporter
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.pipeline.PipelineQueueStatus
import rs.masumi.app.pipeline.PipelineQueueStore
import rs.masumi.app.pipeline.PipelineArtifactFreshness
import rs.masumi.app.pipeline.PipelineSchedulerService
import rs.masumi.app.pipeline.PipelineThreading
import rs.masumi.app.pipeline.WorkspaceJanitor
import rs.masumi.app.translation.TranslationForegroundService
import rs.masumi.app.translation.TranslationSettingsStore
import rs.masumi.app.typesetting.TypesettingForegroundService
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.modelpackage.PinnedAotInpainter
import rs.masumi.core.typesetting.TypesettingPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class LibraryActivity : Activity() {
    private lateinit var locationText: TextView
    private lateinit var storageSetup: View
    private lateinit var pager: HorizontalSwipeViewFlipper
    private lateinit var finishedTabButton: Button
    private lateinit var processingTabButton: Button
    private lateinit var finishedContainer: LinearLayout
    private lateinit var processingContainer: LinearLayout
    private lateinit var finishedEmptyText: TextView
    private lateinit var processingEmptyText: TextView
    private lateinit var finishedRefresh: SwipeRefreshLayout
    private lateinit var processingRefresh: SwipeRefreshLayout
    private lateinit var finishedScroll: ScrollView
    private lateinit var processingScroll: ScrollView
    private lateinit var chooseLibraryButton: Button
    private lateinit var importButton: Button
    private lateinit var catalog: ProjectCatalog
    private lateinit var libraryPreferences: MangaLibraryPreferences
    private lateinit var readerPreferences: MangaReaderPreferences
    private lateinit var pipelineQueueStore: PipelineQueueStore
    private lateinit var snapshotStore: LibraryHomeSnapshotStore
    private lateinit var projectOrderStore: MangaLibraryOrderStore
    private lateinit var imageDiskCache: LibraryImageDiskCache
    private val executor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory("masumi-library-home"),
    )
    private val maintenanceExecutor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory("masumi-library-maintenance"),
    )
    private val coverExecutor = Executors.newFixedThreadPool(
        COVER_LOAD_WORKERS,
        PipelineThreading.factory("masumi-shelf-cover", numbered = true),
    )
    private val refreshGeneration = AtomicInteger()
    private val maintenanceScheduled = AtomicBoolean(false)
    private var pendingImportAfterLibrary = false
    private var importRunning = false
    private val displayedCovers = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var displayedCoverBytes = 0L
    private val coverRequests = ShelfCoverRequestTracker()
    private val desiredCoverKeys = mutableSetOf<String>()
    private val resolvedCoverKeys = mutableSetOf<String>()
    private var coverWindowRefreshScheduled = false
    private val renderedProjectItems = mutableMapOf<String, View>()
    private val renderedProjectSummaries = mutableMapOf<String, LibraryProjectSummary>()
    private var renderedSignature = emptyList<String>()
    private var coverRenderGeneration = 0L
    private var initialTabResolved = false
    private var finishedOrderChangedDuringDrag = false
    private var activeDragShadowBitmap: Bitmap? = null
    private val activityStartedAtMillis = SystemClock.elapsedRealtime()
    private var shelfRevealResolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        locationText = findViewById(R.id.libraryHomeLocation)
        storageSetup = findViewById(R.id.libraryHomeStorageSetup)
        pager = findViewById(R.id.libraryHomePager)
        finishedTabButton = findViewById(R.id.libraryHomeTabFinished)
        processingTabButton = findViewById(R.id.libraryHomeTabProcessing)
        finishedContainer = findViewById(R.id.libraryHomeFinishedList)
        processingContainer = findViewById(R.id.libraryHomeProcessingList)
        finishedEmptyText = findViewById(R.id.libraryHomeFinishedEmpty)
        processingEmptyText = findViewById(R.id.libraryHomeProcessingEmpty)
        finishedRefresh = findViewById(R.id.libraryHomeFinishedRefresh)
        processingRefresh = findViewById(R.id.libraryHomeProcessingRefresh)
        finishedScroll = findViewById(R.id.libraryHomeFinishedScroll)
        processingScroll = findViewById(R.id.libraryHomeProcessingScroll)
        chooseLibraryButton = findViewById(R.id.libraryHomeChooseFolder)
        importButton = findViewById(R.id.libraryHomeImport)
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))
        libraryPreferences = MangaLibraryPreferences(this)
        readerPreferences = MangaReaderPreferences(this)
        pipelineQueueStore = PipelineQueueStore(this)
        snapshotStore = LibraryHomeSnapshotStore(this)
        projectOrderStore = MangaLibraryOrderStore(this)
        imageDiskCache = LibraryImageDiskCache(cacheDir.toPath().resolve("library-image-cache"))
        configureFinishedProjectDragging()

        importButton.setOnClickListener { importChapter() }
        chooseLibraryButton.setOnClickListener { openLibraryFolder(false) }
        finishedTabButton.setOnClickListener { selectTab(TAB_FINISHED) }
        processingTabButton.setOnClickListener { selectTab(TAB_PROCESSING) }
        finishedRefresh.setOnRefreshListener(::refreshLibrary)
        processingRefresh.setOnRefreshListener(::refreshLibrary)
        finishedRefresh.setOnChildScrollUpCallback { _, _ -> finishedScroll.canScrollVertically(-1) }
        processingRefresh.setOnChildScrollUpCallback { _, _ -> processingScroll.canScrollVertically(-1) }
        finishedScroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleCurrentViewportCoverLoading() }
        processingScroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleCurrentViewportCoverLoading() }
        savedInstanceState?.getInt(STATE_SELECTED_TAB)?.let { restored ->
            initialTabResolved = true
            selectTab(restored)
        } ?: selectTab(TAB_FINISHED)
        pager.onSwipe = { direction ->
            when (direction) {
                HorizontalSwipeViewFlipper.Direction.LEFT -> selectTab(TAB_PROCESSING)
                HorizontalSwipeViewFlipper.Direction.RIGHT -> selectTab(TAB_FINISHED)
            }
        }
        if (libraryPreferences.rootUri() == null && savedInstanceState == null) {
            revealShelf()
            storageSetup.post { openLibraryFolder(false) }
        } else {
            pager.visibility = View.INVISIBLE
            pager.postDelayed(
                ::revealShelf,
                ShelfCoverLoadPolicy.remainingRevealDelay(
                    activityStartedAtMillis,
                    SHELF_REVEAL_DEADLINE_MILLIS,
                    SystemClock.elapsedRealtime(),
                ),
            )
            renderCachedLibrary()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, pager.displayedChild)
    }

    private fun selectTab(tab: Int) {
        pager.displayedChild = tab
        finishedTabButton.isSelected = pager.displayedChild == TAB_FINISHED
        processingTabButton.isSelected = pager.displayedChild == TAB_PROCESSING
        scheduleCurrentViewportCoverLoading()
    }

    override fun onResume() {
        super.onResume()
        if (pipelineQueueStore.entries().any { it.status == PipelineQueueStatus.ACTIVE }) {
            startForegroundService(PipelineSchedulerService.wakeIntent(this))
        }
        refreshLibrary()
    }

    override fun onDestroy() {
        refreshGeneration.incrementAndGet()
        executor.shutdownNow()
        maintenanceExecutor.shutdownNow()
        coverExecutor.shutdownNow()
        coverRequests.clear()
        activeDragShadowBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        activeDragShadowBitmap = null
        recycleCovers()
        super.onDestroy()
    }

    private fun importChapter() {
        if (importRunning) return
        if (libraryPreferences.rootUri() == null) {
            openLibraryFolder(true)
        } else {
            showImportSourceChooser()
        }
    }

    private fun showImportSourceChooser() {
        if (importRunning || isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.library_import_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.library_import_files),
                    getString(R.string.library_import_folder),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openChapterDocuments()
                    1 -> openChapterFolder()
                }
            }
            .show()
    }

    private fun openChapterDocuments() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENTS)
    }

    private fun openChapterFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_CHAPTER)
    }

    private fun openLibraryFolder(continueToImport: Boolean) {
        pendingImportAfterLibrary = continueToImport
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_LIBRARY_FOLDER)
    }

    @Deprecated("Uses the platform result API to keep the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_LIBRARY_FOLDER) pendingImportAfterLibrary = false
            return
        }
        if (requestCode == REQUEST_OPEN_DOCUMENTS) {
            val resultData = data ?: return
            val uris = selectedDocumentUris(resultData)
            uris.forEach { retainReadPermission(it, resultData.flags) }
            importSelectedDocuments(uris)
            return
        }
        val uri = data?.data ?: return
        if (requestCode == REQUEST_OPEN_CHAPTER) {
            retainReadPermission(uri, data.flags)
            startActivity(MainActivity.importIntent(this, uri))
            return
        }
        if (requestCode != REQUEST_LIBRARY_FOLDER) return
        val hasRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val hasWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        when {
            hasRead && hasWrite -> runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            hasRead -> runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            hasWrite -> runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        libraryPreferences.saveRootUri(uri)
        refreshLibrary()
        if (pendingImportAfterLibrary) {
            pendingImportAfterLibrary = false
            pager.post(::showImportSourceChooser)
        }
    }

    private fun selectedDocumentUris(data: Intent): List<Uri> = buildList {
        data.clipData?.let { selected ->
            repeat(selected.itemCount) { index ->
                selected.getItemAt(index).uri?.let(::add)
            }
        }
        data.data?.let(::add)
    }.distinct()

    private fun importSelectedDocuments(uris: List<Uri>) {
        val libraryRoot = libraryPreferences.rootUri() ?: return
        if (uris.isEmpty() || importRunning) {
            if (uris.isEmpty()) {
                Toast.makeText(this, R.string.library_import_nothing_selected, Toast.LENGTH_SHORT).show()
            }
            return
        }
        importRunning = true
        importButton.isEnabled = false
        importButton.setText(R.string.library_import_preparing)
        val appContext = applicationContext
        val registrar = ImportedMangaPipelineRegistrar(
            queueStore = PipelineQueueStore(appContext),
            hasTranslationSettings = {
                TranslationSettingsStore(appContext).loadProviderSettings() != null
            },
            wakeScheduler = {
                appContext.startForegroundService(PipelineSchedulerService.wakeIntent(appContext))
            },
        )
        SelectedMangaImportProcessExecutor.execute(Runnable {
            val result = runCatching {
                val documents = SelectedMangaDocumentReader(appContext.contentResolver).read(uris)
                if (documents.isEmpty()) {
                    return@runCatching SelectedMangaImportResult(
                        imported = emptyList(),
                        failedNames = emptyList(),
                        unsupportedCount = uris.size,
                    )
                }
                SelectedMangaImporter(
                    resolver = appContext.contentResolver,
                    workspaceRoot = appContext.filesDir.toPath().resolve("workspace"),
                    cacheDirectory = appContext.cacheDir.toPath().resolve("selected-manga-import"),
                    libraryRoot = libraryRoot,
                ).import(documents) { completed, total ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && importRunning) {
                            importButton.text = getString(
                                R.string.library_import_progress,
                                completed,
                                total.coerceAtLeast(1),
                            )
                        }
                    }
                }
            }.map { imported ->
                imported to registrar.register(
                    imported.imported.map { it.outcome.manifest.projectId },
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                importRunning = false
                importButton.isEnabled = true
                importButton.setText(R.string.library_home_import)
                result.fold(
                    onSuccess = { (imported, registration) ->
                        val rejected = imported.failedNames.size + imported.unsupportedCount
                        val message = when {
                            imported.imported.isEmpty() ->
                                getString(R.string.library_import_all_failed, rejected)
                            rejected > 0 ->
                                getString(
                                    R.string.library_import_partial,
                                    imported.imported.size,
                                    rejected,
                                )
                            registration.hasTranslationSettings ->
                                getString(R.string.library_import_success_queued, imported.imported.size)
                            else ->
                                getString(R.string.library_import_success_ready, imported.imported.size)
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        if (imported.imported.isNotEmpty()) selectTab(TAB_PROCESSING)
                    },
                    onFailure = {
                        Toast.makeText(
                            this,
                            R.string.library_import_failed_unknown,
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
                refreshLibrary()
            }
        })
    }

    private fun retainReadPermission(uri: Uri, resultFlags: Int) {
        if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun renderCachedLibrary() {
        val rootUri = libraryPreferences.rootUri() ?: return
        val snapshot = snapshotStore.load(rootUri) ?: return
        val queueEntries = pipelineQueueStore.entries().associateBy { it.projectId }
        val summaries = snapshot.projects.map { project ->
            val queueEntry = queueEntries[project.metadata.projectId]
            LibraryProjectSummary(
                project = project,
                completedStages = if (
                    isFinishedLibraryProject(project.outputPageCount, queueEntry?.status)
                ) {
                    AutomaticPipelinePlanner.STAGE_COUNT
                } else {
                    0
                },
                cover = null,
                coverSource = null,
                queueStatus = queueEntry?.status,
                queueErrorCode = queueEntry?.errorCode,
            )
        }
        renderLibrary(
            rootUri,
            snapshot.rootDisplayName,
            summaries,
            unavailable = false,
            deferCoverResolution = true,
        )
    }

    private fun refreshLibrary() {
        val generation = refreshGeneration.incrementAndGet()
        val rootUri = libraryPreferences.rootUri()
        if (rootUri == null) {
            finishRefreshGesture()
            renderLibrary(null, null, emptyList(), false)
            return
        }
        executor.execute {
            val result = runCatching {
                val store = MangaLibraryStore(contentResolver, rootUri)
                val projects = store.refreshProjects()
                val name = store.rootDisplayName()
                snapshotStore.save(rootUri, name, projects)
                val queueEntries = pipelineQueueStore.entries().associateBy { it.projectId }
                val summaries = projects.map { project ->
                    val queueEntry = queueEntries[project.metadata.projectId]
                    buildSummary(
                        store = store,
                        project = project,
                        queueStatus = queueEntry?.status,
                        queueErrorCode = queueEntry?.errorCode,
                    )
                }
                Triple(name, rootUri, summaries)
            }
            runOnUiThread {
                if (generation != refreshGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                finishRefreshGesture()
                result.fold(
                    onSuccess = { (name, uri, projects) ->
                        renderLibrary(uri, name, projects, false)
                        scheduleWorkspaceMaintenance()
                    },
                    onFailure = { renderLibrary(rootUri, null, emptyList(), true) },
                )
            }
        }
    }

    private fun buildSummary(
        store: MangaLibraryStore,
        project: MangaLibraryProject,
        queueStatus: PipelineQueueStatus?,
        queueErrorCode: String?,
    ): LibraryProjectSummary {
        val projectId = project.metadata.projectId
        val privateProject = runCatching { catalog.openProject(projectId) }.getOrNull()
        val completedStages = if (isFinishedLibraryProject(project.outputPageCount, queueStatus)) {
            AutomaticPipelinePlanner.STAGE_COUNT
        } else {
            val detection = privateProject?.let { projectRef ->
                catalog.publishedDetectionRuns(projectId).firstOrNull {
                    PipelineArtifactFreshness.detection(it.artifact, projectRef.manifest)
                }
            }
            val ocr = detection?.let { currentDetection ->
                catalog.publishedOcrRuns(projectId).firstOrNull {
                    PipelineArtifactFreshness.ocr(
                        it.artifact,
                        currentDetection.artifact,
                    )
                }
            }
            val translation = ocr?.let { currentOcr ->
                catalog.publishedTranslationRuns(projectId).firstOrNull {
                    PipelineArtifactFreshness.translation(
                        it.artifact,
                        currentOcr.artifact,
                    )
                }
            }
            val cleanup = translation?.let {
                catalog.latestPublishedCleanupRun(
                    projectId = projectId,
                    translationRunArtifactKey = it.artifact.runArtifactKey,
                    policy = CleanupPolicy(),
                    maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
                    neuralModel = PinnedAotInpainter.descriptor.toModelRef(),
                )?.takeIf { currentCleanup ->
                    PipelineArtifactFreshness.cleanup(
                        currentCleanup.artifact,
                        it.artifact,
                    )
                }
            }
            val typesetting = cleanup?.let {
                catalog.latestPublishedTypesettingRun(
                    projectId,
                    it.artifact.runArtifactKey,
                    TypesettingPolicy(),
                )?.takeIf { currentTypesetting ->
                    PipelineArtifactFreshness.typesetting(currentTypesetting.artifact, it.artifact)
                }
            }
            when {
                typesetting != null -> 5
                cleanup != null -> 4
                translation != null -> 3
                ocr != null -> 2
                detection != null -> 1
                else -> 0
            }
        }
        val coverPath = privateProject?.manifest?.pages
            ?.minByOrNull { it.order }
            ?.storedPath
            ?.let { resolveInside(privateProject.directory, it) }
        val coverUri = if (coverPath == null) {
            project.outputDirectoryUri
                ?.takeIf { project.outputPageCount > 0 }
                ?.let { store.outputPages(projectId).firstOrNull()?.uri }
        } else {
            null
        }
        return LibraryProjectSummary(
            project = project,
            completedStages = completedStages,
            cover = null,
            coverSource = coverPath?.let { CoverSource(path = it, uri = null) }
                ?: coverUri?.let { CoverSource(path = null, uri = it) },
            queueStatus = queueStatus,
            queueErrorCode = queueErrorCode,
        )
    }

    private fun scheduleWorkspaceMaintenance() {
        if (!maintenanceScheduled.compareAndSet(false, true)) return
        maintenanceExecutor.execute {
            runCatching {
                val activeProjects = pipelineQueueStore.entries()
                    .filter { it.status == PipelineQueueStatus.ACTIVE }
                    .mapTo(mutableSetOf()) { it.projectId }
                WorkspaceJanitor.sweepAll(filesDir.toPath().resolve("workspace"), activeProjects::contains)
            }
        }
    }

    private fun renderLibrary(
        rootUri: Uri?,
        displayName: String?,
        projects: List<LibraryProjectSummary>,
        unavailable: Boolean,
        deferCoverResolution: Boolean = false,
    ) {
        storageSetup.visibility = if (rootUri == null || unavailable) View.VISIBLE else View.GONE
        chooseLibraryButton.setText(R.string.library_home_choose_folder)
        locationText.text = when {
            unavailable -> getString(R.string.library_home_storage_repair)
            else -> getString(R.string.library_home_storage_setup)
        }
        val (unorderedFinished, processing) = projects.partition {
            isFinishedLibraryProject(it.project.outputPageCount, it.queueStatus)
        }
        val finished = if (rootUri == null) {
            unorderedFinished
        } else {
            val byId = unorderedFinished.associateBy { it.project.metadata.projectId }
            projectOrderStore.orderedProjectIds(
                rootUri,
                unorderedFinished.map { it.project.metadata.projectId },
            ).mapNotNull(byId::get)
        }
        finishedTabButton.text = getString(R.string.library_tab_finished_count, finished.size)
        processingTabButton.text = getString(R.string.library_tab_processing_count, processing.size)
        finishedEmptyText.visibility = if (finished.isEmpty() && !unavailable) View.VISIBLE else View.GONE
        processingEmptyText.visibility = if (processing.isEmpty() && !unavailable) View.VISIBLE else View.GONE
        val signature = buildList {
            finished.forEach { add("finished:${it.project.metadata.projectId}") }
            processing.forEach { add("processing:${it.project.metadata.projectId}") }
        }
        val canRebind = signature == renderedSignature &&
            projects.all { renderedProjectItems.containsKey(it.project.metadata.projectId) }
        val previousCovers = if (canRebind) emptyList() else displayedCovers.values.toList()
        if (!canRebind) {
            displayedCovers.clear()
            displayedCoverBytes = 0L
            resolvedCoverKeys.clear()
        }
        if (!canRebind) {
            finishedContainer.removeAllViews()
            processingContainer.removeAllViews()
            renderedProjectItems.clear()
        }
        renderedProjectSummaries.clear()
        projects.forEach { summary ->
            renderedProjectSummaries[summary.project.metadata.projectId] = summary
        }
        finished.forEach { summary ->
            bindProjectItem(
                item = renderedProjectItems[summary.project.metadata.projectId]
                    ?: createProjectItem(finishedContainer).also { item ->
                        renderedProjectItems[summary.project.metadata.projectId] = item
                        finishedContainer.addView(item)
                    },
                rootUri = rootUri,
                summary = summary,
            )
        }
        processing.forEach { summary ->
            bindProjectItem(
                item = renderedProjectItems[summary.project.metadata.projectId]
                    ?: createProjectItem(processingContainer).also { item ->
                        renderedProjectItems[summary.project.metadata.projectId] = item
                        processingContainer.addView(item)
                    },
                rootUri = rootUri,
                summary = summary,
            )
        }
        updateRenderedSignature(signature)
        previousCovers.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        // A fresh install lands on whichever column actually has content, but a
        // deliberate tab choice is never yanked away by a later refresh.
        if (!initialTabResolved && !unavailable && rootUri != null) {
            initialTabResolved = true
            if (finished.isEmpty() && processing.isNotEmpty()) selectTab(TAB_PROCESSING)
        }
        val shelfOrder = if (pager.displayedChild == TAB_FINISHED) finished + processing else processing + finished
        val coversToLoad = shelfOrder.filter { summary ->
            !hasRenderedCover(summary) &&
                coverKey(summary) !in resolvedCoverKeys
        }
        if (deferCoverResolution) return
        if (
            ShelfCoverLoadPolicy.shouldUseInitialCohort(
                shelfAlreadyRevealed = shelfRevealResolved,
                missingCoverCount = coversToLoad.size,
            )
        ) {
            scheduleCoverLoading(coversToLoad, coverRenderGeneration)
        } else {
            revealShelf()
            scheduleCurrentViewportCoverLoading()
        }
    }

    private fun createProjectItem(parent: LinearLayout): View =
        LayoutInflater.from(this).inflate(R.layout.item_library_project, parent, false)

    private fun hasRenderedCover(summary: LibraryProjectSummary): Boolean =
        renderedProjectItems[summary.project.metadata.projectId]
            ?.findViewById<ImageView>(R.id.libraryProjectCover)
            ?.let { cover -> cover.drawable != null && cover.tag == coverKey(summary) }
            ?: false

    private fun scheduleCoverLoading(
        summaries: List<LibraryProjectSummary>,
        renderGeneration: Long,
    ) {
        val ordered = summaries
        if (ordered.isEmpty()) return
        val viewport = if (pager.displayedChild == TAB_FINISHED) finishedScroll else processingScroll
        viewport.post {
            if (isFinishing || isDestroyed || coverRenderGeneration != renderGeneration) return@post
            val firstItem = ordered.firstNotNullOfOrNull { summary ->
                renderedProjectItems[summary.project.metadata.projectId]
            }
            val cohortCount = ShelfCoverLoadPolicy.initialViewportCount(
                viewportHeight = viewport.height,
                cardHeight = firstItem?.height?.takeIf { it > 0 } ?: dp(160),
                itemCount = ordered.size,
            )
            val plan = ShelfCoverLoadPolicy.split(
                ordered.map { it.project.metadata.projectId },
                cohortCount,
            )
            val byId = ordered.associateBy { it.project.metadata.projectId }
            val cohort = plan.initial.mapNotNull(byId::get).mapNotNull { summary ->
                coverRequests.tryStart(coverKey(summary), renderGeneration)?.let { request ->
                    summary to request
                }
            }
            if (cohort.isEmpty()) return@post
            updateDesiredCoverWindow(
                (plan.initial + plan.deferred).mapTo(linkedSetOf(), byId::getValue),
            )
            executor.execute {
                val futures = coverExecutor.invokeAll(
                    cohort.map { (summary, request) ->
                        Callable {
                            val bitmap = loadCover(summary, LibraryImageAssetKind.FIRST_VIEWPORT_COVER)
                            if (Thread.currentThread().isInterrupted) {
                                bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                                LoadedCover(summary, request, null)
                            } else {
                                LoadedCover(summary, request, bitmap)
                            }
                        }
                    },
                    FIRST_VIEWPORT_COVER_DEADLINE_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                val loaded = futures.mapIndexed { index, future ->
                    if (future.isCancelled) {
                        LoadedCover(cohort[index].first, cohort[index].second, null)
                    } else {
                        runCatching { future.get() }.getOrElse {
                            LoadedCover(cohort[index].first, cohort[index].second, null)
                        }
                    }
                }
                runOnUiThread {
                    // One UI pass makes the visible cohort appear atomically; entries
                    // that missed the deadline retain their stable initial and stay
                    // retryable for the viewport pass immediately after reveal.
                    loaded.forEach { loadedCover ->
                        val key = coverKey(loadedCover.summary)
                        when (
                            coverRequests.complete(
                                request = loadedCover.request,
                                currentRenderGeneration = coverRenderGeneration,
                                stillDesired = !isFinishing && !isDestroyed && key in desiredCoverKeys,
                                bitmapAvailable = loadedCover.bitmap != null,
                            )
                        ) {
                            ShelfCoverCompletionAction.APPLY_AND_RESOLVE -> {
                                resolvedCoverKeys += key
                                bindLoadedCover(loadedCover)
                            }
                            ShelfCoverCompletionAction.KEEP_RETRYABLE,
                            ShelfCoverCompletionAction.DISCARD,
                            -> loadedCover.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                        }
                    }
                    if (isFinishing || isDestroyed || coverRenderGeneration != renderGeneration) {
                        return@runOnUiThread
                    }
                    revealShelf()
                    scheduleCurrentViewportCoverLoading()
                }
                plan.deferred.mapNotNull(byId::get).forEach { summary ->
                    runOnUiThread { enqueueCoverLoad(summary, renderGeneration) }
                }
            }
        }
    }

    private fun scheduleCurrentViewportCoverLoading() {
        if (
            !ShelfCoverLoadPolicy.shouldScheduleViewport(shelfRevealResolved) ||
            coverWindowRefreshScheduled ||
            isFinishing ||
            isDestroyed
        ) return
        coverWindowRefreshScheduled = true
        pager.postOnAnimation {
            coverWindowRefreshScheduled = false
            if (isFinishing || isDestroyed) return@postOnAnimation
            val viewport = if (pager.displayedChild == TAB_FINISHED) finishedScroll else processingScroll
            val container = if (pager.displayedChild == TAB_FINISHED) finishedContainer else processingContainer
            val viewportLocation = IntArray(2).also(viewport::getLocationOnScreen)
            val viewportHeight = viewport.height
            if (viewportHeight <= 0) return@postOnAnimation
            val windowTop = viewportLocation[1] - viewportHeight
            val windowBottom = viewportLocation[1] + viewportHeight * 2
            val desired = buildList {
                repeat(container.childCount) { index ->
                    val item = container.getChildAt(index)
                    val itemLocation = IntArray(2).also(item::getLocationOnScreen)
                    if (itemLocation[1] < windowBottom && itemLocation[1] + item.height > windowTop) {
                        val projectId = item.tag as? String
                        renderedProjectSummaries[projectId]?.let(::add)
                    }
                }
            }
            if (desired.isEmpty()) return@postOnAnimation
            updateDesiredCoverWindow(desired.toCollection(linkedSetOf()))
            val renderGeneration = coverRenderGeneration
            desired.forEach { summary ->
                val projectId = summary.project.metadata.projectId
                displayedCovers[projectId]
                if (!hasRenderedCover(summary)) enqueueCoverLoad(summary, renderGeneration)
            }
        }
    }

    private fun updateDesiredCoverWindow(summaries: Set<LibraryProjectSummary>) {
        desiredCoverKeys.clear()
        summaries.mapTo(desiredCoverKeys, ::coverKey)
        renderedProjectItems.forEach { (projectId, item) ->
            val cover = item.findViewById<ImageView>(R.id.libraryProjectCover)
            val key = cover.tag as? String
            if (cover.drawable != null && key !in desiredCoverKeys) {
                releaseDisplayedCover(projectId, cover, key)
            }
        }
    }

    private fun enqueueCoverLoad(summary: LibraryProjectSummary, renderGeneration: Long) {
        val key = coverKey(summary)
        if (
            renderGeneration != coverRenderGeneration ||
            key !in desiredCoverKeys ||
            key in resolvedCoverKeys
        ) return
        val request = coverRequests.tryStart(key, renderGeneration) ?: return
        runCatching {
            coverExecutor.execute {
                val bitmap = if (Thread.currentThread().isInterrupted) {
                    null
                } else {
                    runCatching { loadCover(summary, LibraryImageAssetKind.COVER) }.getOrNull()
                }
                runOnUiThread {
                    val loaded = LoadedCover(summary, request, bitmap)
                    when (
                        coverRequests.complete(
                            request = request,
                            currentRenderGeneration = coverRenderGeneration,
                            stillDesired = !isFinishing && !isDestroyed && key in desiredCoverKeys,
                            bitmapAvailable = bitmap != null,
                        )
                    ) {
                        ShelfCoverCompletionAction.APPLY_AND_RESOLVE -> {
                            resolvedCoverKeys += key
                            bindLoadedCover(loaded)
                        }
                        ShelfCoverCompletionAction.KEEP_RETRYABLE,
                        ShelfCoverCompletionAction.DISCARD,
                        -> bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    }
                }
            }
        }.onFailure {
            coverRequests.complete(
                request = request,
                currentRenderGeneration = coverRenderGeneration,
                stillDesired = false,
                bitmapAvailable = false,
            )
        }
    }

    private fun loadCover(
        summary: LibraryProjectSummary,
        kind: LibraryImageAssetKind,
    ): Bitmap? {
        val source = summary.coverSource ?: return null
        val cacheKey = coverKey(summary)
        imageDiskCache.read(cacheKey, promoteTo = kind)?.let { encoded ->
            BitmapFactory.decodeByteArray(
                encoded,
                0,
                encoded.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
            )?.let { return it }
        }
        val bitmap = source.path?.let(::decodeCover) ?: source.uri?.let(::decodeCover) ?: return null
        runCatching {
            ByteArrayOutputStream().use { output ->
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    Bitmap.CompressFormat.PNG
                }
                check(bitmap.compress(format, COVER_CACHE_QUALITY, output))
                imageDiskCache.write(cacheKey, kind, output.toByteArray())
            }
        }
        return bitmap
    }

    private fun coverKey(summary: LibraryProjectSummary): String {
        val source = summary.coverSource ?: return "cover:${summary.project.metadata.projectId}:none"
        return "cover:${summary.project.metadata.projectId}:${summary.project.metadata.createdAtEpochMillis}:${source.cacheIdentity()}"
    }

    private fun bindLoadedCover(loaded: LoadedCover) {
        val projectId = loaded.summary.project.metadata.projectId
        val item = renderedProjectItems[projectId] ?: run {
            loaded.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            return
        }
        val expectedKey = coverKey(loaded.summary)
        val cover = item.findViewById<ImageView>(R.id.libraryProjectCover)
        if (cover.tag != expectedKey || expectedKey !in desiredCoverKeys) {
            loaded.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            return
        }
        item.visibility = View.VISIBLE
        item.findViewById<View>(R.id.libraryProjectCoverContainer).visibility = View.VISIBLE
        val initial = item.findViewById<TextView>(R.id.libraryProjectInitial)
        val bitmap = loaded.bitmap ?: run {
            cover.setImageDrawable(null)
            cover.visibility = View.GONE
            initial.visibility = View.VISIBLE
            return
        }
        if (cover.drawable != null) {
            displayedCovers[projectId]
            bitmap.recycle()
            return
        }
        displayedCovers.put(projectId, bitmap)?.let { previous ->
            displayedCoverBytes -= previous.allocationByteCount.toLong()
            previous.takeUnless(Bitmap::isRecycled)?.recycle()
        }
        displayedCoverBytes += bitmap.allocationByteCount.toLong()
        cover.setImageBitmap(bitmap)
        cover.visibility = View.VISIBLE
        initial.visibility = View.GONE
        trimDisplayedCoverMemory()
    }

    private fun trimDisplayedCoverMemory() {
        val iterator = displayedCovers.entries.iterator()
        while (displayedCoverBytes > COVER_BITMAP_MEMORY_BUDGET_BYTES && displayedCovers.size > 1) {
            val (projectId, bitmap) = iterator.next()
            iterator.remove()
            displayedCoverBytes -= bitmap.allocationByteCount.toLong()
            val item = renderedProjectItems[projectId]
            val cover = item?.findViewById<ImageView>(R.id.libraryProjectCover)
            val key = cover?.tag as? String
            if ((cover?.drawable as? BitmapDrawable)?.bitmap === bitmap) {
                cover.setImageDrawable(null)
                cover.visibility = View.GONE
                item.findViewById<TextView>(R.id.libraryProjectInitial).visibility = View.VISIBLE
            }
            key?.let(resolvedCoverKeys::remove)
            bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun releaseDisplayedCover(projectId: String, cover: ImageView, key: String?) {
        val tracked = displayedCovers.remove(projectId)
        val drawableBitmap = (cover.drawable as? BitmapDrawable)?.bitmap
        cover.setImageDrawable(null)
        cover.visibility = View.GONE
        renderedProjectItems[projectId]
            ?.findViewById<TextView>(R.id.libraryProjectInitial)
            ?.visibility = View.VISIBLE
        if (tracked != null) {
            displayedCoverBytes = (displayedCoverBytes - tracked.allocationByteCount.toLong()).coerceAtLeast(0L)
            tracked.takeUnless(Bitmap::isRecycled)?.recycle()
            key?.let(resolvedCoverKeys::remove)
        }
        if (drawableBitmap != null && drawableBitmap !== tracked) {
            drawableBitmap.takeUnless(Bitmap::isRecycled)?.recycle()
            key?.let(resolvedCoverKeys::remove)
        }
    }

    private fun revealShelf() {
        if (shelfRevealResolved || isFinishing || isDestroyed) return
        shelfRevealResolved = true
        pager.visibility = View.VISIBLE
    }

    private fun bindProjectItem(
        item: View,
        rootUri: Uri?,
        summary: LibraryProjectSummary,
    ) {
        val project = summary.project
        item.tag = project.metadata.projectId
        item.findViewById<TextView>(R.id.libraryProjectTitle).text = project.metadata.title
        val initial = item.findViewById<TextView>(R.id.libraryProjectInitial).apply {
            text = project.metadata.title.trim().firstOrNull()?.toString().orEmpty()
        }
        val cover = item.findViewById<ImageView>(R.id.libraryProjectCover)
        val expectedCoverKey = coverKey(summary)
        if (cover.tag != expectedCoverKey) {
            val stale = (cover.drawable as? BitmapDrawable)?.bitmap
            val tracked = displayedCovers.remove(project.metadata.projectId)
            if (tracked != null) {
                displayedCoverBytes = (displayedCoverBytes - tracked.allocationByteCount.toLong()).coerceAtLeast(0L)
                tracked.takeUnless(Bitmap::isRecycled)?.recycle()
            }
            if (stale != null && stale !== tracked) {
                stale.takeUnless(Bitmap::isRecycled)?.recycle()
            }
            (cover.tag as? String)?.let(resolvedCoverKeys::remove)
            cover.setImageDrawable(null)
            cover.tag = expectedCoverKey
        }
        if (cover.drawable == null) {
            cover.visibility = View.GONE
            initial.visibility = View.VISIBLE
        } else {
            initial.visibility = View.GONE
        }
        item.findViewById<ProgressBar>(R.id.libraryProjectProgress).apply {
            max = AutomaticPipelinePlanner.STAGE_COUNT
            progress = summary.completedStages
        }
        item.findViewById<TextView>(R.id.libraryProjectStatus).text = statusText(summary)
        summary.cover?.let { bitmap ->
            displayedCovers.put(project.metadata.projectId, bitmap)?.let { previous ->
                displayedCoverBytes -= previous.allocationByteCount.toLong()
                if (previous !== bitmap) previous.takeUnless(Bitmap::isRecycled)?.recycle()
            }
            displayedCoverBytes += bitmap.allocationByteCount.toLong()
            item.findViewById<View>(R.id.libraryProjectCoverContainer).visibility = View.VISIBLE
            cover.apply {
                setImageBitmap(bitmap)
                tag = expectedCoverKey
                visibility = View.VISIBLE
            }
            initial.visibility = View.GONE
            trimDisplayedCoverMemory()
        }
        val primary = item.findViewById<Button>(R.id.libraryProjectReadButton)
        val secondary = item.findViewById<Button>(R.id.libraryProjectOpenButton)
        bindProjectDragHandle(item, rootUri, summary)
        val canReadCurrentResult = rootUri != null && isFinishedLibraryProject(
            project.outputPageCount,
            summary.queueStatus,
        )
        item.isClickable = false
        item.isFocusable = false
        item.setOnClickListener(null)
        if (canReadCurrentResult) {
            primary.setText(R.string.library_read)
            primary.setOnClickListener {
                startActivity(MangaReaderActivity.intent(this, requireNotNull(rootUri), project))
            }
        } else {
            primary.setText(
                when {
                    summary.queueStatus == PipelineQueueStatus.ACTIVE ->
                        R.string.library_processing_queued
                    summary.queueStatus == PipelineQueueStatus.PAUSED ->
                        R.string.library_continue_processing
                    summary.completedStages >= AutomaticPipelinePlanner.STAGE_COUNT ->
                        R.string.library_finish_saving
                    summary.completedStages > 0 ->
                        R.string.library_continue_processing
                    else ->
                        R.string.library_start_processing
                },
            )
            primary.setOnClickListener {
                startActivity(MainActivity.projectIntent(this, project.metadata.projectId))
            }
        }
        secondary.setText(R.string.library_manage)
        secondary.setOnClickListener {
            showProjectManagement(rootUri, summary)
        }
    }

    private fun bindProjectDragHandle(
        item: View,
        rootUri: Uri?,
        summary: LibraryProjectSummary,
    ) {
        val handle = item.findViewById<ImageView>(R.id.libraryProjectDragHandle)
        val canReorder = rootUri != null && isFinishedLibraryProject(
            summary.project.outputPageCount,
            summary.queueStatus,
        )
        handle.visibility = if (canReorder) View.VISIBLE else View.GONE
        handle.setOnTouchListener(null)
        if (!canReorder) return
        handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                activeDragShadowBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                val shadowBitmap = Bitmap.createBitmap(
                    item.width,
                    item.height,
                    Bitmap.Config.ARGB_8888,
                ).also { item.draw(Canvas(it)) }
                activeDragShadowBitmap = shadowBitmap
                val handleLocation = IntArray(2)
                val itemLocation = IntArray(2)
                handle.getLocationOnScreen(handleLocation)
                item.getLocationOnScreen(itemLocation)
                val touchX = handleLocation[0] - itemLocation[0] + event.x
                val touchY = handleLocation[1] - itemLocation[1] + event.y
                val dragData = ClipData.newPlainText(
                    getString(R.string.library_reorder_drag_handle),
                    summary.project.metadata.projectId,
                )
                val started = item.startDragAndDrop(
                    dragData,
                    ProjectDragShadowBuilder(item, shadowBitmap, touchX, touchY),
                    item,
                    0,
                )
                if (started) {
                    item.animate().cancel()
                    item.alpha = DRAGGED_ITEM_PLACEHOLDER_ALPHA
                    item.scaleX = DRAGGED_ITEM_SCALE
                    item.scaleY = DRAGGED_ITEM_SCALE
                    item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } else {
                    shadowBitmap.recycle()
                    activeDragShadowBitmap = null
                }
            }
            true
        }
    }

    private fun configureFinishedProjectDragging() {
        finishedContainer.setOnDragListener { _, event ->
            val dragged = event.localState as? View
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val accepts = dragged?.parent === finishedContainer
                    if (accepts) finishedOrderChangedDuringDrag = false
                    accepts
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (dragged?.parent !== finishedContainer) return@setOnDragListener false
                    moveFinishedProjectToPointer(dragged, event.y)
                    autoScrollFinishedProjects(event.y)
                    true
                }
                DragEvent.ACTION_DROP -> dragged?.parent === finishedContainer
                DragEvent.ACTION_DRAG_ENDED -> {
                    TransitionManager.endTransitions(finishedContainer)
                    dragged?.animate()?.cancel()
                    dragged?.animate()
                        ?.alpha(1f)
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.setDuration(DRAG_SETTLE_DURATION_MS)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                    activeDragShadowBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    activeDragShadowBitmap = null
                    if (finishedOrderChangedDuringDrag) persistFinishedProjectOrder()
                    finishedOrderChangedDuringDrag = false
                    true
                }
                else -> dragged?.parent === finishedContainer
            }
        }
    }

    private fun moveFinishedProjectToPointer(dragged: View, pointerY: Float) {
        val currentIndex = finishedContainer.indexOfChild(dragged)
        if (currentIndex < 0) return
        var insertionIndex = 0
        repeat(finishedContainer.childCount) { index ->
            val child = finishedContainer.getChildAt(index)
            if (child !== dragged && pointerY > child.top + child.height / 2f) insertionIndex += 1
        }
        if (insertionIndex == currentIndex) return
        TransitionManager.endTransitions(finishedContainer)
        TransitionManager.beginDelayedTransition(
            finishedContainer,
            ChangeBounds().apply {
                duration = DRAG_REORDER_DURATION_MS
                interpolator = DecelerateInterpolator()
            },
        )
        finishedContainer.removeView(dragged)
        finishedContainer.addView(dragged, insertionIndex.coerceIn(0, finishedContainer.childCount))
        finishedOrderChangedDuringDrag = true
        dragged.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun autoScrollFinishedProjects(pointerY: Float) {
        val scrollView = finishedContainer.findAncestorScrollView() ?: return
        val containerLocation = IntArray(2)
        val scrollLocation = IntArray(2)
        finishedContainer.getLocationOnScreen(containerLocation)
        scrollView.getLocationOnScreen(scrollLocation)
        val pointerOnScreen = containerLocation[1] + pointerY
        val edge = dp(DRAG_SCROLL_EDGE_DP)
        val step = dp(DRAG_SCROLL_STEP_DP)
        when {
            pointerOnScreen < scrollLocation[1] + edge -> scrollView.scrollBy(0, -step)
            pointerOnScreen > scrollLocation[1] + scrollView.height - edge -> scrollView.scrollBy(0, step)
        }
    }

    private fun persistFinishedProjectOrder() {
        val rootUri = libraryPreferences.rootUri() ?: return
        val projectIds = buildList {
            repeat(finishedContainer.childCount) { index ->
                (finishedContainer.getChildAt(index).tag as? String)?.let(::add)
            }
        }
        projectOrderStore.save(rootUri, projectIds)
        updateRenderedSignature(buildList {
            projectIds.forEach { add("finished:$it") }
            repeat(processingContainer.childCount) { index ->
                (processingContainer.getChildAt(index).tag as? String)?.let { add("processing:$it") }
            }
        })
        scheduleCurrentViewportCoverLoading()
    }

    private fun updateRenderedSignature(signature: List<String>) {
        if (renderedSignature == signature) return
        renderedSignature = signature
        coverRenderGeneration += 1L
    }

    private fun View.findAncestorScrollView(): ScrollView? {
        var current = parent
        while (current is View) {
            if (current is ScrollView) return current
            current = current.parent
        }
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showProjectManagement(rootUri: Uri?, summary: LibraryProjectSummary) {
        if (rootUri == null) return
        val project = summary.project
        AlertDialog.Builder(this)
            .setTitle(project.metadata.title)
            .setItems(
                arrayOf(
                    getString(R.string.library_manage_details),
                    getString(R.string.library_manage_rename),
                    getString(R.string.library_manage_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        MainActivity.projectIntent(
                            this,
                            project.metadata.projectId,
                            ProjectLaunchMode.DETAILS,
                        ),
                    )
                    1 -> showRenameDialog(rootUri, project)
                    2 -> showDeleteDialog(rootUri, summary)
                }
            }
            .show()
    }

    private fun showRenameDialog(rootUri: Uri, project: MangaLibraryProject) {
        val input = EditText(this).apply {
            setText(project.metadata.title)
            setSelection(text.length)
            hint = getString(R.string.library_rename_hint)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val requested = input.text.toString()
                executor.execute {
                    val renamed = runCatching {
                        MangaLibraryStore(contentResolver, rootUri)
                            .renameProject(project.metadata.projectId, requested)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (renamed.isSuccess) {
                                R.string.library_rename_success
                            } else {
                                R.string.library_rename_failed
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (renamed.isSuccess) refreshLibrary()
                    }
                }
            }
            .show()
    }

    private fun showDeleteDialog(rootUri: Uri, summary: LibraryProjectSummary) {
        if (summary.queueStatus == PipelineQueueStatus.ACTIVE || anyPipelineTaskActive()) {
            Toast.makeText(this, R.string.library_delete_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val project = summary.project
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.library_delete_title, project.metadata.title))
            .setMessage(R.string.library_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_delete_confirm) { _, _ ->
                executor.execute {
                    val deleted = runCatching {
                        val removed = MangaLibraryStore(contentResolver, rootUri)
                            .deleteProject(project.metadata.projectId)
                        check(removed)
                        pipelineQueueStore.remove(project.metadata.projectId)
                        readerPreferences.remove(project.metadata.projectId)
                        deletePrivateProject(project.metadata.projectId)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (deleted.isSuccess) {
                                R.string.library_delete_success
                            } else {
                                R.string.library_delete_failed
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (deleted.isSuccess) refreshLibrary()
                    }
                }
            }
            .show()
    }

    private fun deletePrivateProject(projectId: String) {
        val projectsRoot = filesDir.toPath()
            .resolve("workspace")
            .resolve("projects")
            .toAbsolutePath()
            .normalize()
        val target = projectsRoot.resolve(projectId).normalize()
        require(target.parent == projectsRoot)
        if (Files.exists(target)) check(target.toFile().deleteRecursively())
    }

    private fun anyPipelineTaskActive(): Boolean =
        DetectionForegroundService.isTaskActive() ||
            OcrForegroundService.isTaskActive() ||
            TranslationForegroundService.isTaskActive() ||
            CleanupForegroundService.isTaskActive() ||
            TypesettingForegroundService.isTaskActive() ||
            ExportForegroundService.isTaskActive()

    private fun statusText(summary: LibraryProjectSummary): String = when {
        summary.queueStatus == PipelineQueueStatus.ACTIVE ->
            getString(R.string.library_project_status_queued)
        summary.queueStatus == PipelineQueueStatus.PAUSED &&
            summary.queueErrorCode != null &&
            summary.queueErrorCode != "USER_PAUSED" ->
            getString(
                R.string.pipeline_status_failed,
                describePipelineErrorBrief(summary.queueErrorCode),
            )
        summary.queueStatus == PipelineQueueStatus.PAUSED ->
            getString(R.string.library_project_status_paused)
        summary.project.outputPageCount > 0 ->
            getString(R.string.library_project_status_ready, summary.project.outputPageCount)
        summary.completedStages >= AutomaticPipelinePlanner.STAGE_COUNT ->
            getString(R.string.library_project_status_auto_saving)
        else ->
            getString(
                R.string.library_project_status_progress,
                summary.completedStages,
                AutomaticPipelinePlanner.STAGE_COUNT,
            )
    }

    private fun decodeCover(uri: Uri): Bitmap? = decodeCover {
        contentResolver.openInputStream(uri)
    }

    private fun decodeCover(path: Path): Bitmap? = decodeCover {
        Files.newInputStream(path)
    }

    private fun decodeCover(openStream: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 720) sample *= 2
        return openStream()?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }
    }

    private fun resolveInside(root: Path, relative: String): Path? = runCatching {
        val normalizedRoot = root.normalize()
        val resolved = normalizedRoot.resolve(relative).normalize()
        require(resolved.startsWith(normalizedRoot) && Files.isRegularFile(resolved))
        resolved
    }.getOrNull()

    private fun recycleCovers() {
        renderedProjectItems.values.forEach { item ->
            item.findViewById<ImageView>(R.id.libraryProjectCover).setImageDrawable(null)
        }
        displayedCovers.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        displayedCovers.clear()
        displayedCoverBytes = 0L
        desiredCoverKeys.clear()
    }

    private data class LibraryProjectSummary(
        val project: MangaLibraryProject,
        val completedStages: Int,
        val cover: Bitmap?,
        val coverSource: CoverSource?,
        val queueStatus: PipelineQueueStatus?,
        val queueErrorCode: String?,
    )

    private data class CoverSource(
        val path: Path?,
        val uri: Uri?,
    ) {
        init {
            require((path == null) != (uri == null))
        }

        fun cacheIdentity(): String = path?.toString() ?: requireNotNull(uri).toString()
    }

    private data class LoadedCover(
        val summary: LibraryProjectSummary,
        val request: ShelfCoverRequest,
        val bitmap: Bitmap?,
    )

    private fun finishRefreshGesture() {
        finishedRefresh.isRefreshing = false
        processingRefresh.isRefreshing = false
    }

    private companion object {
        const val REQUEST_LIBRARY_FOLDER = 2101
        const val REQUEST_OPEN_CHAPTER = 2102
        const val REQUEST_OPEN_DOCUMENTS = 2103
        const val TAB_FINISHED = 0
        const val TAB_PROCESSING = 1
        const val STATE_SELECTED_TAB = "library_selected_tab"
        const val DRAGGED_ITEM_PLACEHOLDER_ALPHA = 0.08f
        const val DRAGGED_ITEM_SCALE = 0.985f
        const val DRAG_SHADOW_SCALE = 0.985f
        const val DRAG_SHADOW_ALPHA = 238
        const val DRAG_REORDER_DURATION_MS = 190L
        const val DRAG_SETTLE_DURATION_MS = 160L
        const val DRAG_SCROLL_EDGE_DP = 64
        const val DRAG_SCROLL_STEP_DP = 18
        const val FIRST_VIEWPORT_COVER_DEADLINE_MILLIS = 700L
        const val SHELF_REVEAL_DEADLINE_MILLIS = 900L
        const val COVER_LOAD_WORKERS = 4
        const val COVER_CACHE_QUALITY = 100
        const val COVER_BITMAP_MEMORY_BUDGET_BYTES = 24L * 1024L * 1024L
    }

    private class ProjectDragShadowBuilder(
        view: View,
        private val snapshot: Bitmap,
        touchX: Float,
        touchY: Float,
    ) : View.DragShadowBuilder(view) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = DRAG_SHADOW_ALPHA
        }
        private val destination = RectF()
        private val anchoredTouchX = touchX
        private val anchoredTouchY = touchY

        override fun onProvideShadowMetrics(shadowSize: Point, shadowTouchPoint: Point) {
            shadowSize.set(snapshot.width, snapshot.height)
            val horizontalInset = snapshot.width * (1f - DRAG_SHADOW_SCALE) / 2f
            val verticalInset = snapshot.height * (1f - DRAG_SHADOW_SCALE) / 2f
            shadowTouchPoint.set(
                (horizontalInset + anchoredTouchX * DRAG_SHADOW_SCALE)
                    .roundToInt()
                    .coerceIn(0, snapshot.width),
                (verticalInset + anchoredTouchY * DRAG_SHADOW_SCALE)
                    .roundToInt()
                    .coerceIn(0, snapshot.height),
            )
        }

        override fun onDrawShadow(canvas: Canvas) {
            val horizontalInset = snapshot.width * (1f - DRAG_SHADOW_SCALE) / 2f
            val verticalInset = snapshot.height * (1f - DRAG_SHADOW_SCALE) / 2f
            destination.set(
                horizontalInset,
                verticalInset,
                snapshot.width - horizontalInset,
                snapshot.height - verticalInset,
            )
            canvas.drawBitmap(snapshot, null, destination, paint)
        }
    }
}
