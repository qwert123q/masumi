package rs.masumi.app.detection

import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.PageOcrArtifact
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupMaskModelRef
import rs.masumi.core.cleanup.CleanupNeuralModelRef
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.PageCleanupArtifact
import rs.masumi.core.serialization.TypesettingJson
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRunArtifact
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationRunArtifact
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

data class PublishedOcrRun(
    val directory: Path,
    val artifact: OcrRunArtifact,
    val report: OcrReport,
)

data class PublishedTranslationRun(
    val directory: Path,
    val artifact: TranslationRunArtifact,
    val report: TranslationReport,
)

data class PublishedCleanupRun(
    val directory: Path,
    val artifact: CleanupRunArtifact,
    val report: CleanupReport,
)

data class PublishedTypesettingRun(
    val directory: Path,
    val artifact: TypesettingRunArtifact,
    val report: TypesettingReport,
)

class ProjectCatalog(
    workspaceRoot: Path,
    private val json: ProjectJson = ProjectJson(),
    private val detectionJson: DetectionJson = DetectionJson(),
    private val ocrJson: OcrJson = OcrJson(),
    private val translationJson: TranslationJson = TranslationJson(),
    private val typesettingJson: TypesettingJson = TypesettingJson(),
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

    fun publishedOcrRuns(projectId: String): List<PublishedOcrRun> {
        val project = openProject(projectId) ?: return emptyList()
        val artifactRoot = project.directory.resolve("artifacts/ocr")
        val store = OcrArtifactStore(project.directory)
        return directDirectories(artifactRoot)
            .mapNotNull { directory ->
                val runKey = directory.fileName.toString()
                if (!SHA256.matches(runKey)) return@mapNotNull null
                val artifact = store.readPublishedRun(runKey) ?: return@mapNotNull null
                val report = store.readPublishedReport(runKey) ?: return@mapNotNull null
                if (artifact.projectId != projectId || report.projectId != projectId) {
                    return@mapNotNull null
                }
                PublishedOcrRun(directory, artifact, report)
            }
            .sortedWith(
                compareByDescending<PublishedOcrRun> { it.artifact.createdAtEpochMillis }
                    .thenByDescending { it.artifact.runArtifactKey },
            )
    }

    fun latestPublishedOcrRun(projectId: String): PublishedOcrRun? =
        publishedOcrRuns(projectId).firstOrNull()

    fun publishedOcrRun(projectId: String, runKey: String): PublishedOcrRun? {
        if (!SHA256.matches(runKey)) return null
        val project = openProject(projectId) ?: return null
        val store = OcrArtifactStore(project.directory)
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        if (artifact.projectId != projectId || report.projectId != projectId) return null
        return PublishedOcrRun(project.directory.resolve("artifacts/ocr/$runKey"), artifact, report)
    }

    fun readPublishedOcrPage(run: PublishedOcrRun, pageId: String): PageOcrArtifact? = runCatching {
        require(SHA256.matches(pageId))
        val entries = run.artifact.entries.filter { it.pageId == pageId }
        require(entries.isNotEmpty() && entries.all { it.state == OcrPageState.COMMITTED })
        val relativePaths = entries.map { requireNotNull(it.artifactPath) }.distinct()
        require(relativePaths.size == 1)
        val path = run.directory.resolve(relativePaths.single()).normalize()
        require(path.startsWith(run.directory.normalize()) && Files.isRegularFile(path))
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            ocrJson.decodePageArtifact(reader.readText())
        }.also { page ->
            require(page.pageId == pageId)
            require(page.dependencies == run.artifact.dependencies)
            require(entries.all { it.pageArtifactKey == page.pageArtifactKey })
        }
    }.getOrNull()

    fun publishedTranslationRuns(projectId: String): List<PublishedTranslationRun> {
        val project = openProject(projectId) ?: return emptyList()
        val artifactRoot = project.directory.resolve("artifacts/translation")
        val store = TranslationArtifactStore(project.directory, translationJson)
        return directDirectories(artifactRoot)
            .mapNotNull { directory ->
                val runKey = directory.fileName.toString()
                if (!SHA256.matches(runKey)) return@mapNotNull null
                val artifact = store.readPublishedRun(runKey) ?: return@mapNotNull null
                val report = store.readPublishedReport(runKey) ?: return@mapNotNull null
                if (artifact.projectId != projectId || report.projectId != projectId) return@mapNotNull null
                PublishedTranslationRun(directory, artifact, report)
            }
            .sortedWith(
                compareByDescending<PublishedTranslationRun> { it.artifact.createdAtEpochMillis }
                    .thenByDescending { it.artifact.runArtifactKey },
            )
    }

    fun latestPublishedTranslationRun(projectId: String): PublishedTranslationRun? =
        publishedTranslationRuns(projectId).firstOrNull()

    fun publishedTranslationRun(projectId: String, runKey: String): PublishedTranslationRun? {
        if (!SHA256.matches(runKey)) return null
        val project = openProject(projectId) ?: return null
        val store = TranslationArtifactStore(project.directory, translationJson)
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        if (artifact.projectId != projectId || report.projectId != projectId) return null
        return PublishedTranslationRun(project.directory.resolve("artifacts/translation/$runKey"), artifact, report)
    }

    fun latestPublishedCleanupRun(
        projectId: String,
        translationRunArtifactKey: String? = null,
        policy: CleanupPolicy? = null,
        maskModel: CleanupMaskModelRef? = null,
        neuralModel: CleanupNeuralModelRef? = null,
    ): PublishedCleanupRun? {
        val project = openProject(projectId) ?: return null
        val artifactRoot = project.directory.resolve("artifacts/cleanup")
        val store = CleanupArtifactStore(project.directory)
        return directDirectories(artifactRoot)
            .mapNotNull { directory ->
                val runKey = directory.fileName.toString()
                if (!SHA256.matches(runKey)) return@mapNotNull null
                val artifact = store.readPublishedRun(runKey) ?: return@mapNotNull null
                val report = store.readPublishedReport(runKey) ?: return@mapNotNull null
                if (artifact.projectId != projectId || report.projectId != projectId) return@mapNotNull null
                if (
                    translationRunArtifactKey != null &&
                    artifact.dependencies.translationRunArtifactKey != translationRunArtifactKey
                ) return@mapNotNull null
                if (policy != null && artifact.dependencies.policy != policy) return@mapNotNull null
                if (maskModel != null && artifact.dependencies.maskModel != maskModel) {
                    return@mapNotNull null
                }
                if (neuralModel != null && artifact.dependencies.neuralModel != neuralModel) {
                    return@mapNotNull null
                }
                PublishedCleanupRun(directory, artifact, report)
            }
            .maxWithOrNull(
                compareBy<PublishedCleanupRun> { it.artifact.createdAtEpochMillis }
                    .thenBy { it.artifact.runArtifactKey },
            )
    }

    fun publishedCleanupRun(projectId: String, runKey: String): PublishedCleanupRun? {
        if (!SHA256.matches(runKey)) return null
        val project = openProject(projectId) ?: return null
        val store = CleanupArtifactStore(project.directory)
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        if (artifact.projectId != projectId || report.projectId != projectId) return null
        return PublishedCleanupRun(project.directory.resolve("artifacts/cleanup/$runKey"), artifact, report)
    }

    fun readPublishedCleanupPage(run: PublishedCleanupRun, pageOrder: Int): PageCleanupArtifact? {
        val entry = run.artifact.entries.singleOrNull { it.pageOrder == pageOrder } ?: return null
        return CleanupArtifactStore(run.directory.parent.parent.parent).readPublishedPage(
            run.artifact.runArtifactKey,
            entry,
        )
    }

    fun latestPublishedTypesettingRun(
        projectId: String,
        cleanupRunArtifactKey: String? = null,
        policy: TypesettingPolicy? = null,
    ): PublishedTypesettingRun? {
        val project = openProject(projectId) ?: return null
        val artifactRoot = project.directory.resolve("artifacts/typesetting")
        val store = TypesettingArtifactStore(project.directory, typesettingJson)
        return directDirectories(artifactRoot)
            .mapNotNull { directory ->
                val runKey = directory.fileName.toString()
                if (!SHA256.matches(runKey)) return@mapNotNull null
                val artifact = store.readPublishedRun(runKey) ?: return@mapNotNull null
                val report = store.readPublishedReport(runKey) ?: return@mapNotNull null
                if (artifact.projectId != projectId || report.projectId != projectId) return@mapNotNull null
                if (
                    cleanupRunArtifactKey != null &&
                    artifact.dependencies.cleanupRunArtifactKey != cleanupRunArtifactKey
                ) return@mapNotNull null
                if (policy != null && artifact.dependencies.policy != policy) return@mapNotNull null
                PublishedTypesettingRun(directory, artifact, report)
            }
            .maxWithOrNull(
                compareBy<PublishedTypesettingRun> { it.artifact.createdAtEpochMillis }
                    .thenBy { it.artifact.runArtifactKey },
            )
    }

    fun publishedTypesettingRun(projectId: String, runKey: String): PublishedTypesettingRun? {
        if (!SHA256.matches(runKey)) return null
        val project = openProject(projectId) ?: return null
        val store = TypesettingArtifactStore(project.directory, typesettingJson)
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        if (artifact.projectId != projectId || report.projectId != projectId) return null
        return PublishedTypesettingRun(project.directory.resolve("artifacts/typesetting/$runKey"), artifact, report)
    }

    fun readPublishedTypesettingPage(
        run: PublishedTypesettingRun,
        pageOrder: Int,
    ): PageTypesettingArtifact? {
        val entry = run.artifact.entries.singleOrNull { it.pageOrder == pageOrder } ?: return null
        return TypesettingArtifactStore(run.directory.parent.parent.parent, typesettingJson).readPublishedPage(
            run.artifact.runArtifactKey,
            entry,
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
