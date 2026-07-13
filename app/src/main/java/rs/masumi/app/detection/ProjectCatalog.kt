package rs.masumi.app.detection

import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.serialization.ProjectJson
import java.nio.file.Files
import java.nio.file.Path

data class ProjectRef(
    val directory: Path,
    val manifest: ProjectManifest,
)

data class PublishedDetectionRun(
    val directory: Path,
    val artifact: DetectionRunArtifact,
    val report: DetectionReport,
)

class ProjectCatalog(
    workspaceRoot: Path,
    private val json: ProjectJson = ProjectJson(),
) {
    private val projectsDirectory = workspaceRoot.toAbsolutePath().normalize().resolve("projects")

    fun openProject(projectId: String): ProjectRef? {
        require(SAFE_ID.matches(projectId)) { "projectId contains unsafe characters" }
        val directory = projectsDirectory.resolve(projectId).normalize()
        require(directory.parent == projectsDirectory) { "project path escaped the workspace" }
        return readProject(directory)
    }

    fun latestProject(): ProjectRef? = directDirectories(projectsDirectory)
        .mapNotNull(::readProject)
        .maxWithOrNull(
            compareBy<ProjectRef> { it.manifest.createdAtEpochMillis }
                .thenBy { it.manifest.projectId },
        )

    fun latestPublishedRun(projectId: String): PublishedDetectionRun? {
        val project = openProject(projectId) ?: return null
        val artifactRoot = project.directory.resolve("artifacts/detection")
        val store = DetectionArtifactStore(project.directory)
        return directDirectories(artifactRoot)
            .mapNotNull { directory ->
                val runKey = directory.fileName.toString()
                if (!SHA256.matches(runKey)) return@mapNotNull null
                val artifact = store.readPublishedRun(runKey) ?: return@mapNotNull null
                val report = store.readPublishedReport(runKey) ?: return@mapNotNull null
                if (artifact.projectId != projectId || report.projectId != projectId) {
                    return@mapNotNull null
                }
                PublishedDetectionRun(directory, artifact, report)
            }
            .maxWithOrNull(
                compareBy<PublishedDetectionRun> { it.artifact.createdAtEpochMillis }
                    .thenBy { it.artifact.runArtifactKey },
            )
    }

    private fun readProject(directory: Path): ProjectRef? = runCatching {
        require(Files.isDirectory(directory))
        val manifestPath = directory.resolve("manifest.json")
        require(Files.isRegularFile(manifestPath))
        val manifest = Files.newBufferedReader(manifestPath, Charsets.UTF_8).use { reader ->
            json.decodeManifest(reader.readText())
        }
        require(manifest.schemaVersion == PROJECT_SCHEMA_VERSION)
        require(manifest.projectId == directory.fileName.toString())
        require(SAFE_ID.matches(manifest.projectId))
        require(manifest.pages.isNotEmpty())
        require(manifest.pages.map { it.order } == manifest.pages.indices.toList())
        ProjectRef(directory.toAbsolutePath().normalize(), manifest)
    }.getOrNull()

    private fun directDirectories(parent: Path): List<Path> {
        if (!Files.isDirectory(parent)) return emptyList()
        return Files.list(parent).use { entries ->
            entries.iterator().asSequence().filter(Files::isDirectory).toList()
        }
    }

    private companion object {
        const val PROJECT_SCHEMA_VERSION = 1
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
