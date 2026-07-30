package rs.masumi.app.typesetting

import rs.masumi.app.PageImageEncoder
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationResultState
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingDependencies
import rs.masumi.core.typesetting.TypesettingError
import rs.masumi.core.typesetting.TypesettingIdentity
import rs.masumi.core.typesetting.TypesettingJobPage
import rs.masumi.core.typesetting.TypesettingJobRecord
import rs.masumi.core.typesetting.TypesettingJobReducer
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPageState
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingPreserveReason
import rs.masumi.core.typesetting.TypesettingRegionArtifact
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRepairPageAction
import rs.masumi.core.typesetting.TypesettingRepairPlanner
import rs.masumi.core.typesetting.TypesettingRunArtifact
import rs.masumi.core.typesetting.TypesettingRunEntry
import rs.masumi.core.typesetting.TypesettingStyle
import rs.masumi.core.typesetting.isSuccessful

data class TypesettingProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: TypesettingJobStatus,
    val terminalPageCount: Int,
    val totalPageCount: Int,
    val typesetRegionCount: Int,
    val preservedRegionCount: Int,
    val currentPageOrder: Int? = null,
    val errorCode: String? = null,
)

data class TypesettingRunResult(
    val job: TypesettingJobRecord,
    val runArtifact: TypesettingRunArtifact? = null,
    val report: TypesettingReport? = null,
    val publishedDirectory: Path? = null,
)

class TypesettingRunner(
    workspaceRoot: Path,
    private val decoder: PageBitmapDecoder = PageBitmapDecoder(),
    private val renderer: ChineseTypesetter = ChineseTypesetter(),
    private val policy: TypesettingPolicy = TypesettingPolicy(),
    private val reuseRunArtifactKey: String? = null,
    private val reprocessPageOrders: Set<Int> = emptySet(),
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
        onProgress: (TypesettingProgress) -> Unit,
    ): TypesettingRunResult {
        externallyCancelled.set(false)
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val cleanupRun = requireNotNull(
            catalog.latestPublishedCleanupRun(
                projectId = projectId,
                policy = rs.masumi.core.cleanup.CleanupPolicy(),
                maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
            ),
        ) { "completed cleanup run was not found" }
        val reuseRun = reuseRunArtifactKey?.let { runKey ->
            requireNotNull(catalog.publishedTypesettingRun(projectId, runKey)) {
                "typesetting reuse run was not found"
            }
        }
        val translationRun = requireNotNull(
            catalog.publishedTranslationRun(
                projectId,
                cleanupRun.artifact.dependencies.translationRunArtifactKey,
            ),
        ) { "cleanup translation dependency was not found" }
        val ocrRun = requireNotNull(
            catalog.publishedOcrRun(projectId, translationRun.artifact.dependencies.ocrRunArtifactKey),
        ) { "translation OCR dependency was not found" }
        validateDependencies(project, cleanupRun, translationRun, ocrRun)
        validateReuse(project, cleanupRun, reuseRun)
        val dependencies = TypesettingDependencies(
            cleanupRunArtifactKey = cleanupRun.artifact.runArtifactKey,
            policy = policy,
        )
        val pageKeys = project.manifest.pages.associate { page ->
            val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == page.order }
            page.order to TypesettingIdentity.pageArtifactKey(
                page.order,
                page.sourceSha256,
                cleanupEntry.pageArtifactKey,
                dependencies,
            )
        }
        val runKey = TypesettingIdentity.runArtifactKey(
            project.manifest.pages.map { it.order to pageKeys.getValue(it.order) },
            dependencies,
        )
        val store = TypesettingArtifactStore(project.directory)
        readPublishedResult(store, project, runKey, dependencies)?.let { cached ->
            onProgress(cached.job.toProgress())
            return cached
        }
        var job = recoverOrCreateJob(store, project, cleanupRun, pageKeys, runKey, dependencies)
        store.prepareRun(job)
        val pageArtifacts = mutableMapOf<Int, PageTypesettingArtifact>()

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        fun persist(updated: TypesettingJobRecord, currentPageOrder: Int? = null): TypesettingJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentPageOrder))
            return updated
        }

        try {
            job = persist(TypesettingJobReducer.start(job, clock.millis()))
            project.manifest.pages.sortedBy(PageRecord::order).forEach { sourcePage ->
                val checkpoint = job.pages.single { it.pageOrder == sourcePage.order }
                when (checkpoint.state) {
                    TypesettingPageState.COMMITTED -> {
                        pageArtifacts[sourcePage.order] = store.readCommittedPage(job, checkpoint)
                            ?: throw FatalTypesettingException("COMMITTED_PAGE_INVALID")
                        return@forEach
                    }
                    TypesettingPageState.PRESERVED_CLEANED_PAGE -> return@forEach
                    TypesettingPageState.PENDING -> Unit
                    TypesettingPageState.RUNNING -> throw FatalTypesettingException("JOB_RECOVERY_INVALID")
                }
                if (isCancelled()) throw TypesettingCancellationSignal()
                job = persist(
                    TypesettingJobReducer.startPage(job, sourcePage.order, clock.millis()),
                    sourcePage.order,
                )
                val reuseEntry = reuseRun?.artifact?.entries?.single { it.pageOrder == sourcePage.order }
                val repairAction = TypesettingRepairPlanner.pageAction(
                    reuseEntry?.state,
                    sourcePage.order in reprocessPageOrders,
                )
                if (repairAction == TypesettingRepairPageAction.CARRY_PRESERVED) {
                    job = persist(
                        TypesettingJobReducer.preservePage(
                            job,
                            sourcePage.order,
                            requireNotNull(requireNotNull(reuseEntry).error),
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                    return@forEach
                }
                val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == sourcePage.order }
                if (cleanupEntry.state != CleanupPageState.COMMITTED) {
                    job = persist(
                        TypesettingJobReducer.preservePage(
                            job,
                            sourcePage.order,
                            TypesettingError("CLEANUP_PAGE_PRESERVED"),
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                    return@forEach
                }
                try {
                    val artifactAndPng = if (repairAction == TypesettingRepairPageAction.REUSE_COMMITTED) {
                        reusePage(
                            project,
                            sourcePage,
                            requireNotNull(reuseRun),
                            pageKeys.getValue(sourcePage.order),
                            dependencies,
                        )
                    } else {
                        processPage(
                            project,
                            sourcePage,
                            cleanupRun,
                            translationRun,
                            ocrRun,
                            pageKeys.getValue(sourcePage.order),
                            dependencies,
                            ::isCancelled,
                        )
                    }
                    val artifact = artifactAndPng.first
                    val (artifactPath, imagePath) = store.commitPage(
                        job,
                        artifact,
                        artifactAndPng.second,
                        PageImageEncoder.preferredExtension,
                    )
                    val typeset = artifact.regions.count { it.state == TypesettingRegionState.TYPESET }
                    val preserved = artifact.regions.size - typeset
                    job = persist(
                        TypesettingJobReducer.commitPage(
                            job,
                            sourcePage.order,
                            artifactPath,
                            imagePath,
                            typeset,
                            preserved,
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                    pageArtifacts[sourcePage.order] = artifact
                } catch (failure: Throwable) {
                    if (failure is TypesettingCancellationSignal || isCancelled()) throw TypesettingCancellationSignal()
                    job = persist(
                        TypesettingJobReducer.preservePage(
                            job,
                            sourcePage.order,
                            TypesettingError(failure.safePageErrorCode()),
                            clock.millis(),
                        ),
                        sourcePage.order,
                    )
                }
            }
            val finishedAt = clock.millis()
            job = TypesettingJobReducer.finishSuccess(job, finishedAt)
            val run = job.toRunArtifact(finishedAt)
            val report = job.toReport(finishedAt, pageArtifacts)
            val published = store.publishRun(job, run, report)
            job = persist(job)
            return TypesettingRunResult(job, run, report, published)
        } catch (_: TypesettingCancellationSignal) {
            if (job.status == TypesettingJobStatus.RUNNING) {
                if (!job.cancelRequested) job = persist(TypesettingJobReducer.requestCancellation(job, clock.millis()))
                job = persist(TypesettingJobReducer.finishCancellation(job, clock.millis()))
            }
            return TypesettingRunResult(job)
        } catch (failure: Throwable) {
            val error = TypesettingError(failure.safeFatalErrorCode())
            if (job.status == TypesettingJobStatus.RUNNING || job.status == TypesettingJobStatus.QUEUED) {
                job = TypesettingJobReducer.fail(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return TypesettingRunResult(job)
        }
    }

    private fun processPage(
        project: ProjectRef,
        sourcePage: PageRecord,
        cleanupRun: PublishedCleanupRun,
        translationRun: PublishedTranslationRun,
        ocrRun: PublishedOcrRun,
        pageKey: String,
        dependencies: TypesettingDependencies,
        cancellation: () -> Boolean,
    ): Pair<PageTypesettingArtifact, ByteArray> {
        validateSource(project.directory, sourcePage)
        val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        val cleanupPage = CleanupArtifactStore(project.directory).readPublishedPage(
            cleanupRun.artifact.runArtifactKey,
            cleanupEntry,
        ) ?: throw FatalTypesettingException("CLEANUP_PAGE_INVALID")
        val translationEntry = translationRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        val translationPage = TranslationArtifactStore(project.directory).readPublishedPage(
            translationRun.artifact.runArtifactKey,
            translationEntry,
        ) ?: throw FatalTypesettingException("TRANSLATION_PAGE_INVALID")
        val ocrEntry = ocrRun.artifact.entries.single { it.order == sourcePage.order }
        val ocrPage = if (ocrEntry.state == OcrPageState.COMMITTED) {
            catalog.readPublishedOcrPage(ocrRun, sourcePage.pageId)
                ?: throw FatalTypesettingException("OCR_PAGE_INVALID")
        } else {
            null
        }
        val ocrById = ocrPage?.regions.orEmpty().associateBy { it.candidate.ocrRegionId }
        val cleanupByTranslationId = cleanupPage.regions
            .filter { it.translationRegionId != null }
            .associateBy { requireNotNull(it.translationRegionId) }
        val targets = translationPage.items.mapNotNull { item ->
            if (item.state != TranslationResultState.TRANSLATED || item.translatedText.isNullOrBlank()) {
                return@mapNotNull null
            }
            val cleaned = cleanupByTranslationId[item.translationRegionId]
            if (cleaned?.state != CleanupRegionState.CLEANED) return@mapNotNull null
            val ocrRegion = ocrById[item.ocrRegionId]
                ?: throw FatalTypesettingException("OCR_REGION_INVALID")
            val inBubble = ocrRegion.candidate.sourceClass == DetectorClass.TEXT_IN_BUBBLE ||
                ocrRegion.candidate.associatedBubbleBox != null
            TypesettingTarget(
                translationRegionId = item.translationRegionId,
                ocrRegionId = item.ocrRegionId,
                translatedText = requireNotNull(item.translatedText),
                textBox = ocrRegion.candidate.box,
                bubbleBox = ocrRegion.candidate.associatedBubbleBox,
                style = if (inBubble) TypesettingStyle.BUBBLE else TypesettingStyle.FREE_TEXT,
            )
        }
        val cleanedImage = resolveInside(cleanupRun.directory, requireNotNull(cleanupEntry.imagePath))
        val decoded = decoder.decode(cleanedImage)
        try {
            require(decoded.bitmap.width == cleanupPage.visibleWidth && decoded.bitmap.height == cleanupPage.visibleHeight)
            val rendered = renderer.render(decoded.bitmap, targets, policy, cancellation)
            try {
                val renderedByTranslationId = rendered.regions.associateBy { requireNotNull(it.translationRegionId) }
                val outcomes = translationPage.items.map { item ->
                    when {
                        item.state != TranslationResultState.TRANSLATED -> item.preserved(
                            ocrById[item.ocrRegionId]?.candidate?.box ?: EMPTY_BOX,
                            TypesettingPreserveReason.TRANSLATION_PRESERVED,
                        )
                        cleanupByTranslationId[item.translationRegionId]?.state != CleanupRegionState.CLEANED ->
                            item.preserved(
                                ocrById[item.ocrRegionId]?.candidate?.box ?: EMPTY_BOX,
                                TypesettingPreserveReason.CLEANUP_NOT_CLEANED,
                            )
                        else -> renderedByTranslationId[item.translationRegionId]
                            ?: throw FatalTypesettingException("TYPESETTING_OUTCOME_MISSING")
                    }
                } + translationPage.protectedOcrRegions.map { protected ->
                    TypesettingRegionArtifact(
                        ocrRegionId = protected.ocrRegionId,
                        targetBox = ocrById[protected.ocrRegionId]?.candidate?.box ?: EMPTY_BOX,
                        state = TypesettingRegionState.PRESERVED_CLEANED_PAGE,
                        preserveReason = TypesettingPreserveReason.OCR_PROTECTED,
                    )
                }
                val png = PageImageEncoder.encode(rendered.bitmap)
                return PageTypesettingArtifact(
                    pageId = sourcePage.pageId,
                    pageOrder = sourcePage.order,
                    sourceSha256 = sourcePage.sourceSha256,
                    cleanupPageArtifactKey = cleanupEntry.pageArtifactKey,
                    pageArtifactKey = pageKey,
                    visibleWidth = rendered.bitmap.width,
                    visibleHeight = rendered.bitmap.height,
                    renderedImageSha256 = sha256(png),
                    dependencies = dependencies,
                    regions = outcomes,
                ) to png
            } finally {
                rendered.bitmap.recycle()
            }
        } finally {
            decoded.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun reusePage(
        project: ProjectRef,
        sourcePage: PageRecord,
        reuseRun: PublishedTypesettingRun,
        pageKey: String,
        dependencies: TypesettingDependencies,
    ): Pair<PageTypesettingArtifact, ByteArray> {
        val entry = reuseRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        require(entry.state == TypesettingPageState.COMMITTED)
        val artifact = TypesettingArtifactStore(project.directory).readPublishedPage(
            reuseRun.artifact.runArtifactKey,
            entry,
        ) ?: throw FatalTypesettingException("REUSE_PAGE_INVALID")
        val imagePath = resolveInside(reuseRun.directory, requireNotNull(entry.imagePath))
        val png = Files.readAllBytes(imagePath)
        if (sha256(png) != artifact.renderedImageSha256) {
            throw FatalTypesettingException("REUSE_IMAGE_INVALID")
        }
        return artifact.copy(
            pageArtifactKey = pageKey,
            reusedFromPageArtifactKey = artifact.pageArtifactKey,
            dependencies = dependencies,
        ) to png
    }

    private fun recoverOrCreateJob(
        store: TypesettingArtifactStore,
        project: ProjectRef,
        cleanupRun: PublishedCleanupRun,
        pageKeys: Map<Int, String>,
        runKey: String,
        dependencies: TypesettingDependencies,
    ): TypesettingJobRecord {
        val candidate = store.findResumableJob()?.takeIf {
            it.projectId == project.manifest.projectId && it.runArtifactKey == runKey && it.dependencies == dependencies
        }
        if (candidate != null) {
            candidate.pages.filter { it.state == TypesettingPageState.RUNNING }.forEach {
                store.cleanInterruptedPage(candidate, it.pageOrder)
            }
            val recovered = when (candidate.status) {
                TypesettingJobStatus.QUEUED -> candidate
                TypesettingJobStatus.RUNNING,
                TypesettingJobStatus.CANCELLED,
                -> TypesettingJobReducer.recoverInterrupted(candidate, clock.millis())
                else -> error("terminal typesetting job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }
        val now = clock.millis()
        return TypesettingJobRecord(
            jobId = idSource.nextId(),
            projectId = project.manifest.projectId,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            pages = project.manifest.pages.map { page ->
                val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == page.order }
                TypesettingJobPage(
                    pageId = page.pageId,
                    pageOrder = page.order,
                    sourceSha256 = page.sourceSha256,
                    cleanupPageArtifactKey = cleanupEntry.pageArtifactKey,
                    pageArtifactKey = pageKeys.getValue(page.order),
                )
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: TypesettingArtifactStore,
        project: ProjectRef,
        runKey: String,
        dependencies: TypesettingDependencies,
    ): TypesettingRunResult? {
        val run = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(run.projectId == project.manifest.projectId && run.dependencies == dependencies)
        val job = store.readJob(report.jobId) ?: return null
        require(job.status == report.status && job.status.isSuccessful())
        return TypesettingRunResult(
            job,
            run,
            report,
            project.directory.resolve("artifacts/typesetting/$runKey"),
        )
    }

    private fun validateDependencies(
        project: ProjectRef,
        cleanup: PublishedCleanupRun,
        translation: PublishedTranslationRun,
        ocr: PublishedOcrRun,
    ) {
        require(cleanup.artifact.projectId == project.manifest.projectId)
        require(cleanup.artifact.dependencies.translationRunArtifactKey == translation.artifact.runArtifactKey)
        require(translation.artifact.dependencies.ocrRunArtifactKey == ocr.artifact.runArtifactKey)
        require(cleanup.artifact.entries.size == project.manifest.pages.size)
        require(translation.artifact.entries.size == project.manifest.pages.size)
        require(ocr.artifact.entries.size == project.manifest.pages.size)
        project.manifest.pages.forEach { page ->
            require(cleanup.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
            require(translation.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
            require(ocr.artifact.entries.single { it.order == page.order }.pageId == page.pageId)
        }
    }

    private fun validateReuse(
        project: ProjectRef,
        cleanup: PublishedCleanupRun,
        reuse: PublishedTypesettingRun?,
    ) {
        if (reuse == null) {
            require(reprocessPageOrders.isEmpty())
            return
        }
        require(reprocessPageOrders.isNotEmpty())
        require(reuse.artifact.projectId == project.manifest.projectId)
        require(reuse.artifact.dependencies.cleanupRunArtifactKey == cleanup.artifact.runArtifactKey)
        require(reuse.artifact.entries.size == project.manifest.pages.size)
        require(reprocessPageOrders.all { it in project.manifest.pages.indices })
        project.manifest.pages.forEach { page ->
            require(reuse.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
        }
    }

    private fun validateSource(projectDirectory: Path, page: PageRecord) {
        val path = resolveInside(projectDirectory, page.storedPath)
        if (!Files.isRegularFile(path)) throw FatalTypesettingException("SOURCE_MISSING")
        if (Files.size(path) != page.byteLength) throw FatalTypesettingException("SOURCE_LENGTH_MISMATCH")
        if (sha256(path) != page.sourceSha256) throw FatalTypesettingException("SOURCE_HASH_MISMATCH")
    }

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/'))
        return root.resolve(relative).normalize().also { require(it.startsWith(root.normalize())) }
    }

    private fun rs.masumi.core.translation.ValidatedTranslationItem.preserved(
        box: PixelBox,
        reason: TypesettingPreserveReason,
    ): TypesettingRegionArtifact = TypesettingRegionArtifact(
        translationRegionId = translationRegionId,
        ocrRegionId = ocrRegionId,
        targetBox = box,
        state = TypesettingRegionState.PRESERVED_CLEANED_PAGE,
        preserveReason = reason,
    )

    private fun TypesettingJobRecord.toRunArtifact(createdAt: Long): TypesettingRunArtifact = TypesettingRunArtifact(
        runArtifactKey = runArtifactKey,
        projectId = projectId,
        createdAtEpochMillis = createdAt,
        dependencies = dependencies,
        entries = pages.sortedBy(TypesettingJobPage::pageOrder).map { page ->
            TypesettingRunEntry(
                pageId = page.pageId,
                pageOrder = page.pageOrder,
                sourceSha256 = page.sourceSha256,
                cleanupPageArtifactKey = page.cleanupPageArtifactKey,
                pageArtifactKey = page.pageArtifactKey,
                state = page.state,
                artifactPath = page.artifactPath,
                imagePath = page.imagePath,
                error = page.error,
            )
        },
    )

    private fun TypesettingJobRecord.toReport(
        finishedAt: Long,
        artifacts: Map<Int, PageTypesettingArtifact>,
    ): TypesettingReport = TypesettingReport(
        jobId = jobId,
        projectId = projectId,
        runArtifactKey = runArtifactKey,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAt,
        status = status,
        totalPageCount = pages.size,
        committedPageCount = pages.count { it.state == TypesettingPageState.COMMITTED },
        preservedPageCount = pages.count { it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE },
        typesetRegionCount = pages.sumOf(TypesettingJobPage::typesetRegionCount),
        preservedRegionCount = pages.sumOf(TypesettingJobPage::preservedRegionCount),
        changedPixelCount = artifacts.values.flatMap(PageTypesettingArtifact::regions)
            .sumOf { it.changedPixelCount.toLong() },
        retryCount = pages.sumOf { (it.attemptCount - 1).coerceAtLeast(0) },
        reusedPageCount = artifacts.values.count { it.reusedFromPageArtifactKey != null },
    )

    private fun TypesettingJobRecord.toProgress(
        currentPageOrder: Int? = pages.firstOrNull { it.state == TypesettingPageState.RUNNING }?.pageOrder,
        errorCode: String? = error?.code,
    ): TypesettingProgress = TypesettingProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        terminalPageCount = pages.count {
            it.state == TypesettingPageState.COMMITTED || it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE
        },
        totalPageCount = pages.size,
        typesetRegionCount = pages.sumOf(TypesettingJobPage::typesetRegionCount),
        preservedRegionCount = pages.sumOf(TypesettingJobPage::preservedRegionCount),
        currentPageOrder = currentPageOrder,
        errorCode = errorCode,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.safePageErrorCode(): String = when (this) {
        is FatalTypesettingException -> code
        is IllegalArgumentException -> "PAGE_INPUT_INVALID"
        else -> "PAGE_TYPESETTING_FAILED"
    }

    private fun Throwable.safeFatalErrorCode(): String = when (this) {
        is FatalTypesettingException -> code
        is IllegalArgumentException -> "PROJECT_INVALID"
        else -> "TYPESETTING_RUN_FAILED"
    }

    private class FatalTypesettingException(val code: String) : RuntimeException(code)

    private companion object {
        val EMPTY_BOX = PixelBox(0.0, 0.0, 0.0, 0.0)
    }
}
