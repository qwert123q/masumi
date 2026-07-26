package rs.masumi.core.typesetting

import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.TypesettingJson

class TypesettingArtifactStore(
    projectDirectory: Path,
    private val json: TypesettingJson = TypesettingJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: TypesettingJobRecord) {
        requireSafeId(job.jobId)
        requireSha256(job.runArtifactKey)
        val jobs = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(jobs)
        fileSystem.replaceUtf8(jobs.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): TypesettingJobRecord? {
        requireSafeId(jobId)
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching { json.decodeJob(fileSystem.readUtf8(path)).also { require(it.jobId == jobId) } }.getOrNull()
    }

    fun findResumableJob(): TypesettingJobRecord? = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter {
            it.status in setOf(
                TypesettingJobStatus.QUEUED,
                TypesettingJobStatus.RUNNING,
                TypesettingJobStatus.CANCELLED,
            )
        }
        .maxWithOrNull(compareBy<TypesettingJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun prepareRun(job: TypesettingJobRecord) {
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun cleanInterruptedPage(job: TypesettingJobRecord, pageOrder: Int) {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == TypesettingPageState.RUNNING)
        fileSystem.deleteRecursively(pageDirectory(checkpointDirectory(job), page))
    }

    fun commitPage(
        job: TypesettingJobRecord,
        artifact: PageTypesettingArtifact,
        renderedImage: ByteArray,
        imageExtension: String = "png",
    ): Pair<String, String> {
        require(job.status == TypesettingJobStatus.RUNNING)
        require(imageExtension in SUPPORTED_IMAGE_EXTENSIONS)
        val page = job.pages.single { it.pageOrder == artifact.pageOrder }
        require(page.state == TypesettingPageState.RUNNING)
        requireValidPageArtifact(artifact, page, job.dependencies)
        require(artifact.renderedImageSha256 == sha256(renderedImage))
        val directory = pageDirectory(checkpointDirectory(job), page)
        fileSystem.createDirectories(directory)
        val imageName = "flattened.$imageExtension"
        val artifactPath = "pages/${pagePathName(page)}/typesetting.json"
        val imagePath = "pages/${pagePathName(page)}/$imageName"
        replaceUnique(directory.resolve("typesetting.json"), json.encodePageArtifact(artifact).toByteArray(Charsets.UTF_8))
        replaceUnique(directory.resolve(imageName), renderedImage)
        require(
            readCommittedPage(
                job,
                page.copy(
                    state = TypesettingPageState.COMMITTED,
                    artifactPath = artifactPath,
                    imagePath = imagePath,
                ),
            ) == artifact,
        )
        return artifactPath to imagePath
    }

    fun readCommittedPage(job: TypesettingJobRecord, page: TypesettingJobPage): PageTypesettingArtifact? = runCatching {
        require(page.state == TypesettingPageState.COMMITTED)
        val root = checkpointDirectory(job)
        val artifactPath = resolveInside(root, requireNotNull(page.artifactPath))
        val imagePath = resolveInside(root, requireNotNull(page.imagePath))
        require(fileSystem.exists(artifactPath) && fileSystem.exists(imagePath))
        json.decodePageArtifact(fileSystem.readUtf8(artifactPath)).also { artifact ->
            requireValidPageArtifact(artifact, page, job.dependencies)
            require(artifact.renderedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
        }
    }.getOrNull()

    fun publishRun(job: TypesettingJobRecord, run: TypesettingRunArtifact, report: TypesettingReport): Path {
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

    fun readPublishedRun(runKey: String): TypesettingRunArtifact? {
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

    fun readPublishedReport(runKey: String): TypesettingReport? {
        requireSha256(runKey)
        val path = publishedDirectory(runKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also {
                require(it.runArtifactKey == runKey && it.status.isSuccessful())
            }
        }.getOrNull()
    }

    fun readPublishedPage(runKey: String, entry: TypesettingRunEntry): PageTypesettingArtifact? = runCatching {
        require(entry.state == TypesettingPageState.COMMITTED)
        val root = publishedDirectory(runKey)
        val artifactPath = resolveInside(root, requireNotNull(entry.artifactPath))
        val imagePath = resolveInside(root, requireNotNull(entry.imagePath))
        val artifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
        require(artifact.pageOrder == entry.pageOrder && artifact.pageArtifactKey == entry.pageArtifactKey)
        require(artifact.renderedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
        artifact
    }.getOrNull()

    private fun validateRunFiles(root: Path, run: TypesettingRunArtifact): Boolean = run.entries.all { entry ->
        when (entry.state) {
            TypesettingPageState.COMMITTED -> runCatching {
                val artifactPath = resolveInside(root, requireNotNull(entry.artifactPath))
                val imagePath = resolveInside(root, requireNotNull(entry.imagePath))
                require(fileSystem.exists(artifactPath) && fileSystem.exists(imagePath))
                val artifact = json.decodePageArtifact(fileSystem.readUtf8(artifactPath))
                require(artifact.pageOrder == entry.pageOrder)
                require(artifact.pageId == entry.pageId)
                require(artifact.pageArtifactKey == entry.pageArtifactKey)
                require(artifact.dependencies == run.dependencies)
                require(artifact.renderedImageSha256 == sha256(fileSystem.readBytes(imagePath)))
            }.isSuccess
            TypesettingPageState.PRESERVED_CLEANED_PAGE ->
                entry.artifactPath == null && entry.imagePath == null && entry.error != null
            TypesettingPageState.PENDING,
            TypesettingPageState.RUNNING,
            -> false
        }
    }

    private fun requireValidPageArtifact(
        artifact: PageTypesettingArtifact,
        page: TypesettingJobPage,
        dependencies: TypesettingDependencies,
    ) {
        require(artifact.schemaVersion == TYPESETTING_SCHEMA_VERSION)
        require(artifact.pageId == page.pageId && artifact.pageOrder == page.pageOrder)
        require(artifact.sourceSha256 == page.sourceSha256)
        require(artifact.cleanupPageArtifactKey == page.cleanupPageArtifactKey)
        require(artifact.pageArtifactKey == page.pageArtifactKey)
        require(artifact.dependencies == dependencies)
        require(artifact.visibleWidth > 0 && artifact.visibleHeight > 0)
        requireSha256(artifact.renderedImageSha256)
        artifact.reusedFromPageArtifactKey?.let {
            requireSha256(it)
            require(it != artifact.pageArtifactKey)
        }
        artifact.regions.forEach { region ->
            requireSha256(region.ocrRegionId)
            region.translationRegionId?.let(::requireSha256)
            require(region.lineOrColumnCount >= 0 && region.changedPixelCount >= 0)
            when (region.state) {
                TypesettingRegionState.TYPESET -> {
                    require(region.translationRegionId != null)
                    require(region.layoutBox != null && region.style != null && region.direction != null)
                    require(region.fontSizePx != null && region.fontSizePx > 0.0)
                    require(region.lineOrColumnCount > 0 && region.changedPixelCount > 0)
                    require(region.preserveReason == null)
                }
                TypesettingRegionState.PRESERVED_CLEANED_PAGE -> require(region.preserveReason != null)
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

    private fun checkpointDirectory(job: TypesettingJobRecord): Path =
        projectDirectory.resolve("staging/typesetting/${job.jobId}/${job.runArtifactKey}").normalize()

    private fun publishedDirectory(runKey: String): Path =
        projectDirectory.resolve("artifacts/typesetting/$runKey").normalize()

    private fun pageDirectory(root: Path, page: TypesettingJobPage): Path = root.resolve("pages/${pagePathName(page)}")
    private fun pagePathName(page: TypesettingJobPage): String =
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
