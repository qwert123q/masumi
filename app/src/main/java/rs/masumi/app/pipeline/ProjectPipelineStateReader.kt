package rs.masumi.app.pipeline

import java.nio.file.Path
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.quality.QualityPolicy
import rs.masumi.core.quality.allowsExport
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.typesetting.TypesettingPolicy

internal data class ProjectPipelineState(
    val projectId: String,
    val pageCount: Int,
    val nextStage: PipelineStage?,
    val complete: Boolean = false,
    val blocked: Boolean = false,
    val errorCode: String? = null,
)

internal class ProjectPipelineStateReader(workspaceRoot: Path) {
    private val catalog = ProjectCatalog(workspaceRoot.toAbsolutePath().normalize())

    fun read(projectId: String): ProjectPipelineState? = runCatching {
        val project = catalog.openProject(projectId) ?: return null
        val detection = catalog.latestPublishedRun(projectId)
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.DETECTION)
        val ocr = catalog.latestPublishedOcrRun(projectId)
            ?.takeIf { it.artifact.detectionRunArtifactKey == detection.artifact.runArtifactKey }
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.OCR)
        val translation = catalog.latestPublishedTranslationRun(projectId)
            ?.takeIf {
                it.artifact.dependencies.ocrRunArtifactKey == ocr.artifact.runArtifactKey &&
                    it.artifact.dependencies.policy == TranslationPolicy() &&
                    it.artifact.dependencies.prompt == TranslationPromptRef() &&
                    it.artifact.dependencies.batching == TranslationBatchingConfig()
            }
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.TRANSLATION)
        val cleanup = catalog.latestPublishedCleanupRun(
            projectId = projectId,
            translationRunArtifactKey = translation.artifact.runArtifactKey,
            policy = CleanupPolicy(),
        ) ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.CLEANUP)
        val typesetting = catalog.latestPublishedTypesettingRun(
            projectId = projectId,
            cleanupRunArtifactKey = cleanup.artifact.runArtifactKey,
            policy = TypesettingPolicy(),
        ) ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.TYPESETTING)
        val quality = catalog.latestPublishedQualityRun(
            projectId = projectId,
            typesettingRunArtifactKey = typesetting.artifact.runArtifactKey,
            policy = QualityPolicy(),
        ) ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.QUALITY)
        if (!quality.report.status.allowsExport()) {
            return ProjectPipelineState(
                projectId = projectId,
                pageCount = project.manifest.pages.size,
                nextStage = null,
                blocked = true,
                errorCode = quality.report.error?.code ?: "QUALITY_BLOCKED",
            )
        }
        val exported = ExportArtifactStore(project.directory).findLatestJob()?.takeIf { job ->
            job.projectId == projectId &&
                job.dependencies.typesettingRunArtifactKey == typesetting.artifact.runArtifactKey &&
                job.dependencies.qualityRunArtifactKey == quality.artifact.runArtifactKey &&
                job.status == ExportJobStatus.SUCCEEDED
        }
        if (exported != null) {
            ProjectPipelineState(
                projectId = projectId,
                pageCount = project.manifest.pages.size,
                nextStage = null,
                complete = true,
            )
        } else {
            ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.EXPORT)
        }
    }.getOrNull()
}
