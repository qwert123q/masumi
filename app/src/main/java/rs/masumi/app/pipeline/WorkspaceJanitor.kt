package rs.masumi.app.pipeline

import rs.masumi.app.detection.ProjectCatalog
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

        val detection = catalog.latestPublishedRun(projectId)
        keep(STAGE_DETECTION, detection?.artifact?.runArtifactKey)
        val ocr = catalog.latestPublishedOcrRun(projectId)
        keep(STAGE_OCR, ocr?.artifact?.runArtifactKey)
        keep(STAGE_DETECTION, ocr?.artifact?.detectionRunArtifactKey)
        val translation = catalog.latestPublishedTranslationRun(projectId)
        keep(STAGE_TRANSLATION, translation?.artifact?.runArtifactKey)
        keep(STAGE_OCR, translation?.artifact?.dependencies?.ocrRunArtifactKey)
        val cleanup = catalog.latestPublishedCleanupRun(projectId)
        keep(STAGE_CLEANUP, cleanup?.artifact?.runArtifactKey)
        keep(STAGE_TRANSLATION, cleanup?.artifact?.dependencies?.translationRunArtifactKey)
        val typesetting = catalog.latestPublishedTypesettingRun(projectId)
        keep(STAGE_TYPESETTING, typesetting?.artifact?.runArtifactKey)
        keep(STAGE_CLEANUP, typesetting?.artifact?.dependencies?.cleanupRunArtifactKey)
        val quality = catalog.latestPublishedQualityRun(projectId)
        keep(STAGE_QUALITY, quality?.artifact?.runArtifactKey)
        keep(STAGE_TYPESETTING, quality?.artifact?.dependencies?.typesettingRunArtifactKey)

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
            if (!SHA256.matches(runKey)) return@forEach
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
    private const val STAGE_QUALITY = "quality"
    private val STAGES = listOf(
        STAGE_DETECTION,
        STAGE_OCR,
        STAGE_TRANSLATION,
        STAGE_CLEANUP,
        STAGE_TYPESETTING,
        STAGE_QUALITY,
    )
    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val COLD_RUN_MINUTES = 30L
}
