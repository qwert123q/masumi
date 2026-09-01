package rs.masumi.core.cleanup

import java.nio.file.Path
import java.util.UUID
import rs.masumi.core.identity.SafeOpaqueId
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
        SafeOpaqueId.require(job.jobId, "jobId")
        SafeOpaqueId.require(job.runArtifactKey, "runArtifactKey")
        val jobs = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobs)
        fileSystem.replaceUtf8(jobs.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): CleanupJobRecord? {
        SafeOpaqueId.require(jobId, "jobId")
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching { json.decodeJob(fileSystem.readUtf8(path)).also { require(it.jobId == jobId) } }.getOrNull()
    }

    fun findRecoveryCandidates(): List<CleanupJobRecord> = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter { it.status in setOf(CleanupJobStatus.QUEUED, CleanupJobStatus.RUNNING, CleanupJobStatus.CANCELLED) }
        .sortedWith(compareByDescending<CleanupJobRecord> { it.updatedAtEpochMillis }.thenByDescending { it.jobId })
        .toList()

    fun findResumableJob(): CleanupJobRecord? = findRecoveryCandidates().firstOrNull()

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
        require(cleanedImage.isNotEmpty())
        require(artifact.cleanedImageByteLength == cleanedImage.size.toLong())
        requireValidPageArtifact(artifact, page, job.dependencies)
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
            imageByteLength = cleanedImage.size.toLong(),
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
            val actualByteLength = fileSystem.byteLength(imagePath)
            require(actualByteLength > 0L)
            require(artifact.cleanedImageByteLength in setOf(0L, actualByteLength))
            require(page.imageByteLength in setOf(0L, actualByteLength))
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
        SafeOpaqueId.require(runKey, "runKey")
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
        SafeOpaqueId.require(runKey, "runKey")
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
        require(fileSystem.exists(artifactPath) && fileSystem.exists(imagePath))
        val artifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
        require(artifact.pageOrder == entry.pageOrder && artifact.pageArtifactKey == entry.pageArtifactKey)
        val actualByteLength = fileSystem.byteLength(imagePath)
        require(actualByteLength > 0L)
        require(artifact.cleanedImageByteLength in setOf(0L, actualByteLength))
        require(entry.imageByteLength in setOf(0L, actualByteLength))
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
                val actualByteLength = fileSystem.byteLength(imagePath)
                require(actualByteLength > 0L)
                require(artifact.cleanedImageByteLength in setOf(0L, actualByteLength))
                require(entry.imageByteLength in setOf(0L, actualByteLength))
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
        require(artifact.translationPageArtifactKey == page.translationPageArtifactKey)
        require(artifact.pageArtifactKey == page.pageArtifactKey)
        require(artifact.dependencies == dependencies)
        require(artifact.visibleWidth > 0 && artifact.visibleHeight > 0)
        require(artifact.cleanedImageByteLength >= 0L)
        artifact.regions.forEach { region ->
            SafeOpaqueId.require(region.ocrRegionId, "ocrRegionId")
            region.translationRegionId?.let { SafeOpaqueId.require(it, "translationRegionId") }
            require(region.roiPixelCount >= 0 && region.maskPixelCount >= 0 && region.changedPixelCount >= 0)
            require(region.auditPixelCount >= 0)
            require(region.initialResidualPixelCount in 0..region.auditPixelCount)
            require(region.residualRetryPixelCount in 0..region.roiPixelCount)
            require(region.residualPixelCount in 0..region.auditPixelCount)
            require(region.cleanupAttemptCount in 0..4)
            require(region.neuralFallbackMillis >= 0L)
            when (region.neuralFallbackOutcome) {
                NeuralFallbackOutcome.SUCCEEDED,
                NeuralFallbackOutcome.FAILED,
                -> require(region.cleanupAttemptCount == 4)
                NeuralFallbackOutcome.NOT_ATTEMPTED,
                NeuralFallbackOutcome.NOT_ELIGIBLE,
                NeuralFallbackOutcome.BUDGET_SKIPPED,
                -> require(region.neuralFallbackMillis == 0L)
            }
            when (region.state) {
                CleanupRegionState.CLEANED -> {
                    require(region.strategy != null && region.preserveReason == null)
                    require(region.maskSource != null)
                    require(region.cleanupAttemptCount >= 1)
                    require(region.changedPixelCount > 0)
                    val residualAcceptable =
                        region.residualPixelCount <= dependencies.policy.maximumResidualPixelCount ||
                            (
                                region.auditPixelCount > 0 &&
                                    region.residualPixelCount.toDouble() / region.auditPixelCount <=
                                    dependencies.policy.maximumResidualRatio
                                )
                    when (region.completionMode) {
                        CleanupCompletionMode.STRICT -> require(residualAcceptable)
                        CleanupCompletionMode.BEST_EFFORT_RESIDUAL -> {
                            require(!residualAcceptable)
                            require(region.initialResidualPixelCount > 0)
                            require(region.residualRetryPixelCount > 0)
                            require(region.cleanupAttemptCount >= 3)
                        }
                    }
                }
                CleanupRegionState.PRESERVED_SOURCE -> {
                    require(region.preserveReason != null)
                    require(region.completionMode == CleanupCompletionMode.STRICT)
                }
            }
        }
    }

    private fun replaceUnique(path: Path, bytes: ByteArray) {
        if (fileSystem.exists(path)) {
            require(fileSystem.byteLength(path) == bytes.size.toLong())
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

    private companion object {
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "webp")
    }
}
