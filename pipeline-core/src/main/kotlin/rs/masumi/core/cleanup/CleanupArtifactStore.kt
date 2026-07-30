package rs.masumi.core.cleanup

import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.CleanupJson

class CleanupArtifactStore(
    projectDirectory: Path,
    private val json: CleanupJson = CleanupJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: CleanupJobRecord) {
        requireSafeId(job.jobId)
        requireSha256(job.runArtifactKey)
        val jobs = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobs)
        fileSystem.replaceUtf8(jobs.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): CleanupJobRecord? {
        requireSafeId(jobId)
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching { json.decodeJob(fileSystem.readUtf8(path)).also { require(it.jobId == jobId) } }.getOrNull()
    }

    fun findResumableJob(): CleanupJobRecord? = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter { it.status in setOf(CleanupJobStatus.QUEUED, CleanupJobStatus.RUNNING, CleanupJobStatus.CANCELLED) }
        .maxWithOrNull(compareBy<CleanupJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun prepareRun(job: CleanupJobRecord) {
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun cleanInterruptedPage(job: CleanupJobRecord, pageOrder: Int) {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == CleanupPageState.RUNNING)
        fileSystem.deleteRecursively(pageDirectory(checkpointDirectory(job), page))
    }

    fun commitPage(
        job: CleanupJobRecord,
        artifact: PageCleanupArtifact,
        cleanedImage: ByteArray,
        imageExtension: String = "png",
    ): Pair<String, String> {
        require(job.status == CleanupJobStatus.RUNNING)
        require(imageExtension in SUPPORTED_IMAGE_EXTENSIONS)
        val page = job.pages.single { it.pageOrder == artifact.pageOrder }
        require(page.state == CleanupPageState.RUNNING)
        requireValidPageArtifact(artifact, page, job.dependencies)
        require(artifact.cleanedImageSha256 == sha256(cleanedImage))
        val directory = pageDirectory(checkpointDirectory(job), page)
        fileSystem.createDirectories(directory)
        val imageName = "cleaned.$imageExtension"
        val artifactPath = "pages/${pagePathName(page)}/cleanup.json"
        val imagePath = "pages/${pagePathName(page)}/$imageName"
        replaceUnique(directory.resolve("cleanup.json"), json.encodePageArtifact(artifact).toByteArray(Charsets.UTF_8))
        replaceUnique(directory.resolve(imageName), cleanedImage)
        require(readCommittedPage(job, page.copy(
            state = CleanupPageState.COMMITTED,
            artifactPath = artifactPath,
            imagePath = imagePath,
        )) == artifact)
        return artifactPath to imagePath
    }

    fun readCommittedPage(job: CleanupJobRecord, page: CleanupJobPage): PageCleanupArtifact? = runCatching {
        require(page.state == CleanupPageState.COMMITTED)
        val root = checkpointDirectory(job)
        val artifactPath = resolveInside(root, requireNotNull(page.artifactPath))
        val imagePath = resolveInside(root, requireNotNull(page.imagePath))
        require(fileSystem.exists(artifactPath) && fileSystem.exists(imagePath))
        json.decodePageArtifact(fileSystem.readUtf8(artifactPath)).also { artifact ->
            requireValidPageArtifact(artifact, page, job.dependencies)
            require(artifact.cleanedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
        }
    }.getOrNull()

    fun publishRun(job: CleanupJobRecord, run: CleanupRunArtifact, report: CleanupReport): Path {
        require(job.status.isSuccessful())
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

    fun readPublishedRun(runKey: String): CleanupRunArtifact? {
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

    fun readPublishedReport(runKey: String): CleanupReport? {
        requireSha256(runKey)
        val path = publishedDirectory(runKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also {
                require(it.runArtifactKey == runKey && it.status.isSuccessful())
            }
        }.getOrNull()
    }

    fun readPublishedPage(runKey: String, entry: CleanupRunEntry): PageCleanupArtifact? = runCatching {
        require(entry.state == CleanupPageState.COMMITTED)
        val root = publishedDirectory(runKey)
        val artifactPath = resolveInside(root, requireNotNull(entry.artifactPath))
        val imagePath = resolveInside(root, requireNotNull(entry.imagePath))
        val artifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
        require(artifact.pageOrder == entry.pageOrder && artifact.pageArtifactKey == entry.pageArtifactKey)
        require(artifact.cleanedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
        artifact
    }.getOrNull()

    private fun validateRunFiles(root: Path, run: CleanupRunArtifact): Boolean = run.entries.all { entry ->
        when (entry.state) {
            CleanupPageState.COMMITTED -> runCatching {
                val artifactPath = resolveInside(root, requireNotNull(entry.artifactPath))
                val imagePath = resolveInside(root, requireNotNull(entry.imagePath))
                require(fileSystem.exists(artifactPath) && fileSystem.exists(imagePath))
                val artifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
                require(artifact.pageOrder == entry.pageOrder)
                require(artifact.pageId == entry.pageId)
                require(artifact.pageArtifactKey == entry.pageArtifactKey)
                require(artifact.dependencies == run.dependencies)
                require(artifact.cleanedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
            }.isSuccess
            CleanupPageState.PRESERVED_SOURCE ->
                entry.artifactPath == null && entry.imagePath == null && entry.error != null
            CleanupPageState.PENDING,
            CleanupPageState.RUNNING,
            -> false
        }
    }

    private fun requireValidPageArtifact(
        artifact: PageCleanupArtifact,
        page: CleanupJobPage,
        dependencies: CleanupDependencies,
    ) {
        require(artifact.schemaVersion == CLEANUP_SCHEMA_VERSION)
        require(artifact.pageId == page.pageId && artifact.pageOrder == page.pageOrder)
        require(artifact.sourceSha256 == page.sourceSha256)
        require(artifact.translationPageArtifactKey == page.translationPageArtifactKey)
        require(artifact.pageArtifactKey == page.pageArtifactKey)
        require(artifact.dependencies == dependencies)
        require(artifact.visibleWidth > 0 && artifact.visibleHeight > 0)
        requireSha256(artifact.cleanedImageSha256)
        artifact.regions.forEach { region ->
            requireSha256(region.ocrRegionId)
            region.translationRegionId?.let(::requireSha256)
            require(region.roiPixelCount >= 0 && region.maskPixelCount >= 0 && region.changedPixelCount >= 0)
            require(region.auditPixelCount >= 0 && region.residualPixelCount in 0..region.auditPixelCount)
            require(region.cleanupAttemptCount in 0..2)
            when (region.state) {
                CleanupRegionState.CLEANED -> {
                    require(region.strategy != null && region.preserveReason == null)
                    require(region.maskSource != null)
                    require(region.cleanupAttemptCount >= 1)
                    require(
                        region.residualPixelCount <=
                            dependencies.policy.maximumResidualPixelCount ||
                            (
                                region.auditPixelCount > 0 &&
                                    region.residualPixelCount.toDouble() / region.auditPixelCount <=
                                    dependencies.policy.maximumResidualRatio
                                ),
                    )
                }
                CleanupRegionState.PRESERVED_SOURCE -> require(region.preserveReason != null)
            }
        }
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

    private fun checkpointDirectory(job: CleanupJobRecord): Path =
        projectDirectory.resolve("staging/cleanup/${job.jobId}/${job.runArtifactKey}").normalize()

    private fun publishedDirectory(runKey: String): Path =
        projectDirectory.resolve("artifacts/cleanup/$runKey").normalize()

    private fun pageDirectory(root: Path, page: CleanupJobPage): Path = root.resolve("pages/${pagePathName(page)}")
    private fun pagePathName(page: CleanupJobPage): String =
        "${page.pageOrder.toString().padStart(4, '0')}-${page.pageId}"

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank())
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root.normalize()))
        return resolved
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun requireSafeId(value: String) = require(SAFE_ID.matches(value))
    private fun requireSha256(value: String) = require(SHA256.matches(value))

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "webp")
    }
}
