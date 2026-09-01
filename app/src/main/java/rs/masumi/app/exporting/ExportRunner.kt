package rs.masumi.app.exporting

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
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.app.pipeline.SourceFilePreflight
import rs.masumi.app.pipeline.SourceFilePreflightException
import rs.masumi.app.pipeline.CurrentPipelineArtifactsReader
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportDependencies
import rs.masumi.core.exporting.ExportError
import rs.masumi.core.exporting.ExportIdentity
import rs.masumi.core.exporting.ExportJobPage
import rs.masumi.core.exporting.ExportJobRecord
import rs.masumi.core.exporting.ExportJobReducer
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.exporting.ExportPageSource
import rs.masumi.core.exporting.ExportPageState
import rs.masumi.core.exporting.ExportPolicy
import rs.masumi.core.exporting.ExportReport
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingPageState

data class ExportProgress(
    val projectId: String,
    val jobId: String,
    val exportKey: String,
    val status: ExportJobStatus,
    val terminalPageCount: Int,
    val totalPageCount: Int,
    val flattenedPageCount: Int,
    val cleanedFallbackPageCount: Int,
    val sourceFallbackPageCount: Int,
    val reusedPageCount: Int,
    val currentPageOrder: Int? = null,
    val errorCode: String? = null,
)

data class ExportRunResult(
    val job: ExportJobRecord,
    val report: ExportReport? = null,
)

class ExportRunner(
    workspaceRoot: Path,
    private val destinationFactory: (
        destinationUri: String,
        jobId: String,
        generationName: String,
    ) -> FolderExportDestination,
    private val decoder: PageBitmapDecoder = PageBitmapDecoder(),
    private val policy: ExportPolicy = ExportPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
) {
    private val catalog = ProjectCatalog(workspaceRoot.toAbsolutePath().normalize())
    private val currentArtifacts = CurrentPipelineArtifactsReader(catalog)
    private val externallyCancelled = AtomicBoolean(false)

    fun cancel() {
        externallyCancelled.set(true)
    }

    fun run(
        projectId: String,
        destinationUri: String?,
        cancellation: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportRunResult {
        externallyCancelled.set(false)
        val current = requireNotNull(currentArtifacts.read(projectId)) { "project was not found" }
        val project = current.project
        val cleanupRun = requireNotNull(current.cleanup) { "completed current cleanup run was not found" }
        val typesettingRun = requireNotNull(current.typesetting) {
            "completed current typesetting run was not found"
        }
        validateDependencies(project, typesettingRun, cleanupRun)
        val dependencies = ExportDependencies(
            typesettingRunArtifactKey = typesettingRun.artifact.runArtifactKey,
            policy = policy,
        )
        val store = ExportArtifactStore(project.directory)
        var job = recoverOrCreateJob(
            store,
            project,
            typesettingRun,
            cleanupRun,
            dependencies,
            destinationUri,
        )
        var destination: FolderExportDestination? = null
        var generationPublished = false
        var completionDurable = false

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        fun persist(updated: ExportJobRecord, currentPageOrder: Int? = null): ExportJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentPageOrder))
            return updated
        }

        try {
            job = persist(ExportJobReducer.start(job, clock.millis()))
            destination = destinationFactory(
                job.destinationUri,
                job.jobId,
                rs.masumi.app.library.OutputGeneration.publishedName(
                    job.startedAtEpochMillis,
                    job.exportKey,
                ),
            )
            project.manifest.pages.sortedBy(PageRecord::order).forEach { sourcePage ->
                var checkpoint = job.pages.single { it.pageOrder == sourcePage.order }
                if (checkpoint.state == ExportPageState.COMMITTED) {
                    val valid = destination.matches(
                        checkpoint.outputName,
                        requireNotNull(checkpoint.outputSha256),
                        checkpoint.byteLength,
                    )
                    if (valid) return@forEach
                    job = persist(
                        ExportJobReducer.invalidateCommittedPage(job, sourcePage.order, clock.millis()),
                        sourcePage.order,
                    )
                    checkpoint = job.pages.single { it.pageOrder == sourcePage.order }
                }
                if (checkpoint.state == ExportPageState.RUNNING) {
                    throw FatalExportException("EXPORT_RECOVERY_INVALID")
                }
                if (isCancelled()) throw ExportCancellationSignal()
                job = persist(
                    ExportJobReducer.startPage(job, sourcePage.order, clock.millis()),
                    sourcePage.order,
                )
                val resolved = resolvePage(project, sourcePage, typesettingRun, cleanupRun)
                if (resolved.source != checkpoint.source) throw FatalExportException("EXPORT_SOURCE_CHANGED")
                val digest = sha256(resolved.bytes)
                val result = destination.publish(
                    checkpoint.outputName,
                    resolved.bytes,
                    digest,
                    ::isCancelled,
                )
                job = persist(
                    ExportJobReducer.commitPage(
                        job,
                        sourcePage.order,
                        digest,
                        resolved.bytes.size.toLong(),
                        result.reusedExisting,
                        clock.millis(),
                    ),
                    sourcePage.order,
                )
            }
            if (isCancelled()) throw ExportCancellationSignal()
            val expectedOutputs = job.pages.map { page ->
                ExpectedDestinationOutput(
                    outputName = page.outputName,
                )
            }
            destination.commitCompleteSet(expectedOutputs)
            generationPublished = true
            val finishedAt = clock.millis()
            val successfulJob = ExportJobReducer.finishSuccess(job, finishedAt)
            val report = successfulJob.toReport(finishedAt)
            store.commitSuccessfulExport(successfulJob, report)
            job = successfulJob
            completionDurable = true
            onProgress(job.toProgress())
            runCatching { destination.pruneManagedOutputs() }
            return ExportRunResult(job, report)
        } catch (_: ExportCancellationSignal) {
            if (generationPublished && !completionDurable) {
                runCatching { destination?.rollbackCommittedSet() }
            }
            if (job.status == ExportJobStatus.RUNNING) {
                if (!job.cancelRequested) job = persist(ExportJobReducer.requestCancellation(job, clock.millis()))
                job = persist(ExportJobReducer.finishCancellation(job, clock.millis()))
            }
            return ExportRunResult(job)
        } catch (failure: Throwable) {
            if (generationPublished && !completionDurable) {
                runCatching { destination?.rollbackCommittedSet() }
            }
            val error = ExportError(failure.safeErrorCode())
            if (job.status == ExportJobStatus.RUNNING || job.status == ExportJobStatus.QUEUED) {
                job = ExportJobReducer.fail(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return ExportRunResult(job)
        }
    }

    private fun recoverOrCreateJob(
        store: ExportArtifactStore,
        project: ProjectRef,
        typesettingRun: PublishedTypesettingRun,
        cleanupRun: PublishedCleanupRun,
        dependencies: ExportDependencies,
        requestedDestinationUri: String?,
    ): ExportJobRecord {
        val requestedDestinationKey = requestedDestinationUri?.let(ExportIdentity::destinationKey)
        val candidate = store.findRecoverableJob()?.takeIf { existing ->
            existing.projectId == project.manifest.projectId &&
                existing.dependencies == dependencies &&
                (requestedDestinationKey == null || existing.destinationKey == requestedDestinationKey)
        }
        if (candidate != null) {
            val recovered = when (candidate.status) {
                ExportJobStatus.QUEUED -> candidate
                ExportJobStatus.RUNNING,
                ExportJobStatus.CANCELLED,
                ExportJobStatus.FAILED,
                -> ExportJobReducer.recover(candidate, clock.millis())
                ExportJobStatus.SUCCEEDED -> error("successful job is not recoverable")
            }
            store.writeJob(recovered)
            return recovered
        }
        val destination = requireNotNull(requestedDestinationUri) { "destination was not selected" }
        val destinationKey = requireNotNull(requestedDestinationKey)
        val exportKey = ExportIdentity.exportKey(destinationKey, dependencies)
        val total = project.manifest.pages.size
        val now = clock.millis()
        return ExportJobRecord(
            jobId = idSource.nextId(),
            projectId = project.manifest.projectId,
            exportKey = exportKey,
            destinationUri = destination,
            destinationKey = destinationKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            pages = project.manifest.pages.sortedBy(PageRecord::order).map { page ->
                val typesettingEntry = typesettingRun.artifact.entries.single { it.pageOrder == page.order }
                val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == page.order }
                val source = when {
                    typesettingEntry.state == TypesettingPageState.COMMITTED -> ExportPageSource.FLATTENED
                    cleanupEntry.state == CleanupPageState.COMMITTED -> ExportPageSource.CLEANED_FALLBACK
                    else -> ExportPageSource.SOURCE_FALLBACK
                }
                // Exported bytes are copied verbatim from the artifact, so the
                // output name inherits the artifact's encoding; only the raw
                // source fallback is encoded here and gets to pick its own.
                val imageExtension = when (source) {
                    ExportPageSource.FLATTENED ->
                        imageExtensionOf(requireNotNull(typesettingEntry.imagePath))
                    ExportPageSource.CLEANED_FALLBACK ->
                        imageExtensionOf(requireNotNull(cleanupEntry.imagePath))
                    ExportPageSource.SOURCE_FALLBACK -> PageImageEncoder.preferredExtension
                }
                ExportJobPage(
                    pageId = page.pageId,
                    pageOrder = page.order,
                    sourceSha256 = page.sourceSha256,
                    typesettingPageArtifactKey = typesettingEntry.pageArtifactKey,
                    outputName = ExportIdentity.outputName(page.order, total, policy, imageExtension),
                    source = source,
                )
            },
        ).also(store::writeJob)
    }

    private fun resolvePage(
        project: ProjectRef,
        sourcePage: PageRecord,
        typesettingRun: PublishedTypesettingRun,
        cleanupRun: PublishedCleanupRun,
    ): ResolvedExportPage {
        val typesettingEntry = typesettingRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        if (typesettingEntry.state == TypesettingPageState.COMMITTED) {
            TypesettingArtifactStore(project.directory).readPublishedPage(
                typesettingRun.artifact.runArtifactKey,
                typesettingEntry,
            ) ?: throw FatalExportException("TYPESETTING_PAGE_INVALID")
            val path = resolveInside(typesettingRun.directory, requireNotNull(typesettingEntry.imagePath))
            val bytes = Files.readAllBytes(path)
            return ResolvedExportPage(bytes, ExportPageSource.FLATTENED)
        }
        val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        if (cleanupEntry.state == CleanupPageState.COMMITTED) {
            CleanupArtifactStore(project.directory).readPublishedPage(
                cleanupRun.artifact.runArtifactKey,
                cleanupEntry,
            ) ?: throw FatalExportException("CLEANUP_PAGE_INVALID")
            val path = resolveInside(cleanupRun.directory, requireNotNull(cleanupEntry.imagePath))
            val bytes = Files.readAllBytes(path)
            return ResolvedExportPage(bytes, ExportPageSource.CLEANED_FALLBACK)
        }
        val sourcePath = try {
            SourceFilePreflight.resolve(project.directory, sourcePage)
        } catch (failure: SourceFilePreflightException) {
            throw FatalExportException(failure.code)
        }
        val decoded = decoder.decode(sourcePath)
        return try {
            ResolvedExportPage(PageImageEncoder.encode(decoded.bitmap), ExportPageSource.SOURCE_FALLBACK)
        } finally {
            decoded.bitmap.recycle()
        }
    }

    private fun validateDependencies(
        project: ProjectRef,
        typesetting: PublishedTypesettingRun,
        cleanup: PublishedCleanupRun,
    ) {
        require(typesetting.artifact.projectId == project.manifest.projectId)
        require(cleanup.artifact.projectId == project.manifest.projectId)
        require(typesetting.artifact.dependencies.cleanupRunArtifactKey == cleanup.artifact.runArtifactKey)
        require(typesetting.artifact.entries.size == project.manifest.pages.size)
        require(cleanup.artifact.entries.size == project.manifest.pages.size)
        project.manifest.pages.forEach { page ->
            require(typesetting.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
            require(cleanup.artifact.entries.single { it.pageOrder == page.order }.pageId == page.pageId)
        }
    }

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/'))
        return root.resolve(relative).normalize().also { require(it.startsWith(root.normalize())) }
    }

    private fun imageExtensionOf(imagePath: String): String =
        imagePath.substringAfterLast('.', "png").lowercase()

    private fun ExportJobRecord.toProgress(
        currentPageOrder: Int? = pages.firstOrNull { it.state == ExportPageState.RUNNING }?.pageOrder,
        errorCode: String? = error?.code,
    ): ExportProgress {
        val committed = pages.filter { it.state == ExportPageState.COMMITTED }
        return ExportProgress(
            projectId = projectId,
            jobId = jobId,
            exportKey = exportKey,
            status = status,
            terminalPageCount = committed.size,
            totalPageCount = pages.size,
            flattenedPageCount = committed.count { it.source == ExportPageSource.FLATTENED },
            cleanedFallbackPageCount = committed.count { it.source == ExportPageSource.CLEANED_FALLBACK },
            sourceFallbackPageCount = committed.count { it.source == ExportPageSource.SOURCE_FALLBACK },
            reusedPageCount = committed.count { it.reusedExisting },
            currentPageOrder = currentPageOrder,
            errorCode = errorCode,
        )
    }

    private fun ExportJobRecord.toReport(finishedAt: Long): ExportReport = ExportReport(
        jobId = jobId,
        projectId = projectId,
        exportKey = exportKey,
        destinationKey = destinationKey,
        typesettingRunArtifactKey = dependencies.typesettingRunArtifactKey,
        qualityRunArtifactKey = dependencies.qualityRunArtifactKey,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAt,
        status = status,
        totalPageCount = pages.size,
        exportedPageCount = pages.count { it.state == ExportPageState.COMMITTED },
        flattenedPageCount = pages.count { it.source == ExportPageSource.FLATTENED },
        cleanedFallbackPageCount = pages.count { it.source == ExportPageSource.CLEANED_FALLBACK },
        sourceFallbackPageCount = pages.count { it.source == ExportPageSource.SOURCE_FALLBACK },
        reusedPageCount = pages.count { it.reusedExisting },
        totalByteCount = pages.sumOf { it.byteLength },
        retryCount = pages.sumOf { (it.attemptCount - 1).coerceAtLeast(0) },
    )

    private fun Throwable.safeErrorCode(): String = when (this) {
        is ExportDestinationException -> code
        is FatalExportException -> code
        is IllegalArgumentException -> "EXPORT_INPUT_INVALID"
        else -> "EXPORT_FAILED"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ResolvedExportPage(val bytes: ByteArray, val source: ExportPageSource)
    private class FatalExportException(val code: String) : RuntimeException(code)
}
