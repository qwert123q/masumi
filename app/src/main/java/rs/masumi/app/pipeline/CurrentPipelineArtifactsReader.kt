package rs.masumi.app.pipeline

import java.nio.file.Path
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.core.typesetting.TypesettingPolicy

internal data class CurrentPipelineArtifacts(
    val project: ProjectRef,
    val detection: PublishedDetectionRun?,
    val ocr: PublishedOcrRun?,
    val translation: PublishedTranslationRun?,
    val cleanup: PublishedCleanupRun?,
    val typesetting: PublishedTypesettingRun?,
)

/** Resolves one compatible dependency chain so scheduling and execution cannot diverge. */
internal class CurrentPipelineArtifactsReader(
    private val catalog: ProjectCatalog,
) {
    constructor(workspaceRoot: Path) : this(ProjectCatalog(workspaceRoot.toAbsolutePath().normalize()))

    fun read(projectId: String): CurrentPipelineArtifacts? {
        val project = catalog.openProject(projectId) ?: return null
        val detection = catalog.latestPublishedRun(projectId)
        val ocr = detection?.let { currentDetection ->
            catalog.publishedOcrRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.ocr(
                    it.artifact,
                    currentDetection.artifact.runArtifactKey,
                )
            }
        }
        val translation = ocr?.let { currentOcr ->
            catalog.publishedTranslationRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.translation(
                    it.artifact,
                    currentOcr.artifact.runArtifactKey,
                )
            }
        }
        val cleanup = translation?.let { currentTranslation ->
            val dependencies = currentCleanupDependencies(currentTranslation.artifact.runArtifactKey)
            catalog.latestPublishedCleanupRun(
                projectId = projectId,
                translationRunArtifactKey = dependencies.translationRunArtifactKey,
                policy = dependencies.policy,
                maskModel = dependencies.maskModel,
                neuralModel = dependencies.neuralModel,
            )?.takeIf {
                PipelineArtifactFreshness.cleanup(
                    it.artifact,
                    currentTranslation.artifact.runArtifactKey,
                )
            }
        }
        val typesetting = cleanup?.let { currentCleanup ->
            catalog.latestPublishedTypesettingRun(
                projectId = projectId,
                cleanupRunArtifactKey = currentCleanup.artifact.runArtifactKey,
                policy = TypesettingPolicy(),
            )
        }
        return CurrentPipelineArtifacts(project, detection, ocr, translation, cleanup, typesetting)
    }
}
