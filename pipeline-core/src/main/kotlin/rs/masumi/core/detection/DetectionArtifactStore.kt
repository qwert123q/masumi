package rs.masumi.core.detection

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.DetectionJson
import java.nio.file.Path

class DetectionArtifactStore(
    projectDirectory: Path,
    private val json: DetectionJson = DetectionJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: DetectionJobRecord) {
        requireSafeId(job.jobId, "jobId")
        requireSha256(job.runArtifactKey, "runArtifactKey")
        val jobsDirectory = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobsDirectory)
        fileSystem.replaceUtf8(jobsDirectory.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): DetectionJobRecord? {
        requireSafeId(jobId, "jobId")
        val path = projectDirectory.resolve("jobs").resolve("$jobId.json")
        if (!fileSystem.exists(path)) return null
        val job = json.decodeJob(fileSystem.readUtf8(path))
        require(job.jobId == jobId) { "job file identity mismatch" }
        return job
    }

    fun findResumableJob(): DetectionJobRecord? = fileSystem
        .list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { path -> path.fileName.toString().endsWith(".json") }
        .mapNotNull { path -> runCatching { json.decodeJob(fileSystem.readUtf8(path)) }.getOrNull() }
        .filter { job -> job.status.isResumable() }
        .maxWithOrNull(compareBy<DetectionJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun cleanInterruptedPage(job: DetectionJobRecord, page: DetectionJobPage) {
        require(job.pages.any { it.order == page.order && it.pageId == page.pageId }) {
            "page does not belong to job"
        }
        val checkpoint = checkpointDirectory(job)
        val matchingOrders = job.pages.filter { it.pageId == page.pageId }.map { it.order }
        fileSystem.deleteRecursively(checkpoint.resolve("pages").resolve(page.pageId))
        matchingOrders.forEach { order ->
            fileSystem.deleteIfExists(checkpoint.resolve("previews").resolve(previewFileName(order)))
            fileSystem.deleteIfExists(checkpoint.resolve(".preview-$order.part"))
        }
        fileSystem.deleteIfExists(checkpoint.resolve(".regions-${page.pageId}.part"))
    }

    fun commitPage(
        job: DetectionJobRecord,
        pageArtifact: PageDetectionArtifact,
        previewPng: ByteArray,
        orders: List<Int>,
    ) {
        requireValidPageArtifact(
            pageArtifact,
            expectedPageId = pageArtifact.pageId,
            expectedPageArtifactKey = pageArtifact.pageArtifactKey,
            expectedModel = job.model,
            expectedPreprocessing = job.preprocessing,
            expectedThresholds = job.thresholds,
        )
        requireSha256(pageArtifact.pageId, "pageId")
        requireSha256(pageArtifact.pageArtifactKey, "pageArtifactKey")
        val matchingPages = job.pages.filter { it.pageId == pageArtifact.pageId }
        require(matchingPages.isNotEmpty()) { "page does not belong to job" }
        require(matchingPages.all { it.state == DetectionPageState.RUNNING }) { "page must be running" }
        require(orders.sorted() == matchingPages.map { it.order }.sorted()) {
            "preview orders must match manifest entries for the page"
        }
        require(matchingPages.all { it.pageArtifactKey == pageArtifact.pageArtifactKey }) {
            "page artifact key mismatch"
        }

        cleanInterruptedPage(job, matchingPages.first())
        val checkpoint = checkpointDirectory(job)
        val pageDirectory = checkpoint.resolve("pages").resolve(pageArtifact.pageId)
        val previewsDirectory = checkpoint.resolve("previews")
        fileSystem.createDirectories(pageDirectory)
        fileSystem.createDirectories(previewsDirectory)

        val regionsPart = checkpoint.resolve(".regions-${pageArtifact.pageId}.part")
        fileSystem.writeUtf8(regionsPart, json.encodePageArtifact(pageArtifact))
        fileSystem.moveFile(regionsPart, pageDirectory.resolve("regions.json"))

        orders.forEach { order ->
            val previewPart = checkpoint.resolve(".preview-$order.part")
            fileSystem.newOutputStream(previewPart).use { output ->
                output.write(previewPng)
                output.flush()
            }
            fileSystem.moveFile(previewPart, previewsDirectory.resolve(previewFileName(order)))
        }
    }

    fun validateCommittedPage(job: DetectionJobRecord, page: DetectionJobPage): Boolean = runCatching {
        require(page.state == DetectionPageState.COMMITTED)
        val checkpoint = checkpointDirectory(job)
        val regions = resolveInside(checkpoint, requireNotNull(page.regionsPath))
        val preview = resolveInside(checkpoint, requireNotNull(page.previewPath))
        require(fileSystem.exists(regions))
        require(fileSystem.exists(preview))
        val artifact = json.decodePageArtifact(fileSystem.readUtf8(regions))
        requireValidPageArtifact(
            artifact,
            page.pageId,
            page.pageArtifactKey,
            job.model,
            job.preprocessing,
            job.thresholds,
        )
    }.isSuccess

    fun publishRun(
        job: DetectionJobRecord,
        artifact: DetectionRunArtifact,
        report: DetectionReport,
    ): Path {
        require(job.status.isSuccessful()) { "job must be successfully terminal" }
        require(artifact.runArtifactKey == job.runArtifactKey) { "run artifact key mismatch" }
        require(artifact.projectId == job.projectId) { "run project mismatch" }
        require(report.jobId == job.jobId) { "report job mismatch" }
        require(report.runArtifactKey == job.runArtifactKey) { "report run mismatch" }
        require(report.status == job.status) { "report status mismatch" }

        val target = publishedDirectory(job.runArtifactKey)
        if (fileSystem.exists(target)) {
            val existing = requireNotNull(readPublishedRun(job.runArtifactKey)) {
                "existing run artifact is invalid"
            }
            require(existing == artifact) { "existing run artifact has different content" }
            return target
        }

        val checkpoint = checkpointDirectory(job)
        require(fileSystem.exists(checkpoint)) { "run checkpoint is missing" }
        require(validateRunFiles(checkpoint, artifact)) { "run checkpoint is incomplete" }
        fileSystem.replaceUtf8(checkpoint.resolve("artifact.json"), json.encodeRun(artifact))
        fileSystem.replaceUtf8(checkpoint.resolve("report.json"), json.encodeReport(report))
        fileSystem.createDirectories(target.parent)
        fileSystem.publishDirectory(checkpoint, target)
        requireNotNull(readPublishedRun(job.runArtifactKey)) { "published run did not validate" }
        return target
    }

    fun readPublishedRun(runArtifactKey: String): DetectionRunArtifact? {
        requireSha256(runArtifactKey, "runArtifactKey")
        val directory = publishedDirectory(runArtifactKey)
        val artifactPath = directory.resolve("artifact.json")
        if (!fileSystem.exists(artifactPath)) return null
        return runCatching {
            val artifact = json.decodeRun(fileSystem.readUtf8(artifactPath))
            require(artifact.runArtifactKey == runArtifactKey)
            require(validateRunFiles(directory, artifact))
            artifact
        }.getOrNull()
    }

    private fun validateRunFiles(root: Path, artifact: DetectionRunArtifact): Boolean =
        artifact.entries.all { entry ->
            when (entry.state) {
                DetectionPageState.COMMITTED -> runCatching {
                    val regions = resolveInside(root, requireNotNull(entry.regionsPath))
                    val preview = resolveInside(root, requireNotNull(entry.previewPath))
                    require(fileSystem.exists(regions))
                    require(fileSystem.exists(preview))
                    val pageArtifact = json.decodePageArtifact(fileSystem.readUtf8(regions))
                    requireValidPageArtifact(
                        pageArtifact,
                        entry.pageId,
                        entry.pageArtifactKey,
                        artifact.model,
                        artifact.preprocessing,
                        artifact.thresholds,
                    )
                }.isSuccess

                DetectionPageState.PRESERVED_SOURCE -> {
                    entry.regionsPath == null && entry.previewPath == null && entry.error != null
                }

                DetectionPageState.PENDING,
                DetectionPageState.RUNNING,
                -> false
            }
        }

    private fun requireValidPageArtifact(
        artifact: PageDetectionArtifact,
        expectedPageId: String,
        expectedPageArtifactKey: String,
        expectedModel: DetectorModelRef,
        expectedPreprocessing: DetectionPreprocessingConfig,
        expectedThresholds: DetectionThresholdConfig,
    ) {
        require(artifact.schemaVersion == DETECTION_SCHEMA_VERSION) { "page schema mismatch" }
        require(artifact.pageId == expectedPageId) { "page identity mismatch" }
        require(artifact.sourceSha256 == expectedPageId) { "source digest mismatch" }
        require(artifact.pageArtifactKey == expectedPageArtifactKey) { "page artifact key mismatch" }
        require(artifact.visibleWidth > 0 && artifact.visibleHeight > 0) { "page dimensions are invalid" }
        require(artifact.model == expectedModel) { "page model dependency mismatch" }
        require(artifact.preprocessing == expectedPreprocessing) {
            "page preprocessing dependency mismatch"
        }
        require(artifact.thresholds == expectedThresholds) { "page threshold dependency mismatch" }
        require(artifact.rawQueries.size == 300) { "page artifact must retain 300 raw queries" }
        require(artifact.rawQueries.map { it.queryIndex }.sorted() == (0 until 300).toList()) {
            "page artifact raw query indexes are incomplete"
        }
    }

    private fun checkpointDirectory(job: DetectionJobRecord): Path {
        requireSafeId(job.jobId, "jobId")
        requireSha256(job.runArtifactKey, "runArtifactKey")
        return projectDirectory
            .resolve("staging")
            .resolve("detection")
            .resolve(job.jobId)
            .resolve(job.runArtifactKey)
            .normalize()
    }

    private fun publishedDirectory(runArtifactKey: String): Path = projectDirectory
        .resolve("artifacts")
        .resolve("detection")
        .resolve(runArtifactKey)
        .normalize()

    private fun resolveInside(root: Path, relativePath: String): Path {
        require(relativePath.isNotBlank()) { "relative path must not be blank" }
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root.normalize())) { "relative path escapes artifact root" }
        return resolved
    }

    private fun requireSafeId(value: String, field: String) {
        require(SAFE_ID.matches(value)) { "$field contains unsafe characters" }
    }

    private fun requireSha256(value: String, field: String) {
        require(SHA256.matches(value)) { "$field must be a lowercase SHA-256 digest" }
    }

    private fun previewFileName(order: Int): String {
        require(order >= 0) { "page order must not be negative" }
        return order.toString().padStart(4, '0') + ".png"
    }

    private fun DetectionJobStatus.isResumable(): Boolean = when (this) {
        DetectionJobStatus.QUEUED,
        DetectionJobStatus.DOWNLOADING_MODEL,
        DetectionJobStatus.RUNNING,
        -> true

        DetectionJobStatus.SUCCEEDED,
        DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES,
        DetectionJobStatus.CANCELLED,
        DetectionJobStatus.FAILED,
        -> false
    }

    private fun DetectionJobStatus.isSuccessful(): Boolean =
        this == DetectionJobStatus.SUCCEEDED ||
            this == DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
