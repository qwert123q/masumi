package rs.masumi.app.pipeline

import java.nio.file.Path
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportJobStatus

internal data class ProjectPipelineState(
    val projectId: String,
    val pageCount: Int,
    val nextStage: PipelineStage?,
    val complete: Boolean = false,
    val blocked: Boolean = false,
    val errorCode: String? = null,
)

internal class ProjectPipelineStateReader(workspaceRoot: Path) {
    private val artifacts = CurrentPipelineArtifactsReader(workspaceRoot)

    fun read(projectId: String): ProjectPipelineState? = runCatching {
        val current = artifacts.read(projectId) ?: return null
        val project = current.project
        val detection = current.detection
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.DETECTION)
        val ocr = current.ocr
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.OCR)
        val translation = current.translation
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.TRANSLATION)
        current.cleanup
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.CLEANUP)
        val typesetting = current.typesetting
            ?: return ProjectPipelineState(projectId, project.manifest.pages.size, PipelineStage.TYPESETTING)
        val exportStore = ExportArtifactStore(project.directory)
        val exported = exportStore.findLatestJob()?.takeIf { job ->
            job.projectId == projectId &&
                job.dependencies.typesettingRunArtifactKey == typesetting.artifact.runArtifactKey &&
                job.status == ExportJobStatus.SUCCEEDED &&
                exportStore.readReport(job.jobId) != null
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
