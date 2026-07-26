package rs.masumi.app.library

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import rs.masumi.app.AutomaticPipelinePlanner
import rs.masumi.app.HorizontalSwipeViewFlipper
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.pipeline.PipelineQueueStatus
import rs.masumi.app.pipeline.PipelineColdStartGuard
import rs.masumi.app.pipeline.PipelineQueueStore
import rs.masumi.app.pipeline.PipelineSchedulerService
import rs.masumi.app.pipeline.WorkspaceJanitor
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.typesetting.TypesettingPolicy
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LibraryActivity : Activity() {
    private lateinit var locationText: TextView
    private lateinit var pager: HorizontalSwipeViewFlipper
    private lateinit var finishedTabButton: Button
    private lateinit var processingTabButton: Button
    private lateinit var finishedContainer: LinearLayout
    private lateinit var processingContainer: LinearLayout
    private lateinit var finishedEmptyText: TextView
    private lateinit var processingEmptyText: TextView
    private lateinit var chooseLibraryButton: Button
    private lateinit var catalog: ProjectCatalog
    private lateinit var libraryPreferences: MangaLibraryPreferences
    private lateinit var readingProgressStore: MangaReadingProgressStore
    private lateinit var pipelineQueueStore: PipelineQueueStore
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "masumi-library-home") }
    private val refreshGeneration = AtomicInteger()
    private var pendingImportAfterLibrary = false
    private val displayedCovers = mutableListOf<Bitmap>()
    private var initialTabResolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        locationText = findViewById(R.id.libraryHomeLocation)
        pager = findViewById(R.id.libraryHomePager)
        finishedTabButton = findViewById(R.id.libraryHomeTabFinished)
        processingTabButton = findViewById(R.id.libraryHomeTabProcessing)
        finishedContainer = findViewById(R.id.libraryHomeFinishedList)
        processingContainer = findViewById(R.id.libraryHomeProcessingList)
        finishedEmptyText = findViewById(R.id.libraryHomeFinishedEmpty)
        processingEmptyText = findViewById(R.id.libraryHomeProcessingEmpty)
        chooseLibraryButton = findViewById(R.id.libraryHomeChooseFolder)
        catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))
        libraryPreferences = MangaLibraryPreferences(this)
        readingProgressStore = MangaReadingProgressStore(this)
        pipelineQueueStore = PipelineQueueStore(this)
        PipelineColdStartGuard.reconcile(pipelineQueueStore)
        executor.execute {
            // Sweep superseded pipeline runs left behind by crashes or by
            // sessions that ended before their post-export sweep could run.
            runCatching {
                WorkspaceJanitor.sweepAll(filesDir.toPath().resolve("workspace")) { projectId ->
                    pipelineQueueStore.entries().any {
                        it.projectId == projectId && it.status == PipelineQueueStatus.ACTIVE
                    }
                }
            }
        }

        findViewById<Button>(R.id.libraryHomeImport).setOnClickListener { importChapter() }
        chooseLibraryButton.setOnClickListener { openLibraryFolder(false) }
        finishedTabButton.setOnClickListener { selectTab(TAB_FINISHED) }
        processingTabButton.setOnClickListener { selectTab(TAB_PROCESSING) }
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, pager.displayedChild)
    }

    private fun selectTab(tab: Int) {
        pager.displayedChild = tab
        finishedTabButton.isSelected = pager.displayedChild == TAB_FINISHED
        processingTabButton.isSelected = pager.displayedChild == TAB_PROCESSING
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
        recycleCovers()
        super.onDestroy()
    }

    private fun importChapter() {
        if (libraryPreferences.rootUri() == null) {
            openLibraryFolder(true)
        } else {
            startActivity(MainActivity.importIntent(this))
        }
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
        if (requestCode != REQUEST_LIBRARY_FOLDER) return
        if (resultCode != RESULT_OK) {
            pendingImportAfterLibrary = false
            return
        }
        val uri = data?.data ?: return
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
            startActivity(MainActivity.importIntent(this))
        }
    }

    private fun refreshLibrary() {
        val generation = refreshGeneration.incrementAndGet()
        val rootUri = libraryPreferences.rootUri()
        if (rootUri == null) {
            renderLibrary(null, null, emptyList(), false)
            return
        }
        executor.execute {
            val result = runCatching {
                val store = MangaLibraryStore(contentResolver, rootUri)
                // Always rescan: exports write pages behind the store's back,
                // and this screen has no later reconcile pass that would catch
                // a stale snapshot.
                val summaries = store.refreshProjects().map { project ->
                    buildSummary(store, project)
                }
                Triple(store.rootDisplayName(), rootUri, summaries)
            }
            runOnUiThread {
                if (generation != refreshGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { (name, uri, projects) -> renderLibrary(uri, name, projects, false) },
                    onFailure = { renderLibrary(rootUri, null, emptyList(), true) },
                )
            }
        }
    }

    private fun buildSummary(
        store: MangaLibraryStore,
        project: MangaLibraryProject,
    ): LibraryProjectSummary {
        val projectId = project.metadata.projectId
        val privateProject = runCatching { catalog.openProject(projectId) }.getOrNull()
        val detection = privateProject?.let { catalog.latestPublishedRun(projectId) }
        val ocr = detection?.let { catalog.latestPublishedOcrRun(projectId) }
            ?.takeIf { it.artifact.detectionRunArtifactKey == detection.artifact.runArtifactKey }
        val translation = ocr?.let { catalog.latestPublishedTranslationRun(projectId) }
            ?.takeIf {
                it.artifact.dependencies.ocrRunArtifactKey == ocr.artifact.runArtifactKey &&
                    it.artifact.dependencies.policy == TranslationPolicy() &&
                    it.artifact.dependencies.prompt == TranslationPromptRef() &&
                    it.artifact.dependencies.batching == TranslationBatchingConfig()
            }
        val cleanup = translation?.let {
            catalog.latestPublishedCleanupRun(projectId, it.artifact.runArtifactKey)
        }
        val typesetting = cleanup?.let {
            catalog.latestPublishedTypesettingRun(
                projectId,
                it.artifact.runArtifactKey,
                TypesettingPolicy(),
            )
        }
        val completedStages = when {
            typesetting != null -> 5
            cleanup != null -> 4
            translation != null -> 3
            ocr != null -> 2
            detection != null -> 1
            else -> 0
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
        // Decode on the worker thread: cover decoding on the UI thread was the
        // multi-second stall when the home screen listed real chapters.
        val cover = coverPath?.let(::decodeCover) ?: coverUri?.let(::decodeCover)
        return LibraryProjectSummary(
            project = project,
            completedStages = completedStages,
            cover = cover,
            readingProgress = readingProgressStore.load(projectId),
            queueStatus = pipelineQueueStore.entries()
                .firstOrNull { it.projectId == projectId }
                ?.status,
        )
    }

    private fun renderLibrary(
        rootUri: Uri?,
        displayName: String?,
        projects: List<LibraryProjectSummary>,
        unavailable: Boolean,
    ) {
        recycleCovers()
        finishedContainer.removeAllViews()
        processingContainer.removeAllViews()
        chooseLibraryButton.setText(
            if (rootUri == null) R.string.library_home_choose_folder else R.string.library_home_change_folder,
        )
        locationText.text = when {
            rootUri == null -> getString(R.string.library_home_not_configured)
            unavailable -> getString(R.string.library_unavailable)
            else -> getString(R.string.library_location, displayName ?: "Manga", projects.size)
        }
        val (finished, processing) = projects.partition { it.project.outputPageCount > 0 }
        finishedTabButton.text = getString(R.string.library_tab_finished_count, finished.size)
        processingTabButton.text = getString(R.string.library_tab_processing_count, processing.size)
        finishedEmptyText.visibility = if (finished.isEmpty() && !unavailable) View.VISIBLE else View.GONE
        processingEmptyText.visibility = if (processing.isEmpty() && !unavailable) View.VISIBLE else View.GONE
        finished.forEach { finishedContainer.addView(createProjectItem(finishedContainer, rootUri, it)) }
        processing.forEach { processingContainer.addView(createProjectItem(processingContainer, rootUri, it)) }
        // A fresh install lands on whichever column actually has content, but a
        // deliberate tab choice is never yanked away by a later refresh.
        if (!initialTabResolved && !unavailable && rootUri != null) {
            initialTabResolved = true
            if (finished.isEmpty() && processing.isNotEmpty()) selectTab(TAB_PROCESSING)
        }
    }

    private fun createProjectItem(
        parent: LinearLayout,
        rootUri: Uri?,
        summary: LibraryProjectSummary,
    ): View {
        val project = summary.project
        val item = LayoutInflater.from(this).inflate(R.layout.item_library_project, parent, false)
        item.findViewById<TextView>(R.id.libraryProjectTitle).text = project.metadata.title
        item.findViewById<TextView>(R.id.libraryProjectInitial).text =
            project.metadata.title.trim().firstOrNull()?.toString().orEmpty()
        item.findViewById<ProgressBar>(R.id.libraryProjectProgress).apply {
            max = AutomaticPipelinePlanner.STAGE_COUNT
            progress = summary.completedStages
        }
        item.findViewById<TextView>(R.id.libraryProjectStatus).text = statusText(summary)
        summary.cover?.let { bitmap ->
            displayedCovers += bitmap
            item.findViewById<ImageView>(R.id.libraryProjectCover).apply {
                setImageBitmap(bitmap)
                visibility = View.VISIBLE
            }
            item.findViewById<TextView>(R.id.libraryProjectInitial).visibility = View.GONE
        }
        val primary = item.findViewById<Button>(R.id.libraryProjectReadButton)
        val secondary = item.findViewById<Button>(R.id.libraryProjectOpenButton)
        item.isClickable = true
        item.isFocusable = true
        // Reader-first: tapping a finished manga card starts reading it, the
        // way every manga shelf behaves; details stay one button away.
        item.setOnClickListener {
            if (project.outputPageCount > 0 && rootUri != null) {
                startActivity(MangaReaderActivity.intent(this, rootUri, project))
            } else {
                startActivity(MainActivity.projectIntent(this, project.metadata.projectId))
            }
        }
        if (project.outputPageCount > 0 && rootUri != null) {
            primary.setText(
                if ((summary.readingProgress?.pageIndex ?: 0) > 0) {
                    R.string.library_continue_reading
                } else {
                    R.string.library_read
                },
            )
            primary.setOnClickListener {
                startActivity(MangaReaderActivity.intent(this, rootUri, project))
            }
        } else {
            primary.setText(
                when {
                    summary.queueStatus == PipelineQueueStatus.ACTIVE ->
                        R.string.library_processing_queued
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
        secondary.setText(R.string.library_project_details)
        secondary.setOnClickListener {
            startActivity(MainActivity.projectIntent(this, project.metadata.projectId))
        }
        return item
    }

    private fun statusText(summary: LibraryProjectSummary): String = when {
        summary.project.outputPageCount > 0 && summary.readingProgress != null ->
            getString(
                R.string.library_project_status_reading,
                summary.project.outputPageCount,
                summary.readingProgress.pageIndex + 1,
            )
        summary.project.outputPageCount > 0 ->
            getString(R.string.library_project_status_ready, summary.project.outputPageCount)
        summary.queueStatus == PipelineQueueStatus.ACTIVE ->
            getString(R.string.library_project_status_queued)
        summary.queueStatus == PipelineQueueStatus.PAUSED ->
            getString(R.string.library_project_status_paused)
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
        displayedCovers.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        displayedCovers.clear()
    }

    private data class LibraryProjectSummary(
        val project: MangaLibraryProject,
        val completedStages: Int,
        val cover: Bitmap?,
        val readingProgress: MangaReadingProgress?,
        val queueStatus: PipelineQueueStatus?,
    )

    private companion object {
        const val REQUEST_LIBRARY_FOLDER = 2101
        const val TAB_FINISHED = 0
        const val TAB_PROCESSING = 1
        const val STATE_SELECTED_TAB = "library_selected_tab"
    }
}
