package rs.masumi.app.pipeline

import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.identity.SafeOpaqueId
import rs.masumi.core.modelpackage.PinnedAotInpainter
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.typesetting.TypesettingPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Reclaims workspace space by deleting artifact runs that nothing references
 * anymore. Every policy-revision bump republishes a stage under a new run key
 * and leaves the previous run directory — full-page images included — behind,
 * so a chapter's footprint grows with every reprocess unless something sweeps
 * the graveyard.
 *
 * A run is deleted only when it is neither the newest published run of its
 * stage nor a dependency of a kept run. Anything else — including unpublished
 * runs, which cannot be told apart from a stage that is mid-write — is left
 * alone until it has been cold for [COLD_RUN_MINUTES]; the slowest stage
 * commits a page every few minutes, so a genuinely live run directory keeps a
 * fresh modification time somewhere in its first two levels.
 */
object WorkspaceJanitor {
    /** Sweeps one project and returns the number of bytes freed. */
    fun sweepProject(workspaceRoot: Path, projectId: String): Long {
        val catalog = ProjectCatalog(workspaceRoot)
        val project = catalog.openProject(projectId) ?: return 0
        val keep = mutableMapOf<String, MutableSet<String>>()
        fun keep(stage: String, runKey: String?) {
            runKey?.let { keep.getOrPut(stage) { mutableSetOf() }.add(it) }
        }

        fun keepOcr(run: PublishedOcrRun?) {
            run ?: return
            keep(STAGE_OCR, run.artifact.runArtifactKey)
            keep(STAGE_DETECTION, run.artifact.detectionRunArtifactKey)
        }
        fun keepTranslation(run: PublishedTranslationRun?) {
            run ?: return
            keep(STAGE_TRANSLATION, run.artifact.runArtifactKey)
            val ocrKey = run.artifact.dependencies.ocrRunArtifactKey
            keep(STAGE_OCR, ocrKey)
            keepOcr(catalog.publishedOcrRun(projectId, ocrKey))
        }
        fun keepCleanup(run: PublishedCleanupRun?) {
            run ?: return
            keep(STAGE_CLEANUP, run.artifact.runArtifactKey)
            val translationKey = run.artifact.dependencies.translationRunArtifactKey
            keep(STAGE_TRANSLATION, translationKey)
            keepTranslation(catalog.publishedTranslationRun(projectId, translationKey))
        }
        fun keepTypesetting(run: PublishedTypesettingRun?) {
            run ?: return
            keep(STAGE_TYPESETTING, run.artifact.runArtifactKey)
            val cleanupKey = run.artifact.dependencies.cleanupRunArtifactKey
            keep(STAGE_CLEANUP, cleanupKey)
            keepCleanup(catalog.publishedCleanupRun(projectId, cleanupKey))
        }

        // Always retain the newest complete artifact in every stage and its
        // entire dependency lineage. This is the rollback/reference chain when
        // a schema or policy bump intentionally makes that run non-current.
        val latestDetection = catalog.latestPublishedRun(projectId)
        keep(STAGE_DETECTION, latestDetection?.artifact?.runArtifactKey)
        keepOcr(catalog.latestPublishedOcrRun(projectId))
        keepTranslation(catalog.latestPublishedTranslationRun(projectId))
        keepCleanup(catalog.latestPublishedCleanupRun(projectId))
        keepTypesetting(catalog.latestPublishedTypesettingRun(projectId))

        // Also retain the current compatible lineage. During a staged rerun it
        // can coexist with a newer timestamped artifact from an old identity.
        val currentDetection = catalog.publishedDetectionRuns(projectId).firstOrNull {
            PipelineArtifactFreshness.detection(it.artifact, project.manifest)
        }
        keep(STAGE_DETECTION, currentDetection?.artifact?.runArtifactKey)
        val currentOcr = currentDetection?.let { detection ->
            catalog.publishedOcrRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.ocr(
                    it.artifact,
                    detection.artifact,
                )
            }
        }
        keepOcr(currentOcr)
        val currentTranslation = currentOcr?.let { ocr ->
            catalog.publishedTranslationRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.translation(
                    it.artifact,
                    ocr.artifact,
                )
            }
        }
        keepTranslation(currentTranslation)
        val currentCleanup = currentTranslation?.let { translation ->
            catalog.latestPublishedCleanupRun(
                projectId = projectId,
                translationRunArtifactKey = translation.artifact.runArtifactKey,
                policy = CleanupPolicy(),
                maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
                neuralModel = PinnedAotInpainter.descriptor.toModelRef(),
            )?.takeIf {
                PipelineArtifactFreshness.cleanup(
                    it.artifact,
                    translation.artifact,
                )
            }
        }
        keepCleanup(currentCleanup)
        val currentTypesetting = currentCleanup?.let { cleanup ->
            catalog.latestPublishedTypesettingRun(
                projectId = projectId,
                cleanupRunArtifactKey = cleanup.artifact.runArtifactKey,
                policy = TypesettingPolicy(),
            )?.takeIf {
                PipelineArtifactFreshness.typesetting(it.artifact, cleanup.artifact)
            }
        }
        keepTypesetting(currentTypesetting)

        val cutoff = FileTime.from(
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(COLD_RUN_MINUTES),
            TimeUnit.MILLISECONDS,
        )
        var freed = 0L
        STAGES.forEach { stage ->
            freed += sweepStage(
                project.directory.resolve("artifacts").resolve(stage),
                keep[stage].orEmpty(),
                cutoff,
            )
        }
        freed += sweepAbandonedTemporaries(workspaceRoot, cutoff)
        return freed
    }

    internal fun sweepStage(stageRoot: Path, keep: Set<String>, cutoff: FileTime): Long {
        var freed = 0L
        directDirectories(stageRoot).forEach { runDirectory ->
            val runKey = runDirectory.fileName.toString()
            if (!SafeOpaqueId.isValid(runKey)) return@forEach
            if (runKey in keep) return@forEach
            if (touchedSince(runDirectory, cutoff)) return@forEach
            freed += deleteRecursively(runDirectory)
        }
        return freed
    }

    /** Sweeps every project whose pipeline is idle; returns bytes freed. */
    fun sweepAll(workspaceRoot: Path, isProjectBusy: (String) -> Boolean): Long {
        val projectsRoot = workspaceRoot.toAbsolutePath().normalize().resolve("projects")
        return directDirectories(projectsRoot).sumOf { directory ->
            val projectId = directory.fileName.toString()
            if (isProjectBusy(projectId)) 0L else runCatching {
                sweepProject(workspaceRoot, projectId)
            }.getOrDefault(0L)
        }
    }

    /**
     * Interrupted atomic writes leave `*.tmp` files at the workspace root;
     * they are never reused, only replaced.
     */
    private fun sweepAbandonedTemporaries(workspaceRoot: Path, cutoff: FileTime): Long {
        var freed = 0L
        val root = workspaceRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return 0
        Files.list(root).use { entries ->
            entries.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmp") }
                .filter { runCatching { Files.getLastModifiedTime(it) < cutoff }.getOrDefault(false) }
                .forEach { path ->
                    val size = runCatching { Files.size(path) }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) freed += size
                }
        }
        return freed
    }

    /** True when the directory or anything in its first two levels is fresh. */
    private fun touchedSince(directory: Path, cutoff: FileTime): Boolean {
        fun fresh(path: Path): Boolean =
            runCatching { Files.getLastModifiedTime(path) >= cutoff }.getOrDefault(true)
        if (fresh(directory)) return true
        directDirectories(directory).forEach { child ->
            if (fresh(child)) return true
            directDirectories(child).forEach { grandchild ->
                if (fresh(grandchild)) return true
            }
        }
        return false
    }

    private fun deleteRecursively(root: Path): Long {
        var freed = 0L
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    val size = if (Files.isRegularFile(path)) {
                        runCatching { Files.size(path) }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    try {
                        Files.deleteIfExists(path)
                        freed += size
                    } catch (_: IOException) {
                        // Leave whatever refuses to delete; the next sweep retries.
                    }
                }
            }
        }
        return freed
    }

    private fun directDirectories(parent: Path): List<Path> {
        if (!Files.isDirectory(parent)) return emptyList()
        return runCatching {
            Files.list(parent).use { entries ->
                entries.iterator().asSequence().filter(Files::isDirectory).toList()
            }
        }.getOrDefault(emptyList())
    }

    private const val STAGE_DETECTION = "detection"
    private const val STAGE_OCR = "ocr"
    private const val STAGE_TRANSLATION = "translation"
    private const val STAGE_CLEANUP = "cleanup"
    private const val STAGE_TYPESETTING = "typesetting"

    // No current lineage depends on this retired stage; keeping its directory
    // name in the sweep list reclaims cold artifacts left by older installs.
    private const val LEGACY_STAGE_QUALITY = "quality"
    private val STAGES = listOf(
        STAGE_DETECTION,
        STAGE_OCR,
        STAGE_TRANSLATION,
        STAGE_CLEANUP,
        STAGE_TYPESETTING,
        LEGACY_STAGE_QUALITY,
    )
    private const val COLD_RUN_MINUTES = 30L
}
