package rs.masumi.app.detection

import android.graphics.Bitmap
import rs.masumi.core.detection.DETECTION_SCHEMA_VERSION
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionError
import rs.masumi.core.detection.DetectionIdentity
import rs.masumi.core.detection.DetectionJobPage
import rs.masumi.core.detection.DetectionJobRecord
import rs.masumi.core.detection.DetectionJobReducer
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionPostProcessor
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.DetectorModelRef
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.ModelPackageException
import rs.masumi.core.modelpackage.PinnedComicDetector
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock

data class DetectionProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: DetectionJobStatus,
    val committedPageCount: Int,
    val preservedPageCount: Int,
    val totalPageCount: Int,
    val currentOrder: Int? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val errorCode: String? = null,
)

data class DetectionRunResult(
    val job: DetectionJobRecord,
    val runArtifact: DetectionRunArtifact? = null,
    val report: DetectionReport? = null,
    val publishedDirectory: Path? = null,
)

class DetectionRunner(
    workspaceRoot: Path,
    private val modelProvider: DetectorModelProvider,
    private val detectorFactory: ComicDetectorFactory,
    private val decoder: PageBitmapDecoder,
    private val previewRenderer: DetectionPreviewRenderer,
    private val model: DetectorModelRef = PinnedComicDetector.descriptor.toModelRef(),
    private val preprocessing: DetectionPreprocessingConfig = DetectionPreprocessingConfig(),
    private val thresholds: DetectionThresholdConfig = DetectionThresholdConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val catalog = ProjectCatalog(this.workspaceRoot)

    fun run(
        projectId: String,
        cancellation: () -> Boolean,
        onProgress: (DetectionProgress) -> Unit,
    ): DetectionRunResult {
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val manifest = project.manifest
        val pageKeys = manifest.pages.associate { page ->
            page.pageId to DetectionIdentity.pageArtifactKey(
                sourceSha256 = page.sourceSha256,
                schemaVersion = DETECTION_SCHEMA_VERSION,
                model = model,
                preprocessing = preprocessing,
                thresholds = thresholds,
            )
        }
        val runKey = DetectionIdentity.runArtifactKey(
            manifest.pages.map { page -> page.order to pageKeys.getValue(page.pageId) },
        )
        val store = DetectionArtifactStore(project.directory)

        readPublishedResult(store, project.directory, manifest, runKey)?.let { cached ->
            onProgress(cached.job.toProgress())
            return cached
        }

        var job = recoverOrCreateJob(store, manifest, pageKeys, runKey)
        var detector: ComicDetector? = null
        val pageArtifacts = mutableMapOf<String, PageDetectionArtifact>()

        fun persist(updated: DetectionJobRecord, currentOrder: Int? = null): DetectionJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentOrder = currentOrder))
            return updated
        }

        try {
            validateSources(project.directory, manifest)

            job = persist(DetectionJobReducer.startModelDownload(job, clock.millis()))
            val modelFile = modelProvider.acquire(job.jobId) { downloaded, total ->
                onProgress(
                    job.toProgress(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                    ),
                )
            }
            job = persist(DetectionJobReducer.startRunning(job, clock.millis()))

            manifest.pages.distinctBy(PageRecord::pageId).forEach { sourcePage ->
                val selected = job.pages.filter { it.pageId == sourcePage.pageId }
                when (selected.first().state) {
                    DetectionPageState.COMMITTED -> {
                        val artifact = store.readCommittedPageArtifact(job, selected.first())
                            ?: throw FatalDetectionException("COMMITTED_ARTIFACT_INVALID")
                        pageArtifacts[sourcePage.pageId] = artifact
                        return@forEach
                    }

                    DetectionPageState.PRESERVED_SOURCE -> return@forEach
                    DetectionPageState.PENDING -> Unit
                    DetectionPageState.RUNNING -> throw FatalDetectionException("JOB_RECOVERY_INVALID")
                }

                if (cancellation()) {
                    job = DetectionJobReducer.requestCancel(job, clock.millis())
                    job = persist(job)
                    job = DetectionJobReducer.finishCancellation(job, clock.millis())
                    job = persist(job)
                    return DetectionRunResult(job = job)
                }

                val sourcePath = resolveSource(project.directory, sourcePage)
                var completed = false
                while (!completed) {
                    job = persist(
                        DetectionJobReducer.startPage(job, sourcePage.pageId, clock.millis()),
                        currentOrder = selected.minOf(DetectionJobPage::order),
                    )
                    var bitmap: Bitmap? = null
                    var attemptResult: PageAttemptResult? = null
                    try {
                        val page = decoder.decode(sourcePath)
                        bitmap = page.bitmap
                        val activeDetector = detector ?: detectorFactory.open(modelFile).also {
                            detector = it
                        }
                        val processed = DetectionPostProcessor.process(
                            pageId = sourcePage.pageId,
                            pageArtifactKey = pageKeys.getValue(sourcePage.pageId),
                            pageWidth = page.bitmap.width,
                            pageHeight = page.bitmap.height,
                            thresholds = thresholds,
                            queries = activeDetector.detect(page),
                        )
                        val pageArtifact = PageDetectionArtifact(
                            pageId = sourcePage.pageId,
                            sourceSha256 = sourcePage.sourceSha256,
                            pageArtifactKey = pageKeys.getValue(sourcePage.pageId),
                            visibleWidth = page.bitmap.width,
                            visibleHeight = page.bitmap.height,
                            orientation = page.orientation,
                            model = model,
                            preprocessing = preprocessing,
                            thresholds = thresholds,
                            rawQueries = processed.rawQueries,
                            bubbleCandidates = processed.bubbles,
                            textRegions = processed.textRegions,
                        )
                        attemptResult = PageAttemptResult(
                            artifact = pageArtifact,
                            previewPng = previewRenderer.render(
                                page = page,
                                bubbles = processed.bubbles,
                                textRegions = processed.textRegions,
                            ),
                        )
                    } catch (failure: Throwable) {
                        if (failure is FatalDetectionException) throw failure
                        val pageError = failure.toPageError()
                        closeDetector(detector)
                        detector = null
                        val attempt = job.pages.first { it.pageId == sourcePage.pageId }.attemptCount
                        if (attempt == 1) {
                            job = persist(
                                DetectionJobReducer.recordRetry(
                                    job,
                                    sourcePage.pageId,
                                    pageError,
                                    clock.millis(),
                                ),
                                currentOrder = sourcePage.order,
                            )
                            store.cleanInterruptedPage(
                                job,
                                job.pages.first { it.pageId == sourcePage.pageId },
                            )
                        } else {
                            job = persist(
                                DetectionJobReducer.preservePage(
                                    job,
                                    sourcePage.pageId,
                                    pageError,
                                    clock.millis(),
                                ),
                                currentOrder = sourcePage.order,
                            )
                            completed = true
                        }
                    } finally {
                        bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    }

                    attemptResult?.let { result ->
                        val orders = job.pages
                            .filter { it.pageId == sourcePage.pageId }
                            .map(DetectionJobPage::order)
                        try {
                            store.commitPage(job, result.artifact, result.previewPng, orders)
                        } catch (failure: Throwable) {
                            throw FatalDetectionException("PAGE_ARTIFACT_WRITE_FAILED", failure)
                        }
                        val regionsPath = "pages/${sourcePage.pageId}/regions.json"
                        val previewPaths = orders.associateWith { order ->
                            "previews/${order.toString().padStart(4, '0')}.webp"
                        }
                        job = persist(
                            DetectionJobReducer.commitPage(
                                job = job,
                                pageId = sourcePage.pageId,
                                regionsPath = regionsPath,
                                previewPaths = previewPaths,
                                nowEpochMillis = clock.millis(),
                            ),
                            currentOrder = orders.minOrNull(),
                        )
                        pageArtifacts[sourcePage.pageId] = result.artifact
                        completed = true
                    }
                }
            }

            val finishedAt = clock.millis()
            val terminalJob = DetectionJobReducer.finish(job, finishedAt)
            val artifact = terminalJob.toRunArtifact(finishedAt)
            val report = terminalJob.toReport(finishedAt, pageArtifacts)
            val published = store.publishRun(terminalJob, artifact, report)
            job = persist(terminalJob)
            return DetectionRunResult(job, artifact, report, published)
        } catch (failure: Throwable) {
            val error = failure.toFatalError()
            if (job.status.isNonTerminal()) {
                job = DetectionJobReducer.failJob(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return DetectionRunResult(job = job)
        } finally {
            closeDetector(detector)
        }
    }

    private fun recoverOrCreateJob(
        store: DetectionArtifactStore,
        manifest: ProjectManifest,
        pageKeys: Map<String, String>,
        runKey: String,
    ): DetectionJobRecord {
        val candidate = store.findResumableJob()?.takeIf { job ->
            job.projectId == manifest.projectId &&
                job.runArtifactKey == runKey &&
                job.model == model &&
                job.preprocessing == preprocessing &&
                job.thresholds == thresholds
        }
        if (candidate != null) {
            val recovered = when (candidate.status) {
                DetectionJobStatus.CANCELLED -> {
                    DetectionJobReducer.resumeCancelled(candidate, clock.millis())
                }

                DetectionJobStatus.QUEUED -> candidate
                DetectionJobStatus.DOWNLOADING_MODEL,
                DetectionJobStatus.RUNNING,
                -> {
                    candidate.pages
                        .filter { it.state == DetectionPageState.RUNNING }
                        .distinctBy(DetectionJobPage::pageId)
                        .forEach { page -> store.cleanInterruptedPage(candidate, page) }
                    DetectionJobReducer.recoverInterrupted(candidate, clock.millis())
                }

                else -> error("terminal job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }

        val now = clock.millis()
        return DetectionJobRecord(
            jobId = idSource.nextId(),
            projectId = manifest.projectId,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            pages = manifest.pages.map { page ->
                DetectionJobPage(
                    order = page.order,
                    pageId = page.pageId,
                    pageArtifactKey = pageKeys.getValue(page.pageId),
                )
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: DetectionArtifactStore,
        projectDirectory: Path,
        manifest: ProjectManifest,
        runKey: String,
    ): DetectionRunResult? {
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(artifact.projectId == manifest.projectId)
        require(report.projectId == manifest.projectId)
        require(artifact.entries.map(DetectionRunEntry::order) == manifest.pages.indices.toList())
        var job = store.readJob(report.jobId)
        if (job == null || job.status != report.status || job.pages.any { it.state == DetectionPageState.RUNNING }) {
            job = DetectionJobRecord(
                jobId = report.jobId,
                projectId = report.projectId,
                runArtifactKey = report.runArtifactKey,
                startedAtEpochMillis = report.startedAtEpochMillis,
                updatedAtEpochMillis = report.finishedAtEpochMillis,
                status = report.status,
                model = artifact.model,
                preprocessing = artifact.preprocessing,
                thresholds = artifact.thresholds,
                pages = artifact.entries.map { entry ->
                    DetectionJobPage(
                        order = entry.order,
                        pageId = entry.pageId,
                        pageArtifactKey = entry.pageArtifactKey,
                        state = entry.state,
                        attemptCount = 1,
                        regionsPath = entry.regionsPath,
                        previewPath = entry.previewPath,
                        error = entry.error,
                    )
                },
            )
            store.writeJob(job)
        }
        return DetectionRunResult(
            job = job,
            runArtifact = artifact,
            report = report,
            publishedDirectory = projectDirectory.resolve("artifacts/detection/$runKey"),
        )
    }

    private fun validateSources(projectDirectory: Path, manifest: ProjectManifest) {
        manifest.pages.distinctBy(PageRecord::pageId).forEach { page ->
            require(SHA256.matches(page.pageId)) { "page ID is not a SHA-256 digest" }
            require(page.pageId == page.sourceSha256) { "page and source digests differ" }
            val source = resolveSource(projectDirectory, page)
            if (!Files.isRegularFile(source)) throw FatalDetectionException("SOURCE_MISSING")
            if (Files.size(source) != page.byteLength) throw FatalDetectionException("SOURCE_LENGTH_MISMATCH")
            if (sha256(source) != page.sourceSha256) throw FatalDetectionException("SOURCE_HASH_MISMATCH")
        }
    }

    private fun resolveSource(projectDirectory: Path, page: PageRecord): Path {
        require(page.storedPath.isNotBlank()) { "source path must not be blank" }
        require(!page.storedPath.startsWith('/')) { "source path must be relative" }
        require(page.storedPath.split('/').none { it.isBlank() || it == ".." }) {
            "source path is unsafe"
        }
        val source = projectDirectory.resolve(page.storedPath).normalize()
        require(source.startsWith(projectDirectory)) { "source path escaped project" }
        return source
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

    private fun DetectionJobRecord.toRunArtifact(createdAt: Long): DetectionRunArtifact =
        DetectionRunArtifact(
            runArtifactKey = runArtifactKey,
            projectId = projectId,
            createdAtEpochMillis = createdAt,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            entries = pages.map { page ->
                DetectionRunEntry(
                    order = page.order,
                    pageId = page.pageId,
                    pageArtifactKey = page.pageArtifactKey,
                    state = page.state,
                    regionsPath = page.regionsPath,
                    previewPath = page.previewPath,
                    error = page.error,
                )
            },
        )

    private fun DetectionJobRecord.toReport(
        finishedAt: Long,
        artifacts: Map<String, PageDetectionArtifact>,
    ): DetectionReport {
        val orderedRegions = pages.flatMap { page ->
            artifacts[page.pageId]?.let { it.bubbleCandidates + it.textRegions }.orEmpty()
        }
        val counts = DetectorClass.entries.associate { detectorClass ->
            detectorClass.name to orderedRegions.count { it.detectorClass == detectorClass }
        }
        val buckets = linkedMapOf(
            "0.25-0.49" to orderedRegions.count { it.confidence < 0.5 },
            "0.50-0.74" to orderedRegions.count { it.confidence >= 0.5 && it.confidence < 0.75 },
            "0.75-1.00" to orderedRegions.count { it.confidence >= 0.75 },
        )
        return DetectionReport(
            jobId = jobId,
            projectId = projectId,
            runArtifactKey = runArtifactKey,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = finishedAt,
            status = status,
            totalPageCount = pages.size,
            committedPageCount = pages.count { it.state == DetectionPageState.COMMITTED },
            preservedPageCount = pages.count { it.state == DetectionPageState.PRESERVED_SOURCE },
            retryCount = pages.distinctBy(DetectionJobPage::pageId)
                .sumOf { (it.attemptCount - 1).coerceAtLeast(0) },
            classCounts = counts,
            confidenceBuckets = buckets,
            preservedOrders = pages
                .filter { it.state == DetectionPageState.PRESERVED_SOURCE }
                .map(DetectionJobPage::order),
        )
    }

    private fun DetectionJobRecord.toProgress(
        currentOrder: Int? = null,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        errorCode: String? = error?.code,
    ): DetectionProgress = DetectionProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        committedPageCount = pages.count { it.state == DetectionPageState.COMMITTED },
        preservedPageCount = pages.count { it.state == DetectionPageState.PRESERVED_SOURCE },
        totalPageCount = pages.size,
        currentOrder = currentOrder,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        errorCode = errorCode,
    )

    private fun Throwable.toPageError(): DetectionError = when (this) {
        is ComicDetectorException -> DetectionError(code, "Page inference failed")
        is PageDecodeException -> DetectionError("PAGE_DECODE_FAILED", "Page image could not be decoded")
        is IllegalArgumentException -> DetectionError("DETECTION_OUTPUT_INVALID", "Detector output was invalid")
        else -> DetectionError("PAGE_PROCESSING_FAILED", "Page processing failed")
    }

    private fun Throwable.toFatalError(): DetectionError = when (this) {
        is FatalDetectionException -> DetectionError(code, safeFatalMessage(code))
        is ModelPackageException -> DetectionError("MODEL_${code.name}", "Detector model was unavailable")
        is ComicDetectorException -> DetectionError(code, "Detector could not be started")
        is IllegalArgumentException -> DetectionError("PROJECT_INVALID", "Project data was invalid")
        else -> DetectionError("DETECTION_RUN_FAILED", "Detection run could not be completed")
    }

    private fun safeFatalMessage(code: String): String = when (code) {
        "SOURCE_MISSING" -> "An imported source page was missing"
        "SOURCE_LENGTH_MISMATCH", "SOURCE_HASH_MISMATCH" -> "An imported source page failed integrity checks"
        "COMMITTED_ARTIFACT_INVALID" -> "A committed detection checkpoint was invalid"
        "JOB_RECOVERY_INVALID" -> "The detection checkpoint could not be recovered"
        "PAGE_ARTIFACT_WRITE_FAILED" -> "A page detection checkpoint could not be written"
        else -> "Detection run could not be completed"
    }

    private fun closeDetector(detector: ComicDetector?) {
        runCatching { detector?.close() }
    }

    private fun DetectionJobStatus.isNonTerminal(): Boolean = when (this) {
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

    private data class PageAttemptResult(
        val artifact: PageDetectionArtifact,
        val previewPng: ByteArray,
    )

    private class FatalDetectionException(
        val code: String,
        cause: Throwable? = null,
    ) : RuntimeException(code, cause)

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
