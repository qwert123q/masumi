package rs.masumi.app.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.quality.PageQualityArtifact
import rs.masumi.core.quality.QualityArtifactStore
import rs.masumi.core.quality.QualityDependencies
import rs.masumi.core.quality.QualityError
import rs.masumi.core.quality.QualityEvaluator
import rs.masumi.core.quality.QualityIdentity
import rs.masumi.core.quality.QualityJobPage
import rs.masumi.core.quality.QualityJobRecord
import rs.masumi.core.quality.QualityJobReducer
import rs.masumi.core.quality.QualityJobStatus
import rs.masumi.core.quality.QualityPageState
import rs.masumi.core.quality.QualityPageVerdict
import rs.masumi.core.quality.QualityPixelAudit
import rs.masumi.core.quality.QualityPolicy
import rs.masumi.core.quality.QualityReport
import rs.masumi.core.quality.QualityRunArtifact
import rs.masumi.core.quality.QualityRunEntry
import rs.masumi.core.quality.QualitySeverity
import rs.masumi.core.quality.isPublished
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingPageState

data class QualityProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: QualityJobStatus,
    val terminalPageCount: Int,
    val totalPageCount: Int,
    val warningPageCount: Int,
    val blockedPageCount: Int,
    val warningCount: Int,
    val blockingCount: Int,
    val currentPageOrder: Int? = null,
    val errorCode: String? = null,
)

data class QualityRunResult(
    val job: QualityJobRecord,
    val runArtifact: QualityRunArtifact? = null,
    val report: QualityReport? = null,
    val publishedDirectory: Path? = null,
)

class QualityRunner(
    workspaceRoot: Path,
    private val policy: QualityPolicy = QualityPolicy(),
    private val typesettingRunArtifactKey: String? = null,
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
        onProgress: (QualityProgress) -> Unit,
    ): QualityRunResult {
        externallyCancelled.set(false)
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val typesettingRun = requireNotNull(
            typesettingRunArtifactKey?.let { catalog.publishedTypesettingRun(projectId, it) }
                ?: catalog.latestPublishedTypesettingRun(projectId),
        ) { "completed typesetting run was not found" }
        val cleanupRun = requireNotNull(
            catalog.publishedCleanupRun(projectId, typesettingRun.artifact.dependencies.cleanupRunArtifactKey),
        ) { "typesetting cleanup dependency was not found" }
        validateDependencies(project, typesettingRun, cleanupRun)
        val dependencies = QualityDependencies(
            typesettingRunArtifactKey = typesettingRun.artifact.runArtifactKey,
            policy = policy,
        )
        val typesettingStore = TypesettingArtifactStore(project.directory)
        val typesettingPages = typesettingRun.artifact.entries.associate { entry ->
            entry.pageOrder to if (entry.state == TypesettingPageState.COMMITTED) {
                typesettingStore.readPublishedPage(typesettingRun.artifact.runArtifactKey, entry)
                    ?: throw FatalQualityException("TYPESETTING_PAGE_INVALID")
            } else {
                null
            }
        }
        val pageKeys = project.manifest.pages.associate { page ->
            val entry = typesettingRun.artifact.entries.single { it.pageOrder == page.order }
            val typesettingPage = typesettingPages[page.order]
            page.order to QualityIdentity.pageArtifactKey(
                page.order,
                page.sourceSha256,
                entry.pageArtifactKey,
                typesettingPage?.renderedImageSha256,
                dependencies,
            )
        }
        val runKey = QualityIdentity.runArtifactKey(
            project.manifest.pages.map { it.order to pageKeys.getValue(it.order) },
            dependencies,
        )
        val store = QualityArtifactStore(project.directory)
        readPublishedResult(store, project, runKey, dependencies)?.let { cached ->
            onProgress(cached.job.toProgress())
            return cached
        }
        var job = recoverOrCreateJob(store, project, typesettingRun, pageKeys, runKey, dependencies)
        store.prepareRun(job)
        val pageArtifacts = mutableMapOf<Int, PageQualityArtifact>()

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        fun persist(updated: QualityJobRecord, currentPageOrder: Int? = null): QualityJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentPageOrder))
            return updated
        }

        try {
            job = persist(QualityJobReducer.start(job, clock.millis()))
            project.manifest.pages.sortedBy(PageRecord::order).forEach { sourcePage ->
                val checkpoint = job.pages.single { it.pageOrder == sourcePage.order }
                when (checkpoint.state) {
                    QualityPageState.COMMITTED -> {
                        pageArtifacts[sourcePage.order] = store.readCommittedPage(job, checkpoint)
                            ?: throw FatalQualityException("COMMITTED_PAGE_INVALID")
                        return@forEach
                    }
                    QualityPageState.PENDING -> Unit
                    QualityPageState.RUNNING -> throw FatalQualityException("JOB_RECOVERY_INVALID")
                }
                if (isCancelled()) throw QualityCancellationSignal()
                job = persist(QualityJobReducer.startPage(job, sourcePage.order, clock.millis()), sourcePage.order)
                val typesettingEntry = typesettingRun.artifact.entries.single { it.pageOrder == sourcePage.order }
                val typesettingPage = typesettingPages[sourcePage.order]
                val pixelAudit = if (typesettingPage == null) {
                    null
                } else {
                    auditPixels(
                        project,
                        sourcePage,
                        typesettingRun,
                        cleanupRun,
                        typesettingPage,
                        isCancelled = ::isCancelled,
                    )
                }
                val artifact = QualityEvaluator.evaluate(
                    pageId = sourcePage.pageId,
                    pageOrder = sourcePage.order,
                    sourceSha256 = sourcePage.sourceSha256,
                    typesettingPageArtifactKey = typesettingEntry.pageArtifactKey,
                    pageArtifactKey = pageKeys.getValue(sourcePage.order),
                    dependencies = dependencies,
                    typesetting = typesettingPage,
                    pixelAudit = pixelAudit,
                )
                val artifactPath = store.commitPage(job, artifact)
                val warningCount = artifact.issues.count { it.severity == QualitySeverity.WARNING }
                val blockingCount = artifact.issues.count { it.severity == QualitySeverity.BLOCKING }
                job = persist(
                    QualityJobReducer.commitPage(
                        job,
                        sourcePage.order,
                        artifactPath,
                        artifact.verdict,
                        warningCount,
                        blockingCount,
                        clock.millis(),
                    ),
                    sourcePage.order,
                )
                pageArtifacts[sourcePage.order] = artifact
            }
            val finishedAt = clock.millis()
            job = QualityJobReducer.finish(job, finishedAt)
            val run = job.toRunArtifact(finishedAt)
            val report = job.toReport(finishedAt, pageArtifacts)
            val published = store.publishRun(job, run, report)
            job = persist(job)
            return QualityRunResult(job, run, report, published)
        } catch (_: QualityCancellationSignal) {
            if (job.status == QualityJobStatus.RUNNING) {
                if (!job.cancelRequested) job = persist(QualityJobReducer.requestCancellation(job, clock.millis()))
                job = persist(QualityJobReducer.finishCancellation(job, clock.millis()))
            }
            return QualityRunResult(job)
        } catch (failure: Throwable) {
            val error = QualityError(failure.safeErrorCode())
            if (job.status == QualityJobStatus.RUNNING || job.status == QualityJobStatus.QUEUED) {
                job = QualityJobReducer.fail(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return QualityRunResult(job)
        }
    }

    private fun auditPixels(
        project: ProjectRef,
        sourcePage: PageRecord,
        typesettingRun: PublishedTypesettingRun,
        cleanupRun: PublishedCleanupRun,
        typesettingPage: PageTypesettingArtifact,
        isCancelled: () -> Boolean,
    ): QualityPixelAudit {
        val typesettingEntry = typesettingRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        val cleanupEntry = cleanupRun.artifact.entries.single { it.pageOrder == sourcePage.order }
        if (cleanupEntry.state != CleanupPageState.COMMITTED) throw FatalQualityException("CLEANUP_PAGE_INVALID")
        val flattenedPath = resolveInside(typesettingRun.directory, requireNotNull(typesettingEntry.imagePath))
        val cleanedPath = resolveInside(cleanupRun.directory, requireNotNull(cleanupEntry.imagePath))
        val flattened = BitmapFactory.decodeFile(flattenedPath.toString())
            ?: throw FatalQualityException("FLATTENED_IMAGE_INVALID")
        val cleaned = BitmapFactory.decodeFile(cleanedPath.toString())
            ?: run {
                flattened.recycle()
                throw FatalQualityException("CLEANUP_IMAGE_INVALID")
            }
        try {
            return BitmapQualityAuditor().audit(cleaned, flattened, typesettingPage, isCancelled)
        } finally {
            cleaned.recycle()
            flattened.recycle()
        }
    }

    private fun recoverOrCreateJob(
        store: QualityArtifactStore,
        project: ProjectRef,
        typesettingRun: PublishedTypesettingRun,
        pageKeys: Map<Int, String>,
        runKey: String,
        dependencies: QualityDependencies,
    ): QualityJobRecord {
        val candidate = store.findResumableJob()?.takeIf {
            it.projectId == project.manifest.projectId && it.runArtifactKey == runKey && it.dependencies == dependencies
        }
        if (candidate != null) {
            candidate.pages.filter { it.state == QualityPageState.RUNNING }.forEach {
                store.cleanInterruptedPage(candidate, it.pageOrder)
            }
            val recovered = when (candidate.status) {
                QualityJobStatus.QUEUED -> candidate
                QualityJobStatus.RUNNING,
                QualityJobStatus.CANCELLED,
                -> QualityJobReducer.recoverInterrupted(candidate, clock.millis())
                else -> error("terminal quality job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }
        val now = clock.millis()
        return QualityJobRecord(
            jobId = idSource.nextId(),
            projectId = project.manifest.projectId,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            pages = project.manifest.pages.map { page ->
                val entry = typesettingRun.artifact.entries.single { it.pageOrder == page.order }
                QualityJobPage(
                    pageId = page.pageId,
                    pageOrder = page.order,
                    sourceSha256 = page.sourceSha256,
                    typesettingPageArtifactKey = entry.pageArtifactKey,
                    pageArtifactKey = pageKeys.getValue(page.order),
                )
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: QualityArtifactStore,
        project: ProjectRef,
        runKey: String,
        dependencies: QualityDependencies,
    ): QualityRunResult? {
        val run = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(run.projectId == project.manifest.projectId && run.dependencies == dependencies)
        val job = store.readJob(report.jobId) ?: return null
        require(job.status == report.status && job.status.isPublished())
        return QualityRunResult(job, run, report, project.directory.resolve("artifacts/quality/$runKey"))
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

    private fun QualityJobRecord.toRunArtifact(createdAt: Long): QualityRunArtifact = QualityRunArtifact(
        runArtifactKey = runArtifactKey,
        projectId = projectId,
        createdAtEpochMillis = createdAt,
        dependencies = dependencies,
        entries = pages.sortedBy(QualityJobPage::pageOrder).map { page ->
            QualityRunEntry(
                pageId = page.pageId,
                pageOrder = page.pageOrder,
                sourceSha256 = page.sourceSha256,
                typesettingPageArtifactKey = page.typesettingPageArtifactKey,
                pageArtifactKey = page.pageArtifactKey,
                verdict = requireNotNull(page.verdict),
                artifactPath = requireNotNull(page.artifactPath),
            )
        },
    )

    private fun QualityJobRecord.toReport(
        finishedAt: Long,
        artifacts: Map<Int, PageQualityArtifact>,
    ): QualityReport = QualityReport(
        jobId = jobId,
        projectId = projectId,
        runArtifactKey = runArtifactKey,
        typesettingRunArtifactKey = dependencies.typesettingRunArtifactKey,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAt,
        status = status,
        totalPageCount = pages.size,
        passedPageCount = pages.count { it.verdict == QualityPageVerdict.PASS },
        warningPageCount = pages.count { it.verdict == QualityPageVerdict.PASS_WITH_WARNINGS },
        blockedPageCount = pages.count { it.verdict == QualityPageVerdict.BLOCKED },
        warningCount = pages.sumOf(QualityJobPage::warningCount),
        blockingCount = pages.sumOf(QualityJobPage::blockingCount),
        verifiedTypesetRegionCount = artifacts.values.sumOf(PageQualityArtifact::verifiedTypesetRegionCount),
        preservedRegionCount = artifacts.values.sumOf(PageQualityArtifact::preservedRegionCount),
        retryCount = pages.sumOf { (it.attemptCount - 1).coerceAtLeast(0) },
    )

    private fun QualityJobRecord.toProgress(
        currentPageOrder: Int? = pages.firstOrNull { it.state == QualityPageState.RUNNING }?.pageOrder,
        errorCode: String? = error?.code,
    ): QualityProgress = QualityProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        terminalPageCount = pages.count { it.state == QualityPageState.COMMITTED },
        totalPageCount = pages.size,
        warningPageCount = pages.count { it.verdict == QualityPageVerdict.PASS_WITH_WARNINGS },
        blockedPageCount = pages.count { it.verdict == QualityPageVerdict.BLOCKED },
        warningCount = pages.sumOf(QualityJobPage::warningCount),
        blockingCount = pages.sumOf(QualityJobPage::blockingCount),
        currentPageOrder = currentPageOrder,
        errorCode = errorCode,
    )

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/'))
        return root.resolve(relative).normalize().also {
            require(it.startsWith(root.normalize()) && Files.isRegularFile(it))
        }
    }

    private fun Throwable.safeErrorCode(): String = when (this) {
        is FatalQualityException -> code
        is IllegalArgumentException -> "QUALITY_INPUT_INVALID"
        else -> "QUALITY_RUN_FAILED"
    }

    private class FatalQualityException(val code: String) : RuntimeException(code)
}

internal class QualityCancellationSignal : RuntimeException()
