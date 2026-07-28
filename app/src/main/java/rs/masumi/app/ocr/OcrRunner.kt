package rs.masumi.app.ocr

import android.graphics.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.app.detection.PageDecodeException
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.OcrModelPackageException
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrAttemptArtifact
import rs.masumi.core.ocr.OcrCandidate
import rs.masumi.core.ocr.OcrCandidateConsolidator
import rs.masumi.core.ocr.OcrCropDescriptor
import rs.masumi.core.ocr.OcrCropPolicy
import rs.masumi.core.ocr.OcrDependencies
import rs.masumi.core.ocr.OcrError
import rs.masumi.core.ocr.OcrExecutionBackend
import rs.masumi.core.ocr.OcrGenerationConfig
import rs.masumi.core.ocr.OcrIdentity
import rs.masumi.core.ocr.OcrJobPage
import rs.masumi.core.ocr.OcrJobRecord
import rs.masumi.core.ocr.OcrJobReducer
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrQualityEvaluator
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionCheckpoint
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.app.pipeline.PipelineThreading
import rs.masumi.core.ocr.isSuccessful
import rs.masumi.core.ocr.isTerminal

data class OcrProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: OcrJobStatus,
    val terminalRegionCount: Int,
    val totalRegionCount: Int,
    val committedPageCount: Int,
    val totalPageCount: Int,
    val currentOrder: Int? = null,
    val currentPageId: String? = null,
    val currentRegionId: String? = null,
    val downloadedBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val errorCode: String? = null,
)

data class OcrRunResult(
    val job: OcrJobRecord,
    val runArtifact: OcrRunArtifact? = null,
    val report: OcrReport? = null,
    val publishedDirectory: Path? = null,
)

class OcrRunner(
    workspaceRoot: Path,
    private val modelProvider: OcrModelProvider,
    private val engineFactory: OcrEngineFactory,
    private val decoder: PageBitmapDecoder,
    private val cropRenderer: OcrCropRenderer,
    private val previewRenderer: OcrPreviewRenderer,
    private val dependencies: OcrDependencies = defaultOcrDependencies(),
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
    private val secondaryEngineFactory: OcrEngineFactory? = null,
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val catalog = ProjectCatalog(this.workspaceRoot)
    private val consolidator = OcrCandidateConsolidator(dependencies.consolidation, dependencies.readingOrder)
    private val cropPolicy = OcrCropPolicy(dependencies.crop)
    private val qualityEvaluator = OcrQualityEvaluator(dependencies.quality)
    private val activeEngine = AtomicReference<OcrEngine?>()
    private val activeSecondaryEngine = AtomicReference<OcrEngine?>()
    private val activeStore = AtomicReference<OcrArtifactStore?>()
    private val activeJob = AtomicReference<OcrJobRecord?>()
    private val stateWriteLock = ReentrantLock()
    private val externallyCancelled = AtomicBoolean(false)

    fun cancel(): OcrProgress? {
        externallyCancelled.set(true)
        val progress = stateWriteLock.withLock {
            val store = activeStore.get() ?: return@withLock null
            val job = activeJob.get() ?: return@withLock null
            if (!job.status.isNonTerminal()) return@withLock job.toProgress()
            val requested = if (job.cancelRequested) {
                job
            } else {
                OcrJobReducer.requestCancel(job, clock.millis())
            }
            val cancelled = OcrJobReducer.finishCancellation(requested, clock.millis())
            store.writeJob(cancelled)
            activeJob.set(cancelled)
            cancelled.toProgress()
        }
        activeEngine.get()?.cancel()
        activeSecondaryEngine.get()?.cancel()
        return progress
    }

    fun run(
        projectId: String,
        cancellation: () -> Boolean,
        onProgress: (OcrProgress) -> Unit,
    ): OcrRunResult {
        externallyCancelled.set(false)
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val detectionRun = requireNotNull(catalog.latestPublishedRun(projectId)) {
            "completed detection run was not found"
        }
        validateDetectionDependency(project.manifest, detectionRun)
        val detectionPages = loadDetectionPages(project.manifest, detectionRun)
        val candidates = detectionPages.mapValues { (_, page) -> consolidator.consolidate(page) }
        val pageKeys = project.manifest.pages.associate { page ->
            val detectionKey = detectionEntry(detectionRun, page.order).pageArtifactKey
            page.pageId to OcrIdentity.pageArtifactKey(page.sourceSha256, detectionKey, dependencies)
        }
        val runKey = OcrIdentity.runArtifactKey(
            project.manifest.pages.map { page -> page.order to pageKeys.getValue(page.pageId) },
        )
        val store = OcrArtifactStore(project.directory)
        readPublishedResult(store, project, detectionRun, runKey)?.let { cached ->
            onProgress(cached.job.toProgress())
            return cached
        }

        var job = recoverOrCreateJob(
            store = store,
            manifest = project.manifest,
            detectionRun = detectionRun,
            candidates = candidates,
            pageKeys = pageKeys,
            runKey = runKey,
        )
        activeStore.set(store)
        activeJob.set(job)
        store.prepareRun(job)
        val pageArtifacts = mutableMapOf<String, rs.masumi.core.ocr.PageOcrArtifact>()

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()

        fun persist(
            updated: OcrJobRecord,
            currentOrder: Int? = null,
            currentPageId: String? = null,
            currentRegionId: String? = null,
        ): OcrJobRecord {
            val persisted = stateWriteLock.withLock {
                val current = activeJob.get()
                if (current?.jobId == updated.jobId && !current.status.isNonTerminal() &&
                    updated.status.isNonTerminal()
                ) {
                    current
                } else {
                    store.writeJob(updated)
                    activeJob.set(updated)
                    updated
                }
            }
            onProgress(
                persisted.toProgress(
                    currentOrder = currentOrder,
                    currentPageId = currentPageId,
                    currentRegionId = currentRegionId,
                ),
            )
            return persisted
        }

        try {
            validateSources(project.directory, project.manifest)
            if (isCancelled()) throw OcrCancellationSignal()

            job = persist(OcrJobReducer.startModelDownload(job, clock.millis()))
            val installed = modelProvider.acquire(job.jobId) { downloaded, total ->
                if (isCancelled()) throw OcrCancellationSignal()
                onProgress(job.toProgress(downloadedBytes = downloaded, totalDownloadBytes = total))
            }
            job = persist(OcrJobReducer.startModelLoad(job, clock.millis()))
            if (installed.metadata.modelPackage != dependencies.modelPackage ||
                installed.metadata.runtime != dependencies.runtime ||
                installed.metadata.prompt != dependencies.generation.prompt
            ) {
                throw FatalOcrException("MODEL_PACKAGE_DEPENDENCY_MISMATCH")
            }
            val engine = engineFactory.open(installed.model, installed.projector)
            activeEngine.set(engine)
            // A second engine (typically CPU-only) roughly halves per-page OCR
            // wall time on big-core devices; opening it is best-effort.
            val secondaryEngine = secondaryEngineFactory?.let { factory ->
                runCatching { factory.open(installed.model, installed.projector) }.getOrNull()
            }
            activeSecondaryEngine.set(secondaryEngine)
            job = persist(OcrJobReducer.startRunning(job, clock.millis()))

            project.manifest.pages.distinctBy(PageRecord::pageId).forEach { sourcePage ->
                val selectedPages = job.pages.filter { it.pageId == sourcePage.pageId }
                when (selectedPages.first().state) {
                    OcrPageState.COMMITTED -> {
                        pageArtifacts[sourcePage.pageId] = store.readCommittedPageArtifact(
                            job,
                            selectedPages.first(),
                        ) ?: throw FatalOcrException("COMMITTED_PAGE_INVALID")
                        return@forEach
                    }
                    OcrPageState.PRESERVED_SOURCE -> return@forEach
                    OcrPageState.PENDING -> Unit
                    OcrPageState.RUNNING -> throw FatalOcrException("JOB_RECOVERY_INVALID")
                }
                if (isCancelled()) throw OcrCancellationSignal()

                val detectionPage = detectionPages[sourcePage.pageId]
                    ?: throw FatalOcrException("DETECTION_PAGE_INVALID")
                val pageCandidates = candidates.getValue(sourcePage.pageId)
                val decoded = decoder.decode(resolveSource(project.directory, sourcePage))
                try {
                    require(decoded.bitmap.width == detectionPage.visibleWidth)
                    require(decoded.bitmap.height == detectionPage.visibleHeight)
                    require(decoded.orientation == detectionPage.orientation)
                    if (pageCandidates.isEmpty()) {
                        job = persist(
                            OcrJobReducer.startEmptyPage(job, sourcePage.pageId, clock.millis()),
                            currentOrder = selectedPages.minOf(OcrJobPage::order),
                            currentPageId = sourcePage.pageId,
                        )
                    }

                    val regionArtifacts = mutableListOf<OcrRegionArtifact>()
                    val pendingCandidates = mutableListOf<OcrCandidate>()
                    pageCandidates.sortedBy(OcrCandidate::readingOrderRank).forEach { candidate ->
                        val checkpoint = job.pages.first { it.pageId == sourcePage.pageId }
                            .regions.single { it.ocrRegionId == candidate.ocrRegionId }
                        if (checkpoint.state.isTerminal()) {
                            regionArtifacts += store.readRegionCheckpoint(
                                job,
                                selectedPages.first(),
                                candidate.ocrRegionId,
                            ) ?: throw FatalOcrException("COMMITTED_REGION_INVALID")
                        } else {
                            pendingCandidates += candidate
                        }
                    }
                    if (secondaryEngine != null && pendingCandidates.size >= 2) {
                        regionArtifacts += recognizeRegionsConcurrently(
                            engines = listOf(engine, secondaryEngine),
                            store = store,
                            page = decoded.bitmap,
                            detectionPage = detectionPage,
                            pageId = sourcePage.pageId,
                            currentOrder = selectedPages.minOf(OcrJobPage::order),
                            candidates = pendingCandidates,
                            jobId = job.jobId,
                            isCancelled = ::isCancelled,
                            onProgress = onProgress,
                        )
                        job = stateWriteLock.withLock {
                            activeJob.get()?.takeIf { it.jobId == job.jobId }
                                ?: throw OcrCancellationSignal()
                        }
                    } else pendingCandidates.forEach { candidate ->
                        if (isCancelled()) throw OcrCancellationSignal()
                        job = persist(
                            OcrJobReducer.startRegion(
                                job,
                                sourcePage.pageId,
                                candidate.ocrRegionId,
                                clock.millis(),
                            ),
                            currentOrder = selectedPages.minOf(OcrJobPage::order),
                            currentPageId = sourcePage.pageId,
                            currentRegionId = candidate.ocrRegionId,
                        )
                        val artifact = recognizeRegion(
                            engine = engine,
                            page = decoded.bitmap,
                            detectionPage = detectionPage,
                            candidate = candidate,
                            cancellation = ::isCancelled,
                        )
                        job = stateWriteLock.withLock {
                            val current = activeJob.get()
                                ?.takeIf { it.jobId == job.jobId }
                                ?: throw OcrCancellationSignal()
                            if (isCancelled() || !current.status.isNonTerminal()) {
                                throw OcrCancellationSignal()
                            }
                            val activePage = current.pages.first { it.pageId == sourcePage.pageId }
                            val checkpointPath = store.commitRegion(current, activePage, artifact)
                            OcrJobReducer.commitTerminalRegion(
                                job = current,
                                pageId = sourcePage.pageId,
                                ocrRegionId = candidate.ocrRegionId,
                                state = artifact.state,
                                checkpointPath = checkpointPath,
                                error = artifact.error,
                                nowEpochMillis = clock.millis(),
                            ).also { committed ->
                                store.writeJob(committed)
                                activeJob.set(committed)
                            }
                        }
                        onProgress(
                            job.toProgress(
                                currentOrder = selectedPages.minOf(OcrJobPage::order),
                                currentPageId = sourcePage.pageId,
                                currentRegionId = candidate.ocrRegionId,
                            ),
                        )
                        regionArtifacts += artifact
                    }

                    val pageArtifact = rs.masumi.core.ocr.PageOcrArtifact(
                        pageId = sourcePage.pageId,
                        sourceSha256 = sourcePage.sourceSha256,
                        detectionPageArtifactKey = detectionPage.pageArtifactKey,
                        pageArtifactKey = pageKeys.getValue(sourcePage.pageId),
                        visibleWidth = detectionPage.visibleWidth,
                        visibleHeight = detectionPage.visibleHeight,
                        orientation = detectionPage.orientation,
                        dependencies = dependencies,
                        regions = regionArtifacts.sortedBy { it.candidate.readingOrderRank },
                    )
                    val preview = previewRenderer.render(decoded.bitmap, pageArtifact)
                    val orders = selectedPages.map(OcrJobPage::order)
                    job = stateWriteLock.withLock {
                        val current = activeJob.get()
                            ?.takeIf { it.jobId == job.jobId }
                            ?: throw OcrCancellationSignal()
                        if (isCancelled() || !current.status.isNonTerminal()) {
                            throw OcrCancellationSignal()
                        }
                        store.commitPage(current, pageArtifact, preview, orders)
                        OcrJobReducer.commitPage(
                            job = current,
                            pageId = sourcePage.pageId,
                            artifactPath = "pages/${sourcePage.pageId}/ocr.json",
                            previewPaths = orders.associateWith { order ->
                                "previews/${order.toString().padStart(4, '0')}.webp"
                            },
                            nowEpochMillis = clock.millis(),
                        ).also { committed ->
                            store.writeJob(committed)
                            activeJob.set(committed)
                        }
                    }
                    onProgress(
                        job.toProgress(
                            currentOrder = orders.minOrNull(),
                            currentPageId = sourcePage.pageId,
                        ),
                    )
                    pageArtifacts[sourcePage.pageId] = pageArtifact
                } finally {
                    decoded.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }

            val finishedAt = clock.millis()
            val result = stateWriteLock.withLock {
                val current = activeJob.get()
                    ?.takeIf { it.jobId == job.jobId }
                    ?: throw OcrCancellationSignal()
                if (isCancelled() || !current.status.isNonTerminal()) {
                    throw OcrCancellationSignal()
                }
                val terminal = OcrJobReducer.finishSuccess(current, finishedAt)
                val runArtifact = terminal.toRunArtifact(finishedAt)
                val report = terminal.toReport(finishedAt, pageArtifacts)
                val published = store.publishRun(terminal, runArtifact, report)
                store.writeJob(terminal)
                activeJob.set(terminal)
                OcrRunResult(terminal, runArtifact, report, published)
            }
            job = result.job
            onProgress(job.toProgress())
            return result
        } catch (_: OcrCancellationSignal) {
            activeJob.get()?.takeIf { it.jobId == job.jobId }?.let { job = it }
            activeEngine.get()?.cancel()
            if (job.status.isNonTerminal()) {
                if (!job.cancelRequested) {
                    job = persist(OcrJobReducer.requestCancel(job, clock.millis()))
                }
                job = persist(OcrJobReducer.finishCancellation(job, clock.millis()))
            }
            return OcrRunResult(job)
        } catch (failure: Throwable) {
            activeJob.get()?.takeIf { it.jobId == job.jobId }?.let { job = it }
            if (!job.status.isNonTerminal()) {
                onProgress(job.toProgress())
                return OcrRunResult(job)
            }
            val error = failure.toFatalError()
            job = OcrJobReducer.failJob(job, error, clock.millis())
            stateWriteLock.withLock {
                store.writeJob(job)
                activeJob.set(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return OcrRunResult(job)
        } finally {
            activeEngine.getAndSet(null)?.let { engine -> runCatching { engine.close() } }
            activeSecondaryEngine.getAndSet(null)?.let { engine -> runCatching { engine.close() } }
            activeStore.set(null)
            activeJob.set(null)
        }
    }

    /**
     * Fans pending regions of one page out across the available engines.
     * Workers compute speculatively and only then serialize the
     * startRegion+commitTerminalRegion pair under the state lock, so the
     * durable job record keeps its existing single-RUNNING-region invariant
     * and a crash simply leaves unfinished regions PENDING for resume.
     */
    private fun recognizeRegionsConcurrently(
        engines: List<OcrEngine>,
        store: OcrArtifactStore,
        page: Bitmap,
        detectionPage: PageDetectionArtifact,
        pageId: String,
        currentOrder: Int,
        candidates: List<OcrCandidate>,
        jobId: String,
        isCancelled: () -> Boolean,
        onProgress: (OcrProgress) -> Unit,
    ): List<OcrRegionArtifact> {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(candidates)
        val results = java.util.concurrent.ConcurrentHashMap<String, OcrRegionArtifact>()
        val failure = AtomicReference<Throwable?>()
        val workers = engines.mapIndexed { index, engine ->
            PipelineThreading.thread(
                "$OCR_WORKER_THREAD_PREFIX$index",
                Runnable {
                    while (failure.get() == null) {
                        val candidate = queue.poll() ?: break
                        try {
                            if (isCancelled()) throw OcrCancellationSignal()
                            val artifact = recognizeRegion(
                                engine = engine,
                                page = page,
                                detectionPage = detectionPage,
                                candidate = candidate,
                                cancellation = isCancelled,
                            )
                            val committed = stateWriteLock.withLock {
                                val current = activeJob.get()
                                    ?.takeIf { it.jobId == jobId }
                                    ?: throw OcrCancellationSignal()
                                if (isCancelled() || !current.status.isNonTerminal()) {
                                    throw OcrCancellationSignal()
                                }
                                val started = OcrJobReducer.startRegion(
                                    current,
                                    pageId,
                                    candidate.ocrRegionId,
                                    clock.millis(),
                                )
                                val activePage = started.pages.first { it.pageId == pageId }
                                val checkpointPath = store.commitRegion(started, activePage, artifact)
                                OcrJobReducer.commitTerminalRegion(
                                    job = started,
                                    pageId = pageId,
                                    ocrRegionId = candidate.ocrRegionId,
                                    state = artifact.state,
                                    checkpointPath = checkpointPath,
                                    error = artifact.error,
                                    nowEpochMillis = clock.millis(),
                                ).also { updated ->
                                    store.writeJob(updated)
                                    activeJob.set(updated)
                                }
                            }
                            results[candidate.ocrRegionId] = artifact
                            onProgress(
                                committed.toProgress(
                                    currentOrder = currentOrder,
                                    currentPageId = pageId,
                                    currentRegionId = candidate.ocrRegionId,
                                ),
                            )
                        } catch (worker: Throwable) {
                            failure.compareAndSet(null, worker)
                            engines.forEach { active -> runCatching { active.cancel() } }
                            break
                        }
                    }
                },
            ).apply { start() }
        }
        workers.forEach(Thread::join)
        failure.get()?.let { first ->
            if (isCancelled() || first is OcrCancellationSignal) throw OcrCancellationSignal()
            throw first
        }
        return candidates.map { candidate -> results.getValue(candidate.ocrRegionId) }
    }

    private fun recognizeRegion(
        engine: OcrEngine,
        page: Bitmap,
        detectionPage: PageDetectionArtifact,
        candidate: OcrCandidate,
        cancellation: () -> Boolean,
    ): OcrRegionArtifact {
        val attempts = mutableListOf<OcrAttemptArtifact>()
        val descriptors = cropPolicy.attempts(
            candidate,
            detectionPage.visibleWidth,
            detectionPage.visibleHeight,
        )
        descriptors.forEach { descriptor ->
            if (cancellation()) throw OcrCancellationSignal()
            var rendered: RenderedOcrCrop? = null
            var inferenceShouldPreserveRegion = false
            val attempt = try {
                rendered = cropRenderer.render(page, descriptor)
                val crop = requireNotNull(rendered)
                val result = engine.recognize(
                    OcrEngineRequest(
                        rgb = crop.rgb,
                        width = crop.width,
                        height = crop.height,
                        prompt = dependencies.generation.prompt,
                        maximumGeneratedTokens = dependencies.generation.maximumGeneratedTokens,
                        repetitionPenalty = dependencies.generation.repetitionPenalty,
                    ),
                    cancellation,
                )
                if (cancellation()) throw OcrCancellationSignal()
                result.toAttempt(
                    descriptor,
                    qualityEvaluator.normalize(result.rawText),
                    engine.executionBackend,
                )
            } catch (failure: Throwable) {
                if (cancellation() ||
                    (failure is OcrEngineException && failure.code == OcrEngineErrorCode.CANCELLED)
                ) {
                    throw OcrCancellationSignal()
                }
                inferenceShouldPreserveRegion = failure is OcrEngineException &&
                    (
                        failure.code == OcrEngineErrorCode.TIMEOUT ||
                            failure.code == OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE
                        )
                failure.toFailedAttempt(descriptor, rendered, engine.executionBackend)
            }
            attempts += attempt
            if (inferenceShouldPreserveRegion) {
                return OcrRegionArtifact(
                    candidate = candidate,
                    attempts = attempts,
                    selectedAttemptIndex = null,
                    quality = null,
                    state = OcrRegionState.PRESERVED_SOURCE,
                    error = attempt.error,
                )
            }
            val successfulAttempts = attempts.filter { it.error == null }
            if (successfulAttempts.isNotEmpty()) {
                val decision = qualityEvaluator.evaluate(
                    candidate.detectorConfidence,
                    candidate.sourceClass,
                    attempts,
                )
                if (decision.state == OcrRegionState.RECOGNIZED ||
                    decision.state == OcrRegionState.NO_TEXT_CONFIRMED
                ) {
                    return OcrRegionArtifact(
                        candidate = candidate,
                        attempts = attempts,
                        selectedAttemptIndex = decision.selectedAttemptIndex,
                        quality = decision.quality,
                        state = decision.state,
                    )
                }
            }
        }
        if (attempts.all { it.error != null }) {
            val error = attempts.last().error ?: OcrError("REGION_INFERENCE_FAILED", "OCR region failed")
            return OcrRegionArtifact(
                candidate = candidate,
                attempts = attempts,
                selectedAttemptIndex = null,
                quality = null,
                state = OcrRegionState.PRESERVED_SOURCE,
                error = error,
            )
        }
        val decision = qualityEvaluator.evaluate(
            candidate.detectorConfidence,
            candidate.sourceClass,
            attempts,
        )
        return OcrRegionArtifact(
            candidate = candidate,
            attempts = attempts,
            selectedAttemptIndex = decision.selectedAttemptIndex,
            quality = decision.quality,
            state = decision.state,
        )
    }

    private fun OcrEngineResult.toAttempt(
        descriptor: OcrCropDescriptor,
        normalizedText: String,
        executionBackend: OcrExecutionBackend,
    ): OcrAttemptArtifact = OcrAttemptArtifact(
        executionBackend = executionBackend,
        strategy = descriptor.strategy,
        cropBox = descriptor.box,
        rawText = rawText,
        normalizedText = normalizedText,
        tokenIds = tokenIds,
        tokenProbabilities = tokenProbabilities,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        processedWidth = processedWidth,
        processedHeight = processedHeight,
        visualTokenCount = visualTokenCount,
        generatedTokenCount = generatedTokenCount,
        reachedEos = reachedEos,
        truncated = truncated,
        repetitionStopped = repetitionStopped,
        invalidUtf8 = false,
        promptEvaluationMillis = promptEvaluationMillis,
        generationMillis = generationMillis,
    )

    private fun Throwable.toFailedAttempt(
        descriptor: OcrCropDescriptor,
        crop: RenderedOcrCrop?,
        executionBackend: OcrExecutionBackend,
    ): OcrAttemptArtifact = OcrAttemptArtifact(
        executionBackend = executionBackend,
        strategy = descriptor.strategy,
        cropBox = descriptor.box,
        rawText = "",
        normalizedText = "",
        tokenIds = emptyList(),
        tokenProbabilities = emptyList(),
        sourceWidth = crop?.width ?: 0,
        sourceHeight = crop?.height ?: 0,
        processedWidth = 0,
        processedHeight = 0,
        visualTokenCount = 0,
        generatedTokenCount = 0,
        reachedEos = false,
        truncated = false,
        repetitionStopped = false,
        invalidUtf8 = this is OcrEngineException && code == OcrEngineErrorCode.UTF8,
        promptEvaluationMillis = 0L,
        generationMillis = 0L,
        error = toRegionError(),
    )

    private fun recoverOrCreateJob(
        store: OcrArtifactStore,
        manifest: ProjectManifest,
        detectionRun: PublishedDetectionRun,
        candidates: Map<String, List<OcrCandidate>>,
        pageKeys: Map<String, String>,
        runKey: String,
    ): OcrJobRecord {
        val candidate = store.findRecoveryCandidates()
            .filter { job ->
                job.projectId == manifest.projectId &&
                job.detectionRunArtifactKey == detectionRun.artifact.runArtifactKey &&
                job.runArtifactKey == runKey &&
                job.dependencies == dependencies
            }
            .maxWithOrNull(
                compareBy<OcrJobRecord> { job ->
                    job.pages.distinctBy(OcrJobPage::pageId).sumOf { page ->
                        page.regions.count { it.state.isTerminal() }
                    }
                }.thenBy { job ->
                    job.pages.distinctBy(OcrJobPage::pageId).count { page ->
                        page.state == OcrPageState.COMMITTED ||
                            page.state == OcrPageState.PRESERVED_SOURCE
                    }
                }.thenBy(OcrJobRecord::updatedAtEpochMillis).thenBy(OcrJobRecord::jobId),
            )
        if (candidate != null) {
            candidate.pages
                .flatMap { page -> page.regions.filter { it.state == OcrRegionState.RUNNING }.map { page.pageId to it.ocrRegionId } }
                .distinct()
                .forEach { (pageId, regionId) -> store.cleanInterruptedRegion(candidate, pageId, regionId) }
            val recovered = when (candidate.status) {
                OcrJobStatus.CANCELLED -> OcrJobReducer.resumeCancelled(candidate, clock.millis())
                OcrJobStatus.QUEUED -> candidate
                OcrJobStatus.DOWNLOADING_MODEL,
                OcrJobStatus.LOADING_MODEL,
                OcrJobStatus.RUNNING,
                -> OcrJobReducer.recoverInterrupted(candidate, clock.millis())
                OcrJobStatus.FAILED -> OcrJobReducer.retryFailed(candidate, clock.millis())
                else -> error("successful OCR job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }

        val now = clock.millis()
        return OcrJobRecord(
            jobId = idSource.nextId(),
            projectId = manifest.projectId,
            detectionRunArtifactKey = detectionRun.artifact.runArtifactKey,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            pages = manifest.pages.map { page ->
                val detectionEntry = detectionEntry(detectionRun, page.order)
                if (detectionEntry.state == DetectionPageState.PRESERVED_SOURCE) {
                    OcrJobPage(
                        order = page.order,
                        pageId = page.pageId,
                        sourceSha256 = page.sourceSha256,
                        detectionPageArtifactKey = detectionEntry.pageArtifactKey,
                        pageArtifactKey = pageKeys.getValue(page.pageId),
                        state = OcrPageState.PRESERVED_SOURCE,
                        regions = emptyList(),
                        error = OcrError("DETECTION_PAGE_PRESERVED", "Detection page was preserved"),
                    )
                } else {
                    OcrJobPage(
                        order = page.order,
                        pageId = page.pageId,
                        sourceSha256 = page.sourceSha256,
                        detectionPageArtifactKey = detectionEntry.pageArtifactKey,
                        pageArtifactKey = pageKeys.getValue(page.pageId),
                        regions = candidates.getValue(page.pageId).map { item ->
                            OcrRegionCheckpoint(item.ocrRegionId)
                        },
                    )
                }
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: OcrArtifactStore,
        project: ProjectRef,
        detectionRun: PublishedDetectionRun,
        runKey: String,
    ): OcrRunResult? {
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(artifact.projectId == project.manifest.projectId)
        require(artifact.detectionRunArtifactKey == detectionRun.artifact.runArtifactKey)
        require(artifact.dependencies == dependencies)
        val job = store.readJob(report.jobId) ?: return null
        require(job.status == report.status && job.status.isSuccessful())
        return OcrRunResult(
            job = job,
            runArtifact = artifact,
            report = report,
            publishedDirectory = project.directory.resolve("artifacts/ocr/$runKey"),
        )
    }

    private fun loadDetectionPages(
        manifest: ProjectManifest,
        run: PublishedDetectionRun,
    ): Map<String, PageDetectionArtifact> = manifest.pages
        .distinctBy(PageRecord::pageId)
        .mapNotNull { page ->
            val entry = run.artifact.entries.first { it.pageId == page.pageId }
            if (entry.state == DetectionPageState.PRESERVED_SOURCE) return@mapNotNull null
            val artifact = catalog.readPublishedPage(run, page.pageId)
                ?: throw FatalOcrException("DETECTION_PAGE_INVALID")
            page.pageId to artifact
        }
        .toMap()

    private fun validateDetectionDependency(manifest: ProjectManifest, run: PublishedDetectionRun) {
        require(run.artifact.projectId == manifest.projectId)
        require(run.report.projectId == manifest.projectId)
        require(run.artifact.entries.size == manifest.pages.size)
        manifest.pages.forEach { page ->
            val entry = detectionEntry(run, page.order)
            require(entry.pageId == page.pageId)
            require(entry.order == page.order)
        }
    }

    private fun detectionEntry(run: PublishedDetectionRun, order: Int) =
        run.artifact.entries.single { it.order == order }

    private fun validateSources(projectDirectory: Path, manifest: ProjectManifest) {
        manifest.pages.distinctBy(PageRecord::pageId).forEach { page ->
            require(SHA256.matches(page.pageId)) { "page ID is not a SHA-256 digest" }
            require(page.pageId == page.sourceSha256) { "page and source digests differ" }
            val source = resolveSource(projectDirectory, page)
            if (!Files.isRegularFile(source)) throw FatalOcrException("SOURCE_MISSING")
            if (Files.size(source) != page.byteLength) throw FatalOcrException("SOURCE_LENGTH_MISMATCH")
            if (sha256(source) != page.sourceSha256) throw FatalOcrException("SOURCE_HASH_MISMATCH")
        }
    }

    private fun resolveSource(projectDirectory: Path, page: PageRecord): Path {
        require(page.storedPath.isNotBlank() && !page.storedPath.startsWith('/'))
        require(page.storedPath.split('/').none { it.isBlank() || it == ".." })
        return projectDirectory.resolve(page.storedPath).normalize().also { source ->
            require(source.startsWith(projectDirectory)) { "source path escaped project" }
        }
    }

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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun OcrJobRecord.toRunArtifact(createdAt: Long): OcrRunArtifact = OcrRunArtifact(
        runArtifactKey = runArtifactKey,
        projectId = projectId,
        detectionRunArtifactKey = detectionRunArtifactKey,
        createdAtEpochMillis = createdAt,
        dependencies = dependencies,
        entries = pages.map { page ->
            OcrRunEntry(
                order = page.order,
                pageId = page.pageId,
                sourceSha256 = page.sourceSha256,
                detectionPageArtifactKey = page.detectionPageArtifactKey,
                pageArtifactKey = page.pageArtifactKey,
                state = page.state,
                artifactPath = page.artifactPath,
                previewPath = page.previewPath,
                error = page.error,
            )
        },
    )

    private fun OcrJobRecord.toReport(
        finishedAt: Long,
        artifacts: Map<String, rs.masumi.core.ocr.PageOcrArtifact>,
    ): OcrReport {
        val uniquePages = pages.distinctBy(OcrJobPage::pageId)
        val regions = uniquePages.flatMap { page -> artifacts[page.pageId]?.regions.orEmpty() }
        return OcrReport(
            jobId = jobId,
            projectId = projectId,
            runArtifactKey = runArtifactKey,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAt,
            status = status,
            totalPageCount = pages.size,
            committedPageCount = pages.count { it.state == OcrPageState.COMMITTED },
            totalRegionCount = regions.size,
            recognizedRegionCount = regions.count { it.state == OcrRegionState.RECOGNIZED },
            needsFallbackRegionCount = regions.count { it.state == OcrRegionState.NEEDS_FALLBACK },
            noTextRegionCount = regions.count { it.state == OcrRegionState.NO_TEXT_CONFIRMED },
            preservedRegionCount = regions.count { it.state == OcrRegionState.PRESERVED_SOURCE },
            retryCount = regions.sumOf { (it.attempts.size - 1).coerceAtLeast(0) },
            stateCounts = OcrRegionState.entries.associate { state ->
                state.name to regions.count { it.state == state }
            },
            preservedRegionIds = regions.filter {
                it.state == OcrRegionState.NEEDS_FALLBACK || it.state == OcrRegionState.PRESERVED_SOURCE
            }.map { it.candidate.ocrRegionId }.sorted(),
        )
    }

    private fun OcrJobRecord.toProgress(
        currentOrder: Int? = null,
        currentPageId: String? = null,
        currentRegionId: String? = null,
        downloadedBytes: Long = 0L,
        totalDownloadBytes: Long = 0L,
        errorCode: String? = error?.code,
    ): OcrProgress {
        val uniquePages = pages.distinctBy(OcrJobPage::pageId)
        return OcrProgress(
            projectId = projectId,
            jobId = jobId,
            runArtifactKey = runArtifactKey,
            status = status,
            terminalRegionCount = uniquePages.sumOf { page -> page.regions.count { it.state.isTerminal() } },
            totalRegionCount = uniquePages.sumOf { it.regions.size },
            committedPageCount = pages.count { it.state == OcrPageState.COMMITTED },
            totalPageCount = pages.size,
            currentOrder = currentOrder,
            currentPageId = currentPageId,
            currentRegionId = currentRegionId,
            downloadedBytes = downloadedBytes,
            totalDownloadBytes = totalDownloadBytes,
            errorCode = errorCode,
        )
    }

    private fun Throwable.toRegionError(): OcrError = when (this) {
        is OcrEngineException -> OcrError("ENGINE_${code.name}", code.safeMessage)
        is PageDecodeException -> OcrError("PAGE_DECODE_FAILED", "Page image could not be decoded")
        is IllegalArgumentException -> OcrError("CROP_INVALID", "OCR crop was invalid")
        else -> OcrError("REGION_INFERENCE_FAILED", "OCR region failed")
    }

    private fun Throwable.toFatalError(): OcrError = when (this) {
        is FatalOcrException -> OcrError(code, safeFatalMessage(code))
        is OcrModelPackageException -> OcrError("MODEL_${code.name}", code.safeMessage)
        is OcrEngineException -> OcrError("ENGINE_${code.name}", code.safeMessage)
        is IllegalArgumentException -> OcrError("PROJECT_INVALID", "Project data was invalid")
        else -> OcrError("OCR_RUN_FAILED", "OCR run could not be completed")
    }

    private fun safeFatalMessage(code: String): String = when (code) {
        "SOURCE_MISSING" -> "An imported source page was missing"
        "SOURCE_LENGTH_MISMATCH", "SOURCE_HASH_MISMATCH" -> "An imported source page failed integrity checks"
        "DETECTION_PAGE_INVALID" -> "The detection dependency was invalid"
        "COMMITTED_REGION_INVALID", "COMMITTED_PAGE_INVALID" -> "An OCR checkpoint was invalid"
        "MODEL_PACKAGE_DEPENDENCY_MISMATCH" -> "The installed OCR model package was incompatible"
        else -> "OCR run could not be completed"
    }

    private fun OcrJobStatus.isNonTerminal(): Boolean = when (this) {
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

    private class OcrCancellationSignal : RuntimeException()
    private class FatalOcrException(val code: String) : RuntimeException(code)

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        const val OCR_WORKER_THREAD_PREFIX = "masumi-ocr-region-"
    }
}

private fun defaultOcrDependencies(): OcrDependencies {
    val descriptor = PinnedPaddleOcrVl.descriptor
    return OcrDependencies(
        modelPackage = descriptor.toRef(),
        runtime = descriptor.runtime.toRef(),
        generation = OcrGenerationConfig(prompt = descriptor.prompt),
    )
}
