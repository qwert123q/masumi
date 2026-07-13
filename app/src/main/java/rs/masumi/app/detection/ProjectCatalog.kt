package rs.masumi.app.detection

import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.serialization.DetectionJson
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
    private val detectionJson: DetectionJson = DetectionJson(),
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

    fun readPublishedPage(
        run: PublishedDetectionRun,
        pageId: String,
    ): PageDetectionArtifact? = runCatching {
        require(SHA256.matches(pageId)) { "pageId must be a SHA-256 digest" }
        val entries = run.artifact.entries.filter { it.pageId == pageId }
        require(entries.isNotEmpty()) { "page does not belong to detection run" }
        require(entries.all { it.state == DetectionPageState.COMMITTED }) {
            "detection page is not committed"
        }
        val relativePaths = entries.map { requireNotNull(it.regionsPath) }.distinct()
        require(relativePaths.size == 1) { "duplicate page entries disagree" }
        val path = run.directory.resolve(relativePaths.single()).normalize()
        require(path.startsWith(run.directory.normalize())) { "detection page path escaped run" }
        require(Files.isRegularFile(path)) { "detection page artifact is missing" }
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            detectionJson.decodePageArtifact(reader.readText())
        }.also { page ->
            require(page.pageId == pageId)
            require(page.sourceSha256 == pageId)
            require(entries.all { it.pageArtifactKey == page.pageArtifactKey })
            require(page.model == run.artifact.model)
            require(page.preprocessing == run.artifact.preprocessing)
            require(page.thresholds == run.artifact.thresholds)
        }
    }.getOrNull()

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
