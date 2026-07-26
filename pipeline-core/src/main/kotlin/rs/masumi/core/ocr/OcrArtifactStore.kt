package rs.masumi.core.ocr

import java.nio.file.Path
import java.util.UUID
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.OcrJson

class OcrArtifactStore(
    projectDirectory: Path,
    private val json: OcrJson = OcrJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: OcrJobRecord) {
        requireSafeId(job.jobId, "jobId")
        requireSha256(job.runArtifactKey, "runArtifactKey")
        val jobs = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobs)
        fileSystem.replaceUtf8(jobs.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): OcrJobRecord? {
        requireSafeId(jobId, "jobId")
        val path = projectDirectory.resolve("jobs").resolve("$jobId.json")
        if (!fileSystem.exists(path)) return null
        val job = json.decodeJob(fileSystem.readUtf8(path))
        require(job.jobId == jobId) { "job file identity mismatch" }
        return job
    }

    fun findLatestJob(): OcrJobRecord? = readAllJobs()
        .maxWithOrNull(compareBy<OcrJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun findResumableJob(): OcrJobRecord? = readAllJobs()
        .filter { it.status.isResumable() }
        .maxWithOrNull(compareBy<OcrJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun findRecoveryCandidates(): List<OcrJobRecord> = readAllJobs()
        .filterNot { it.status.isSuccessful() }
        .toList()

    fun prepareRun(job: OcrJobRecord) {
        requireSafeId(job.jobId, "jobId")
        requireSha256(job.runArtifactKey, "runArtifactKey")
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun cleanInterruptedRegion(
        job: OcrJobRecord,
        pageId: String,
        ocrRegionId: String,
    ) {
        val pages = requireMatchingPages(job, pageId)
        require(pages.all { page -> page.regions.any { it.ocrRegionId == ocrRegionId } }) {
            "region does not belong to duplicate page entries"
        }
        requireSha256(ocrRegionId, "ocrRegionId")
        fileSystem.deleteIfExists(
            checkpointDirectory(job)
                .resolve("pages")
                .resolve(pageId)
                .resolve("regions")
                .resolve("$ocrRegionId.json"),
        )
    }

    fun commitRegion(
        job: OcrJobRecord,
        page: OcrJobPage,
        artifact: OcrRegionArtifact,
    ): String {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        val matchingPages = requireMatchingPages(job, page.pageId)
        require(matchingPages.any { it.order == page.order }) { "page order does not belong to job" }
        require(matchingPages.all { it.state == OcrPageState.RUNNING }) { "page must be running" }
        require(artifact.state.isTerminal()) { "region checkpoint must be terminal" }
        val expectedRegions = matchingPages.map { matching ->
            matching.regions.singleOrNull { it.ocrRegionId == artifact.candidate.ocrRegionId }
                ?: throw IllegalArgumentException("region does not belong to page")
        }
        require(expectedRegions.all { it.state == OcrRegionState.RUNNING }) {
            "region must be running before checkpoint"
        }
        requireValidRegionArtifact(artifact)

        val relativePath = "pages/${page.pageId}/regions/${artifact.candidate.ocrRegionId}.json"
        val target = checkpointDirectory(job).resolve(relativePath)
        fileSystem.createDirectories(target.parent)
        if (fileSystem.exists(target)) {
            val existing = json.decodeRegionArtifact(fileSystem.readUtf8(target))
            require(existing == artifact) { "existing region checkpoint has different content" }
            return relativePath
        }
        val part = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        try {
            fileSystem.writeUtf8(part, json.encodeRegionArtifact(artifact))
            fileSystem.moveFile(part, target)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteIfExists(part) }
            throw failure
        }
        require(readRegionCheckpoint(job, page, artifact.candidate.ocrRegionId) == artifact) {
            "region checkpoint did not validate"
        }
        return relativePath
    }

    fun readRegionCheckpoint(
        job: OcrJobRecord,
        page: OcrJobPage,
        ocrRegionId: String,
    ): OcrRegionArtifact? = runCatching {
        requireMatchingPages(job, page.pageId)
        require(page.regions.any { it.ocrRegionId == ocrRegionId }) { "region does not belong to page" }
        requireSha256(ocrRegionId, "ocrRegionId")
        val path = checkpointDirectory(job)
            .resolve("pages")
            .resolve(page.pageId)
            .resolve("regions")
            .resolve("$ocrRegionId.json")
        require(fileSystem.exists(path))
        json.decodeRegionArtifact(fileSystem.readUtf8(path)).also(::requireValidRegionArtifact)
            .also { require(it.candidate.ocrRegionId == ocrRegionId) { "region identity mismatch" } }
    }.getOrNull()

    fun commitPage(
        job: OcrJobRecord,
        pageArtifact: PageOcrArtifact,
        previewPng: ByteArray,
        orders: List<Int>,
    ) {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        val pages = requireMatchingPages(job, pageArtifact.pageId)
        require(pages.all { it.state == OcrPageState.RUNNING }) { "page must be running" }
        require(orders.sorted() == pages.map(OcrJobPage::order).sorted()) {
            "preview orders must match duplicate manifest entries"
        }
        require(pages.all { it.regions.all { region -> region.state.isTerminal() } }) {
            "every region must be terminal"
        }
        requireValidPageArtifact(pageArtifact, pages.first(), job.dependencies)
        val expectedRegionIds = pages.first().regions.map(OcrRegionCheckpoint::ocrRegionId).sorted()
        require(pageArtifact.regions.map { it.candidate.ocrRegionId }.sorted() == expectedRegionIds) {
            "page region set mismatch"
        }
        pageArtifact.regions.forEach { region ->
            val checkpoint = readRegionCheckpoint(job, pages.first(), region.candidate.ocrRegionId)
            require(checkpoint == region) { "page region does not match durable checkpoint" }
        }

        val checkpoint = checkpointDirectory(job)
        val pageDirectory = checkpoint.resolve("pages").resolve(pageArtifact.pageId)
        val previewsDirectory = checkpoint.resolve("previews")
        fileSystem.createDirectories(pageDirectory)
        fileSystem.createDirectories(previewsDirectory)
        replaceUtf8WithUniquePart(pageDirectory.resolve("ocr.json"), json.encodePageArtifact(pageArtifact))
        orders.forEach { order ->
            val target = previewsDirectory.resolve(previewFileName(order))
            val part = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
            try {
                fileSystem.newOutputStream(part).use { output ->
                    output.write(previewPng)
                    output.flush()
                }
                fileSystem.replaceFile(part, target)
            } catch (failure: Throwable) {
                runCatching { fileSystem.deleteIfExists(part) }
                throw failure
            }
        }
    }

    fun readCommittedPageArtifact(job: OcrJobRecord, page: OcrJobPage): PageOcrArtifact? =
        runCatching {
            require(page.state == OcrPageState.COMMITTED)
            val root = checkpointDirectory(job)
            val artifactPath = resolveInside(root, requireNotNull(page.artifactPath))
            val previewPath = resolveInside(root, requireNotNull(page.previewPath))
            require(fileSystem.exists(artifactPath) && fileSystem.exists(previewPath))
            json.decodePageArtifact(fileSystem.readUtf8(artifactPath)).also { artifact ->
                requireValidPageArtifact(artifact, page, job.dependencies)
            }
        }.getOrNull()

    fun validateCommittedPage(job: OcrJobRecord, page: OcrJobPage): Boolean =
        readCommittedPageArtifact(job, page) != null

    fun publishRun(job: OcrJobRecord, artifact: OcrRunArtifact, report: OcrReport): Path {
        require(job.status.isSuccessful()) { "job must be successfully terminal" }
        require(artifact.runArtifactKey == job.runArtifactKey) { "run artifact key mismatch" }
        require(artifact.projectId == job.projectId) { "run project mismatch" }
        require(artifact.detectionRunArtifactKey == job.detectionRunArtifactKey) {
            "detection dependency mismatch"
        }
        require(artifact.dependencies == job.dependencies) { "run dependencies mismatch" }
        require(report.jobId == job.jobId) { "report job mismatch" }
        require(report.runArtifactKey == job.runArtifactKey) { "report run mismatch" }
        require(report.status == job.status) { "report status mismatch" }

        val target = publishedDirectory(job.runArtifactKey)
        if (fileSystem.exists(target)) {
            require(readPublishedRun(job.runArtifactKey) == artifact) {
                "existing run artifact has different content"
            }
            require(readPublishedReport(job.runArtifactKey) == report) {
                "existing report has different content"
            }
            return target
        }
        val checkpoint = checkpointDirectory(job)
        require(fileSystem.exists(checkpoint)) { "run checkpoint is missing" }
        require(validateRunFiles(checkpoint, artifact)) { "run checkpoint is incomplete" }
        fileSystem.replaceUtf8(checkpoint.resolve("artifact.json"), json.encodeRun(artifact))
        fileSystem.replaceUtf8(checkpoint.resolve("report.json"), json.encodeReport(report))
        fileSystem.createDirectories(target.parent)
        fileSystem.publishDirectory(checkpoint, target)
        require(readPublishedRun(job.runArtifactKey) == artifact) { "published run did not validate" }
        return target
    }

    fun readPublishedRun(runArtifactKey: String): OcrRunArtifact? {
        requireSha256(runArtifactKey, "runArtifactKey")
        val directory = publishedDirectory(runArtifactKey)
        val path = directory.resolve("artifact.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeRun(fileSystem.readUtf8(path)).also { artifact ->
                require(artifact.runArtifactKey == runArtifactKey)
                require(validateRunFiles(directory, artifact))
            }
        }.getOrNull()
    }

    fun readPublishedReport(runArtifactKey: String): OcrReport? {
        requireSha256(runArtifactKey, "runArtifactKey")
        val path = publishedDirectory(runArtifactKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also { report ->
                require(report.runArtifactKey == runArtifactKey)
                require(report.status.isSuccessful())
            }
        }.getOrNull()
    }

    private fun readAllJobs(): Sequence<OcrJobRecord> = fileSystem
        .list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { path -> runCatching { json.decodeJob(fileSystem.readUtf8(path)) }.getOrNull() }

    private fun validateRunFiles(root: Path, artifact: OcrRunArtifact): Boolean =
        artifact.entries.all { entry ->
            when (entry.state) {
                OcrPageState.COMMITTED -> runCatching {
                    val artifactPath = resolveInside(root, requireNotNull(entry.artifactPath))
                    val previewPath = resolveInside(root, requireNotNull(entry.previewPath))
                    require(fileSystem.exists(artifactPath) && fileSystem.exists(previewPath))
                    val pageArtifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
                    require(pageArtifact.pageId == entry.pageId)
                    require(pageArtifact.sourceSha256 == entry.sourceSha256)
                    require(pageArtifact.detectionPageArtifactKey == entry.detectionPageArtifactKey)
                    require(pageArtifact.pageArtifactKey == entry.pageArtifactKey)
                    require(pageArtifact.dependencies == artifact.dependencies)
                    require(pageArtifact.regions.all { it.state.isTerminal() })
                }.isSuccess
                OcrPageState.PRESERVED_SOURCE -> {
                    entry.artifactPath == null && entry.previewPath == null && entry.error != null
                }
                OcrPageState.PENDING,
                OcrPageState.RUNNING,
                -> false
            }
        }

    private fun requireValidPageArtifact(
        artifact: PageOcrArtifact,
        page: OcrJobPage,
        dependencies: OcrDependencies,
    ) {
        require(artifact.schemaVersion == OCR_SCHEMA_VERSION) { "page schema mismatch" }
        require(artifact.pageId == page.pageId) { "page identity mismatch" }
        require(artifact.sourceSha256 == page.sourceSha256) { "source digest mismatch" }
        require(artifact.detectionPageArtifactKey == page.detectionPageArtifactKey) {
            "detection page dependency mismatch"
        }
        require(artifact.pageArtifactKey == page.pageArtifactKey) { "page artifact key mismatch" }
        require(artifact.dependencies == dependencies) { "page dependencies mismatch" }
        require(artifact.visibleWidth > 0 && artifact.visibleHeight > 0) { "page dimensions are invalid" }
        require(artifact.regions.all { it.state.isTerminal() }) { "page contains incomplete regions" }
        artifact.regions.forEach(::requireValidRegionArtifact)
    }

    private fun requireValidRegionArtifact(artifact: OcrRegionArtifact) {
        requireSha256(artifact.candidate.ocrRegionId, "ocrRegionId")
        require(artifact.state.isTerminal()) { "region artifact must be terminal" }
        require(artifact.selectedAttemptIndex == null || artifact.selectedAttemptIndex in artifact.attempts.indices) {
            "selected attempt is outside attempt list"
        }
        require(artifact.attempts.all { it.tokenIds.size == it.tokenProbabilities.size }) {
            "token IDs and probabilities must align"
        }
    }

    private fun requireMatchingPages(job: OcrJobRecord, pageId: String): List<OcrJobPage> =
        job.pages.filter { it.pageId == pageId }.also { pages ->
            require(pages.isNotEmpty()) { "page does not belong to job" }
        }

    private fun checkpointDirectory(job: OcrJobRecord): Path {
        requireSafeId(job.jobId, "jobId")
        requireSha256(job.runArtifactKey, "runArtifactKey")
        return projectDirectory.resolve("staging/ocr/${job.jobId}/${job.runArtifactKey}").normalize()
    }

    private fun publishedDirectory(runArtifactKey: String): Path =
        projectDirectory.resolve("artifacts/ocr/$runArtifactKey").normalize()

    private fun replaceUtf8WithUniquePart(target: Path, content: String) {
        val part = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        try {
            fileSystem.writeUtf8(part, content)
            fileSystem.replaceFile(part, target)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteIfExists(part) }
            throw failure
        }
    }

    private fun resolveInside(root: Path, relativePath: String): Path {
        require(relativePath.isNotBlank()) { "relative path must not be blank" }
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root.normalize())) { "relative path escapes artifact root" }
        return resolved
    }

    private fun previewFileName(order: Int): String {
        require(order >= 0) { "page order must not be negative" }
        return order.toString().padStart(4, '0') + ".webp"
    }

    private fun OcrJobStatus.isResumable(): Boolean = when (this) {
        OcrJobStatus.QUEUED,
        OcrJobStatus.DOWNLOADING_MODEL,
        OcrJobStatus.LOADING_MODEL,
        OcrJobStatus.RUNNING,
        OcrJobStatus.CANCELLED,
        -> true
        OcrJobStatus.SUCCEEDED,
        OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        OcrJobStatus.FAILED,
        -> false
    }

    private fun requireSafeId(value: String, field: String) {
        require(SAFE_ID.matches(value)) { "$field contains unsafe characters" }
    }

    private fun requireSha256(value: String, field: String) {
        require(SHA256.matches(value)) { "$field must be a lowercase SHA-256 digest" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
