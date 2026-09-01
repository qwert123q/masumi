package rs.masumi.app.cleanup

import rs.masumi.app.PageImageEncoder
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.pipeline.PipelineArtifactFreshness
import rs.masumi.app.pipeline.SourceFilePreflight
import rs.masumi.app.pipeline.SourceFilePreflightException
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CLEANUP_SCHEMA_VERSION
import rs.masumi.core.cleanup.CleanupDependencies
import rs.masumi.core.cleanup.CleanupError
import rs.masumi.core.cleanup.CleanupIdentity
import rs.masumi.core.cleanup.CleanupJobPage
import rs.masumi.core.cleanup.CleanupJobRecord
import rs.masumi.core.cleanup.CleanupJobReducer
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupMaskModelRef
import rs.masumi.core.cleanup.CleanupNeuralModelRef
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupPreserveReason
import rs.masumi.core.cleanup.CleanupRegionArtifact
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.cleanup.PageCleanupArtifact
import rs.masumi.core.cleanup.isSuccessful
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.translation.PageTranslationArtifact
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationResultState

data class CleanupProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: CleanupJobStatus,
    val terminalPageCount: Int,
    val totalPageCount: Int,
    val cleanedRegionCount: Int,
    val preservedRegionCount: Int,
    val currentPageOrder: Int? = null,
    val errorCode: String? = null,
)

data class CleanupRunResult(
    val job: CleanupJobRecord,
    val runArtifact: CleanupRunArtifact? = null,
    val report: CleanupReport? = null,
    val publishedDirectory: Path? = null,
)

class CleanupRunner(
    workspaceRoot: Path,
    private val decoder: PageBitmapDecoder = PageBitmapDecoder(),
    private val engine: SourceCleanupEngine = SourceCleanupEngine(),
    private val policy: CleanupPolicy = CleanupPolicy(),
    private val maskModel: CleanupMaskModelRef? = null,
    private val neuralModel: CleanupNeuralModelRef? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
) {
    private val catalog = ProjectCatalog(workspaceRoot.toAbsolutePath().normalize())
    private val externallyCancelled = AtomicBoolean(false)

    fun cancel() {
        externallyCancelled.set(true)
    }

    fun run(
        projectId: String,
        cancellation: () -> Boolean,
        onProgress: (CleanupProgress) -> Unit,
    ): CleanupRunResult {
        externallyCancelled.set(false)
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val detectionRun = requireNotNull(catalog.publishedDetectionRuns(projectId).firstOrNull {
            PipelineArtifactFreshness.detection(it.artifact, project.manifest)
        }) {
            "completed detection run was not found"
        }
        val currentOcrRun = requireNotNull(
            catalog.publishedOcrRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.ocr(it.artifact, detectionRun.artifact)
            },
        ) { "completed OCR run was not found" }
        val translationRun = requireNotNull(
            catalog.publishedTranslationRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.translation(it.artifact, currentOcrRun.artifact)
            },
        ) {
            "completed translation run was not found"
        }
        val ocrRun = requireNotNull(
            catalog.publishedOcrRun(projectId, translationRun.artifact.dependencies.ocrRunArtifactKey),
        ) { "translation OCR dependency was not found" }
        validateDependencies(project, translationRun, ocrRun)
        val dependencies = CleanupDependencies(
            translationRunArtifactKey = translationRun.artifact.runArtifactKey,
            policy = policy,
            maskModel = maskModel,
            neuralModel = neuralModel,
        )
        val store = CleanupArtifactStore(project.directory)
        val reusable = catalog.latestPublishedCleanupRun(
            projectId = projectId,
            translationRunArtifactKey = translationRun.artifact.runArtifactKey,
            policy = policy,
            maskModel = maskModel,
            neuralModel = neuralModel,
        )?.takeIf { published ->
            published.artifact.schemaVersion == CLEANUP_SCHEMA_VERSION &&
                published.artifact.entries.map { it.pageOrder to it.pageId to it.translationPageArtifactKey } ==
                project.manifest.pages.map { page ->
                    val upstream = translationRun.artifact.entries.single { it.pageOrder == page.order }
                    page.order to page.pageId to upstream.pageArtifactKey
                }
        }
        reusable?.let { published ->
            val cached = requireNotNull(readPublishedResult(store, project, published.artifact.runArtifactKey, dependencies))
            onProgress(cached.job.toProgress())
            return cached
        }
        var job = recoverOrCreateJob(store, project, translationRun, dependencies)
        val pageKeys = job.pages.associate { it.pageOrder to it.pageArtifactKey }
        store.prepareRun(job)
        val pageArtifacts = mutableMapOf<Int, PageCleanupArtifact>()

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        fun persist(updated: CleanupJobRecord, currentPageOrder: Int? = null): CleanupJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentPageOrder))
            return updated
        }

        try {
            job = persist(CleanupJobReducer.start(job, clock.millis()))
            project.manifest.pages.sortedBy(PageRecord::order).forEach { sourcePage ->
                val checkpoint = job.pages.single { it.pageOrder == sourcePage.order }
                when (checkpoint.state) {
                    CleanupPageState.COMMITTED -> {
                        pageArtifacts[sourcePage.order] = store.readCommittedPage(job, checkpoint)
                            ?: throw FatalCleanupException("COMMITTED_PAGE_INVALID")
                        return@forEach
                    }
                    CleanupPageState.PRESERVED_SOURCE -> return@forEach
                    CleanupPageState.PENDING -> Unit
                    CleanupPageState.RUNNING -> throw FatalCleanupException("JOB_RECOVERY_INVALID")
                }
                if (isCancelled()) throw CleanupCancellationSignal()
                job = persist(
                    CleanupJobReducer.startPage(job, sourcePage.order, clock.millis()),
                    sourcePage.order,
                )
                try {
                    val artifactAndPng = processPage(
                        project,
                        sourcePage,
                        translationRun,
                        ocrRun,
                        pageKeys.getValue(sourcePage.order),
                        dependencies,
                        ::isCancelled,
                    )
                    val artifact = artifactAndPng.first
                    val (artifactPath, imagePath) = store.commitPage(
                        job,
                        artifact,
                        artifactAndPng.second,
                        PageImageEncoder.preferredExtension,
                    )
                    val cleaned = artifact.regions.count { it.state == CleanupRegionState.CLEANED }
                    val preserved = artifact.regions.size - cleaned
                    job = persist(
                        CleanupJobReducer.commitPage(
                            job,
                            sourcePage.order,
                            artifactPath,
                            imagePath,
                            artifactAndPng.second.size.toLong(),
                            cleaned,
                            preserved,
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                    pageArtifacts[sourcePage.order] = artifact
                } catch (failure: Throwable) {
                    if (failure is CleanupCancellationSignal || isCancelled()) throw CleanupCancellationSignal()
                    job = persist(
                        CleanupJobReducer.preservePage(
                            job,
                            sourcePage.order,
                            CleanupError(failure.safePageErrorCode()),
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                }
            }
            val finishedAt = clock.millis()
            job = CleanupJobReducer.finishSuccess(job, finishedAt)
            val run = job.toRunArtifact(finishedAt)
            val report = job.toReport(finishedAt, pageArtifacts)
            val published = store.publishRun(job, run, report)
            job = persist(job)
            return CleanupRunResult(job, run, report, published)
        } catch (_: CleanupCancellationSignal) {
            if (job.status == CleanupJobStatus.RUNNING) {
                if (!job.cancelRequested) job = persist(CleanupJobReducer.requestCancellation(job, clock.millis()))
                job = persist(CleanupJobReducer.finishCancellation(job, clock.millis()))
            }
            return CleanupRunResult(job)
        } catch (failure: Throwable) {
            val error = CleanupError(failure.safeFatalErrorCode())
            if (job.status == CleanupJobStatus.RUNNING || job.status == CleanupJobStatus.QUEUED) {
                job = CleanupJobReducer.fail(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return CleanupRunResult(job)
        }
    }

    private fun processPage(
        project: ProjectRef,
        sourcePage: PageRecord,
        translationRun: PublishedTranslationRun,
        ocrRun: PublishedOcrRun,
        pageKey: String,
        dependencies: CleanupDependencies,
        cancellation: () -> Boolean,
    ): Pair<PageCleanupArtifact, ByteArray> {
        val sourcePath = try {
            SourceFilePreflight.resolve(project.directory, sourcePage)
        } catch (failure: SourceFilePreflightException) {
            throw FatalCleanupException(failure.code)
        }
        val translationEntry = translationRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        val translationPage = TranslationArtifactStore(project.directory).readPublishedPage(
            translationRun.artifact.runArtifactKey,
            translationEntry,
        ) ?: throw FatalCleanupException("TRANSLATION_PAGE_INVALID")
        val ocrEntry = ocrRun.artifact.entries.single { it.order == sourcePage.order }
        val ocrPage = if (ocrEntry.state == OcrPageState.COMMITTED) {
            catalog.readPublishedOcrPage(ocrRun, sourcePage.pageId)
                ?: throw FatalCleanupException("OCR_PAGE_INVALID")
        } else {
            null
        }
        val regionsById = ocrPage?.regions.orEmpty().associateBy { it.candidate.ocrRegionId }
        val targets = translationPage.items.mapNotNull { item ->
            if (item.state != TranslationResultState.TRANSLATED) return@mapNotNull null
            val ocrRegion = regionsById[item.ocrRegionId]
                ?: throw FatalCleanupException("OCR_REGION_INVALID")
            CleanupTarget(
                translationRegionId = item.translationRegionId,
                ocrRegionId = item.ocrRegionId,
                box = ocrRegion.candidate.box,
                strategy = ocrRegion.cleanupStrategy(),
                expectedGlyphCount = ocrRegion.selectedText()
                    ?.count { !it.isWhitespace() && !it.isISOControl() }
                    ?.coerceAtLeast(1),
            )
        }
        val decoded = decoder.decode(sourcePath)
        try {
            if (ocrPage != null) {
                require(decoded.bitmap.width == ocrPage.visibleWidth && decoded.bitmap.height == ocrPage.visibleHeight)
                require(decoded.orientation == ocrPage.orientation)
            }
            val cleaned = engine.clean(
                decoded.bitmap,
                targets,
                policy,
                cancellation,
                recycleSourceAfterCopy = true,
            )
            try {
                val cleanedByTranslationId = cleaned.regions.associateBy { it.translationRegionId }
                val outcomes = translationPage.items.map { item ->
                    if (item.state == TranslationResultState.TRANSLATED) {
                        cleanedByTranslationId[item.translationRegionId]
                            ?: throw FatalCleanupException("CLEANUP_OUTCOME_MISSING")
                    } else {
                        val box = regionsById[item.ocrRegionId]?.candidate?.box ?: EMPTY_BOX
                        CleanupRegionArtifact(
                            translationRegionId = item.translationRegionId,
                            ocrRegionId = item.ocrRegionId,
                            box = box,
                            state = CleanupRegionState.PRESERVED_SOURCE,
                            preserveReason = CleanupPreserveReason.TRANSLATION_PRESERVED,
                        )
                    }
                } + translationPage.protectedOcrRegions.map { protected ->
                    CleanupRegionArtifact(
                        ocrRegionId = protected.ocrRegionId,
                        box = regionsById[protected.ocrRegionId]?.candidate?.box ?: EMPTY_BOX,
                        state = CleanupRegionState.PRESERVED_SOURCE,
                        preserveReason = CleanupPreserveReason.OCR_PROTECTED,
                    )
                }
                val png = PageImageEncoder.encode(cleaned.bitmap)
                return PageCleanupArtifact(
                    pageId = sourcePage.pageId,
                    pageOrder = sourcePage.order,
                    translationPageArtifactKey = translationEntry.pageArtifactKey,
                    pageArtifactKey = pageKey,
                    visibleWidth = cleaned.bitmap.width,
                    visibleHeight = cleaned.bitmap.height,
                    cleanedImageByteLength = png.size.toLong(),
                    dependencies = dependencies,
                    regions = outcomes,
                ) to png
            } finally {
                cleaned.bitmap.recycle()
            }
        } finally {
            decoded.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun recoverOrCreateJob(
        store: CleanupArtifactStore,
        project: ProjectRef,
        translationRun: PublishedTranslationRun,
        dependencies: CleanupDependencies,
    ): CleanupJobRecord {
        val candidate = store.findRecoveryCandidates().firstOrNull {
            it.schemaVersion == CLEANUP_SCHEMA_VERSION &&
                it.projectId == project.manifest.projectId &&
                it.dependencies == dependencies &&
                it.pages.map { page -> page.pageOrder to page.pageId to page.translationPageArtifactKey } ==
                project.manifest.pages.map { page ->
                    val upstream = translationRun.artifact.entries.single { it.pageOrder == page.order }
                    page.order to page.pageId to upstream.pageArtifactKey
                }
        }
        if (candidate != null) {
            candidate.pages.filter { it.state == CleanupPageState.RUNNING }.forEach {
                store.cleanInterruptedPage(candidate, it.pageOrder)
            }
            val recovered = when (candidate.status) {
                CleanupJobStatus.QUEUED -> candidate
                CleanupJobStatus.RUNNING,
                CleanupJobStatus.CANCELLED,
                -> CleanupJobReducer.recoverInterrupted(candidate, clock.millis())
                else -> error("terminal cleanup job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }
        val now = clock.millis()
        val runKey = idSource.nextId()
        return CleanupJobRecord(
            jobId = idSource.nextId(),
            projectId = project.manifest.projectId,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            pages = project.manifest.pages.map { page ->
                val translationEntry = translationRun.artifact.entries.single { it.pageOrder == page.order }
                CleanupJobPage(
                    pageId = page.pageId,
                    pageOrder = page.order,
                    translationPageArtifactKey = translationEntry.pageArtifactKey,
                    pageArtifactKey = CleanupIdentity.pageArtifactKey(runKey, page.order),
                )
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: CleanupArtifactStore,
        project: ProjectRef,
        runKey: String,
        dependencies: CleanupDependencies,
    ): CleanupRunResult? {
        val run = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(run.projectId == project.manifest.projectId && run.dependencies == dependencies)
        val job = store.readJob(report.jobId) ?: return null
        require(job.status == report.status && job.status.isSuccessful())
        return CleanupRunResult(
            job,
            run,
            report,
            project.directory.resolve("artifacts/cleanup/$runKey"),
        )
    }

    private fun validateDependencies(
        project: ProjectRef,
        translation: PublishedTranslationRun,
        ocr: PublishedOcrRun,
    ) {
        require(translation.artifact.projectId == project.manifest.projectId)
        require(translation.artifact.dependencies.ocrRunArtifactKey == ocr.artifact.runArtifactKey)
        require(translation.artifact.entries.size == project.manifest.pages.size)
        require(ocr.artifact.entries.size == project.manifest.pages.size)
        project.manifest.pages.forEach { page ->
            require(translation.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
            require(ocr.artifact.entries.single { it.order == page.order }.pageId == page.pageId)
        }
    }

    private fun OcrRegionArtifact.cleanupStrategy(): CleanupStrategy =
        if (candidate.sourceClass == DetectorClass.TEXT_IN_BUBBLE) {
            CleanupStrategy.FLAT_LOCAL_FILL
        } else {
            CleanupStrategy.LOCAL_BOUNDARY_INPAINT
        }

    private fun OcrRegionArtifact.selectedText(): String? =
        selectedAttemptIndex?.let(attempts::getOrNull)?.normalizedText

    private fun CleanupJobRecord.toRunArtifact(createdAt: Long): CleanupRunArtifact = CleanupRunArtifact(
        runArtifactKey = runArtifactKey,
        projectId = projectId,
        createdAtEpochMillis = createdAt,
        dependencies = dependencies,
        entries = pages.sortedBy(CleanupJobPage::pageOrder).map { page ->
            CleanupRunEntry(
                pageId = page.pageId,
                pageOrder = page.pageOrder,
                translationPageArtifactKey = page.translationPageArtifactKey,
                pageArtifactKey = page.pageArtifactKey,
                state = page.state,
                artifactPath = page.artifactPath,
                imagePath = page.imagePath,
                imageByteLength = page.imageByteLength,
                error = page.error,
            )
        },
    )

    private fun CleanupJobRecord.toReport(
        finishedAt: Long,
        artifacts: Map<Int, PageCleanupArtifact>,
    ): CleanupReport = CleanupReport(
        jobId = jobId,
        projectId = projectId,
        runArtifactKey = runArtifactKey,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAt,
        status = status,
        totalPageCount = pages.size,
        committedPageCount = pages.count { it.state == CleanupPageState.COMMITTED },
        preservedPageCount = pages.count { it.state == CleanupPageState.PRESERVED_SOURCE },
        cleanedRegionCount = pages.sumOf(CleanupJobPage::cleanedRegionCount),
        preservedRegionCount = pages.sumOf(CleanupJobPage::preservedRegionCount),
        changedPixelCount = artifacts.values.flatMap(PageCleanupArtifact::regions)
            .sumOf { it.changedPixelCount.toLong() },
        retryCount = pages.sumOf { (it.attemptCount - 1).coerceAtLeast(0) },
    )

    private fun CleanupJobRecord.toProgress(
        currentPageOrder: Int? = pages.firstOrNull { it.state == CleanupPageState.RUNNING }?.pageOrder,
        errorCode: String? = error?.code,
    ): CleanupProgress = CleanupProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        terminalPageCount = pages.count {
            it.state == CleanupPageState.COMMITTED || it.state == CleanupPageState.PRESERVED_SOURCE
        },
        totalPageCount = pages.size,
        cleanedRegionCount = pages.sumOf(CleanupJobPage::cleanedRegionCount),
        preservedRegionCount = pages.sumOf(CleanupJobPage::preservedRegionCount),
        currentPageOrder = currentPageOrder,
        errorCode = errorCode,
    )

    private fun Throwable.safePageErrorCode(): String = when (this) {
        is FatalCleanupException -> code
        is OutOfMemoryError -> "PAGE_OUT_OF_MEMORY"
        is IllegalArgumentException -> "PAGE_INPUT_INVALID"
        else -> "PAGE_CLEANUP_FAILED"
    }

    private fun Throwable.safeFatalErrorCode(): String = when (this) {
        is FatalCleanupException -> code
        is IllegalArgumentException -> "PROJECT_INVALID"
        else -> "CLEANUP_RUN_FAILED"
    }

    private class FatalCleanupException(val code: String) : RuntimeException(code)

    private companion object {
        val EMPTY_BOX = PixelBox(0.0, 0.0, 0.0, 0.0)
    }
}
