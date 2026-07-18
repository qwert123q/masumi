package rs.masumi.app.quality

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.typesetting.TypesettingProgress
import rs.masumi.app.typesetting.TypesettingRunResult
import rs.masumi.app.typesetting.TypesettingRunner
import rs.masumi.core.quality.PageQualityArtifact
import rs.masumi.core.quality.QualityArtifactStore
import rs.masumi.core.quality.QualityJobStatus
import rs.masumi.core.quality.QualityRepairPlan
import rs.masumi.core.quality.QualityRepairPlanner
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.isSuccessful

data class QualityRepairResult(
    val initialQuality: QualityRunResult,
    val repairPlan: QualityRepairPlan? = null,
    val repairedTypesetting: TypesettingRunResult? = null,
    val finalQuality: QualityRunResult? = null,
)

class QualityRepairCoordinator(workspaceRoot: Path) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val catalog = ProjectCatalog(this.workspaceRoot)
    private val externallyCancelled = AtomicBoolean(false)

    fun cancel() {
        externallyCancelled.set(true)
    }

    fun run(
        projectId: String,
        cancellation: () -> Boolean,
        onProgress: (QualityProgress) -> Unit,
    ): QualityRepairResult {
        externallyCancelled.set(false)
        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        var blockedProgress: QualityProgress? = null
        val initial = QualityRunner(workspaceRoot).run(projectId, ::isCancelled) { progress ->
            if (progress.status == QualityJobStatus.BLOCKED) blockedProgress = progress else onProgress(progress)
        }
        if (initial.job.status != QualityJobStatus.BLOCKED || initial.runArtifact == null) {
            return QualityRepairResult(initialQuality = initial)
        }
        val project = requireNotNull(catalog.openProject(projectId))
        val typesetting = requireNotNull(
            catalog.publishedTypesettingRun(projectId, initial.job.dependencies.typesettingRunArtifactKey),
        )
        val qualityPages = readQualityPages(project.directory, initial)
        val plan = QualityRepairPlanner.plan(
            qualityRunArtifactKey = initial.runArtifact.runArtifactKey,
            typesettingRunArtifactKey = typesetting.artifact.runArtifactKey,
            typesettingPolicy = typesetting.artifact.dependencies.policy,
            pageArtifacts = qualityPages,
        )
        if (plan.exhausted || plan.pages.isEmpty() || isCancelled()) {
            onProgress(
                if (isCancelled()) requireNotNull(blockedProgress).copy(status = QualityJobStatus.CANCELLED)
                else requireNotNull(blockedProgress),
            )
            return QualityRepairResult(initialQuality = initial, repairPlan = plan)
        }
        val repairPolicy = QualityRepairPlanner.repairTypesettingPolicy(
            typesetting.artifact.dependencies.policy,
            plan.attempt,
        )
        val repairRunner = TypesettingRunner(
            workspaceRoot = workspaceRoot,
            policy = repairPolicy,
            reuseRunArtifactKey = typesetting.artifact.runArtifactKey,
            reprocessPageOrders = plan.repairPageOrders(),
        )
        val repaired = repairRunner.run(projectId, ::isCancelled) { progress ->
            onProgress(progress.asQualityRepairProgress(requireNotNull(blockedProgress)))
        }
        if (!repaired.job.status.isSuccessful() || repaired.runArtifact == null || isCancelled()) {
            onProgress(
                if (isCancelled()) requireNotNull(blockedProgress).copy(status = QualityJobStatus.CANCELLED)
                else requireNotNull(blockedProgress),
            )
            return QualityRepairResult(initial, plan, repaired)
        }
        val finalQuality = QualityRunner(
            workspaceRoot = workspaceRoot,
            typesettingRunArtifactKey = repaired.runArtifact.runArtifactKey,
        ).run(projectId, ::isCancelled, onProgress)
        return QualityRepairResult(initial, plan, repaired, finalQuality)
    }

    private fun readQualityPages(projectDirectory: Path, result: QualityRunResult): List<PageQualityArtifact> {
        val run = requireNotNull(result.runArtifact)
        val store = QualityArtifactStore(projectDirectory)
        return run.entries.sortedBy { it.pageOrder }.map { entry ->
            store.readPublishedPage(run.runArtifactKey, entry)
                ?: throw IllegalStateException("published quality page was invalid")
        }
    }

    private fun TypesettingProgress.asQualityRepairProgress(blocked: QualityProgress): QualityProgress =
        blocked.copy(
            status = when (status) {
                TypesettingJobStatus.CANCELLED -> QualityJobStatus.CANCELLED
                TypesettingJobStatus.FAILED -> QualityJobStatus.BLOCKED
                else -> QualityJobStatus.RUNNING
            },
            terminalPageCount = terminalPageCount,
            totalPageCount = totalPageCount,
            currentPageOrder = currentPageOrder,
            errorCode = REPAIRING_STATUS_CODE,
        )

    private companion object {
        const val REPAIRING_STATUS_CODE = "QUALITY_REPAIRING"
    }
}
