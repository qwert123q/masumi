package rs.masumi.core.quality

import java.nio.file.Path
import java.util.UUID
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.QualityJson

class QualityArtifactStore(
    projectDirectory: Path,
    private val json: QualityJson = QualityJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: QualityJobRecord) {
        requireSafeId(job.jobId)
        requireSha256(job.runArtifactKey)
        val jobs = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobs)
        fileSystem.replaceUtf8(jobs.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): QualityJobRecord? {
        requireSafeId(jobId)
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching { json.decodeJob(fileSystem.readUtf8(path)).also { require(it.jobId == jobId) } }.getOrNull()
    }

    fun findResumableJob(): QualityJobRecord? = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter {
            it.status in setOf(
                QualityJobStatus.QUEUED,
                QualityJobStatus.RUNNING,
                QualityJobStatus.CANCELLED,
            )
        }
        .maxWithOrNull(compareBy<QualityJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun prepareRun(job: QualityJobRecord) {
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun cleanInterruptedPage(job: QualityJobRecord, pageOrder: Int) {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == QualityPageState.RUNNING)
        fileSystem.deleteRecursively(pageDirectory(checkpointDirectory(job), page))
    }

    fun commitPage(job: QualityJobRecord, artifact: PageQualityArtifact): String {
        require(job.status == QualityJobStatus.RUNNING)
        val page = job.pages.single { it.pageOrder == artifact.pageOrder }
        require(page.state == QualityPageState.RUNNING)
        requireValidPageArtifact(artifact, page, job.dependencies)
        val directory = pageDirectory(checkpointDirectory(job), page)
        fileSystem.createDirectories(directory)
        val artifactPath = "pages/${pagePathName(page)}/quality.json"
        replaceUnique(directory.resolve("quality.json"), json.encodePageArtifact(artifact).toByteArray(Charsets.UTF_8))
        require(
            readCommittedPage(
                job,
                page.copy(
                    state = QualityPageState.COMMITTED,
                    artifactPath = artifactPath,
                    verdict = artifact.verdict,
                    warningCount = artifact.issues.count { it.severity == QualitySeverity.WARNING },
                    blockingCount = artifact.issues.count { it.severity == QualitySeverity.BLOCKING },
                ),
            ) == artifact,
        )
        return artifactPath
    }

    fun readCommittedPage(job: QualityJobRecord, page: QualityJobPage): PageQualityArtifact? = runCatching {
        require(page.state == QualityPageState.COMMITTED)
        val path = resolveInside(checkpointDirectory(job), requireNotNull(page.artifactPath))
        require(fileSystem.exists(path))
        json.decodePageArtifact(fileSystem.readUtf8(path)).also { artifact ->
            requireValidPageArtifact(artifact, page, job.dependencies)
            require(artifact.verdict == page.verdict)
        }
    }.getOrNull()

    fun publishRun(job: QualityJobRecord, run: QualityRunArtifact, report: QualityReport): Path {
        require(job.status.isPublished())
        require(run.runArtifactKey == job.runArtifactKey && run.projectId == job.projectId)
        require(run.dependencies == job.dependencies)
        require(report.jobId == job.jobId && report.status == job.status)
        val staging = checkpointDirectory(job)
        require(validateRunFiles(staging, run))
        fileSystem.replaceUtf8(staging.resolve("artifact.json"), json.encodeRun(run))
        fileSystem.replaceUtf8(staging.resolve("report.json"), json.encodeReport(report))
        val target = publishedDirectory(job.runArtifactKey)
        if (!fileSystem.exists(target)) {
            fileSystem.createDirectories(target.parent)
            fileSystem.publishDirectory(staging, target)
        }
        require(readPublishedRun(job.runArtifactKey) == run)
        require(readPublishedReport(job.runArtifactKey) == report)
        return target
    }

    fun readPublishedRun(runKey: String): QualityRunArtifact? {
        requireSha256(runKey)
        val root = publishedDirectory(runKey)
        val path = root.resolve("artifact.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeRun(fileSystem.readUtf8(path)).also {
                require(it.runArtifactKey == runKey && validateRunFiles(root, it))
            }
        }.getOrNull()
    }

    fun readPublishedReport(runKey: String): QualityReport? {
        requireSha256(runKey)
        val path = publishedDirectory(runKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also {
                require(it.runArtifactKey == runKey && it.status.isPublished())
            }
        }.getOrNull()
    }

    fun readPublishedPage(runKey: String, entry: QualityRunEntry): PageQualityArtifact? = runCatching {
        val root = publishedDirectory(runKey)
        val path = resolveInside(root, entry.artifactPath)
        require(fileSystem.exists(path))
        json.decodePageArtifact(fileSystem.readUtf8(path)).also { artifact ->
            require(artifact.pageOrder == entry.pageOrder && artifact.pageArtifactKey == entry.pageArtifactKey)
            require(artifact.verdict == entry.verdict)
        }
    }.getOrNull()

    private fun validateRunFiles(root: Path, run: QualityRunArtifact): Boolean = run.entries.all { entry ->
        runCatching {
            val path = resolveInside(root, entry.artifactPath)
            require(fileSystem.exists(path))
            val artifact = json.decodePageArtifact(fileSystem.readUtf8(path))
            require(artifact.pageId == entry.pageId && artifact.pageOrder == entry.pageOrder)
            require(artifact.sourceSha256 == entry.sourceSha256)
            require(artifact.typesettingPageArtifactKey == entry.typesettingPageArtifactKey)
            require(artifact.pageArtifactKey == entry.pageArtifactKey)
            require(artifact.dependencies == run.dependencies)
            require(artifact.verdict == entry.verdict)
        }.isSuccess
    }

    private fun requireValidPageArtifact(
        artifact: PageQualityArtifact,
        page: QualityJobPage,
        dependencies: QualityDependencies,
    ) {
        require(artifact.schemaVersion == QUALITY_SCHEMA_VERSION)
        require(artifact.pageId == page.pageId && artifact.pageOrder == page.pageOrder)
        require(artifact.sourceSha256 == page.sourceSha256)
        require(artifact.typesettingPageArtifactKey == page.typesettingPageArtifactKey)
        require(artifact.pageArtifactKey == page.pageArtifactKey)
        require(artifact.dependencies == dependencies)
        artifact.renderedImageSha256?.let(::requireSha256)
        require(artifact.actualChangedPixelCount >= 0 && artifact.changedPixelsOutsideLayout >= 0)
        require(artifact.verifiedTypesetRegionCount >= 0 && artifact.preservedRegionCount >= 0)
        artifact.issues.forEach { issue ->
            issue.ocrRegionId?.let(::requireSha256)
            require(issue.observedCount == null || issue.observedCount >= 0)
        }
        val warnings = artifact.issues.count { it.severity == QualitySeverity.WARNING }
        val blocking = artifact.issues.count { it.severity == QualitySeverity.BLOCKING }
        require((artifact.verdict == QualityPageVerdict.BLOCKED) == (blocking > 0))
        require((artifact.verdict == QualityPageVerdict.PASS_WITH_WARNINGS) == (blocking == 0 && warnings > 0))
        require((artifact.verdict == QualityPageVerdict.PASS) == (blocking == 0 && warnings == 0))
    }

    private fun replaceUnique(path: Path, bytes: ByteArray) {
        if (fileSystem.exists(path)) {
            require(fileSystem.readBytes(path).contentEquals(bytes))
            return
        }
        val part = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.part")
        try {
            fileSystem.newOutputStream(part).use { it.write(bytes) }
            fileSystem.moveFile(part, path)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteIfExists(part) }
            throw failure
        }
    }

    private fun checkpointDirectory(job: QualityJobRecord): Path =
        projectDirectory.resolve("staging/quality/${job.jobId}/${job.runArtifactKey}").normalize()

    private fun publishedDirectory(runKey: String): Path =
        projectDirectory.resolve("artifacts/quality/$runKey").normalize()

    private fun pageDirectory(root: Path, page: QualityJobPage): Path = root.resolve("pages/${pagePathName(page)}")
    private fun pagePathName(page: QualityJobPage): String =
        "${page.pageOrder.toString().padStart(4, '0')}-${page.pageId}"

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank())
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root.normalize()))
        return resolved
    }

    private fun requireSafeId(value: String) = require(SAFE_ID.matches(value))
    private fun requireSha256(value: String) = require(SHA256.matches(value))

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
