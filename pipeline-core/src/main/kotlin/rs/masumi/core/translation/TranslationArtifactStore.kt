package rs.masumi.core.translation

import java.nio.file.Path
import java.util.UUID
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.TranslationJson

class TranslationArtifactStore(
    projectDirectory: Path,
    private val json: TranslationJson = TranslationJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()

    fun writeJob(job: TranslationJobRecord) {
        requireSafeId(job.jobId)
        requireSha256(job.runArtifactKey)
        val directory = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(directory)
        fileSystem.replaceUtf8(directory.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): TranslationJobRecord? {
        requireSafeId(jobId)
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return json.decodeJob(fileSystem.readUtf8(path)).also { require(it.jobId == jobId) }
    }

    fun findResumableJob(): TranslationJobRecord? = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter { it.status in setOf(TranslationJobStatus.QUEUED, TranslationJobStatus.RUNNING, TranslationJobStatus.CANCELLED) }
        .maxWithOrNull(compareBy<TranslationJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun prepareRun(job: TranslationJobRecord) {
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun commitWindow(job: TranslationJobRecord, artifact: TranslationWindowArtifact): String {
        require(job.status == TranslationJobStatus.RUNNING)
        val checkpoint = job.windows.single { it.windowIndex == artifact.windowIndex }
        require(checkpoint.state == TranslationWindowState.RUNNING)
        require(checkpoint.windowArtifactKey == artifact.windowArtifactKey)
        require(artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION)
        require(artifact.items.map(ValidatedTranslationItem::translationRegionId) == checkpoint.translationRegionIds)
        artifact.items.forEach(::requireValidOutcome)
        requireSha256(artifact.inputGlossarySha256)
        val expectedInputGlossary = if (artifact.windowIndex == 0) {
            job.dependencies.initialGlossarySha256
        } else {
            val previous = job.windows.single { it.windowIndex == artifact.windowIndex - 1 }
            require(previous.state.isTerminal()) { "previous window must be terminal" }
            requireNotNull(readWindowCheckpoint(job, previous.windowIndex)).outputGlossarySha256
        }
        require(artifact.inputGlossarySha256 == expectedInputGlossary)
        requireValidGlossary(artifact.outputGlossary)
        require(artifact.outputGlossarySha256 == TranslationArtifactIdentity.glossarySha256(artifact.outputGlossary))
        val relative = "windows/${artifact.windowIndex.toString().padStart(4, '0')}-${artifact.windowArtifactKey}.json"
        replaceUnique(checkpointDirectory(job).resolve(relative), json.encodeWindowArtifact(artifact))
        require(readWindowCheckpoint(job, artifact.windowIndex) == artifact)
        return relative
    }

    fun readWindowCheckpoint(job: TranslationJobRecord, index: Int): TranslationWindowArtifact? = runCatching {
        val window = job.windows.single { it.windowIndex == index }
        val path = if (window.checkpointPath != null) {
            resolveInside(checkpointDirectory(job), window.checkpointPath)
        } else {
            checkpointDirectory(job).resolve(
                "windows/${index.toString().padStart(4, '0')}-${window.windowArtifactKey}.json",
            )
        }
        require(fileSystem.exists(path))
        json.decodeWindowArtifact(fileSystem.readUtf8(path)).also {
            require(it.windowIndex == index && it.windowArtifactKey == window.windowArtifactKey)
        }
    }.getOrNull()

    fun cleanInterruptedWindow(job: TranslationJobRecord, index: Int) {
        val window = job.windows.single { it.windowIndex == index }
        require(window.state == TranslationWindowState.RUNNING)
        fileSystem.deleteIfExists(
            checkpointDirectory(job).resolve(
                "windows/${index.toString().padStart(4, '0')}-${window.windowArtifactKey}.json",
            ),
        )
    }

    fun commitPage(job: TranslationJobRecord, artifact: PageTranslationArtifact): String {
        require(job.status == TranslationJobStatus.RUNNING)
        val page = job.pages.single { it.pageOrder == artifact.pageOrder }
        require(page.state == TranslationPageState.PENDING || page.state == TranslationPageState.RUNNING)
        require(artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION)
        require(artifact.pageId == page.pageId)
        require(artifact.pageOrder == page.pageOrder)
        require(artifact.ocrPageArtifactKey == page.ocrPageArtifactKey)
        require(artifact.pageArtifactKey == page.pageArtifactKey)
        require(artifact.dependencies == job.dependencies)
        require(artifact.items.map(ValidatedTranslationItem::translationRegionId).sorted() == page.translationRegionIds.sorted())
        artifact.items.forEach(::requireValidOutcome)
        require(artifact.protectedOcrRegions.size == page.protectedOcrRegionCount)
        val relative = "pages/${artifact.pageOrder.toString().padStart(4, '0')}-${artifact.pageId}/translation.json"
        replaceUnique(checkpointDirectory(job).resolve(relative), json.encodePageArtifact(artifact))
        return relative
    }

    fun publishRun(
        job: TranslationJobRecord,
        run: TranslationRunArtifact,
        glossary: TranslationGlossaryArtifact,
        report: TranslationReport,
    ): Path {
        require(job.status.isSuccessful())
        require(run.runArtifactKey == job.runArtifactKey && run.projectId == job.projectId)
        require(run.dependencies == job.dependencies)
        require(report.jobId == job.jobId && report.status == job.status)
        require(glossary.sha256 == TranslationArtifactIdentity.glossarySha256(glossary.entries))
        val staging = checkpointDirectory(job)
        run.entries.forEach { entry ->
            require(fileSystem.exists(resolveInside(staging, entry.artifactPath)))
        }
        val glossaryPath = resolveInside(staging, run.glossaryPath)
        fileSystem.createDirectories(glossaryPath.parent)
        fileSystem.replaceUtf8(glossaryPath, json.encodeGlossary(glossary))
        fileSystem.replaceUtf8(staging.resolve("artifact.json"), json.encodeRun(run))
        fileSystem.replaceUtf8(staging.resolve("report.json"), json.encodeReport(report))
        val target = publishedDirectory(job.runArtifactKey)
        if (!fileSystem.exists(target)) {
            fileSystem.createDirectories(target.parent)
            fileSystem.publishDirectory(staging, target)
        }
        require(readPublishedRun(job.runArtifactKey) == run)
        return target
    }

    fun readPublishedRun(runKey: String): TranslationRunArtifact? {
        requireSha256(runKey)
        val root = publishedDirectory(runKey)
        val path = root.resolve("artifact.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeRun(fileSystem.readUtf8(path)).also { run ->
                require(run.runArtifactKey == runKey)
                require(run.entries.all { fileSystem.exists(resolveInside(root, it.artifactPath)) })
                require(fileSystem.exists(resolveInside(root, run.glossaryPath)))
            }
        }.getOrNull()
    }

    fun readPublishedReport(runKey: String): TranslationReport? {
        requireSha256(runKey)
        val path = publishedDirectory(runKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also { require(it.runArtifactKey == runKey) }
        }.getOrNull()
    }

    fun readPublishedGlossary(runKey: String): TranslationGlossaryArtifact? {
        requireSha256(runKey)
        val run = readPublishedRun(runKey) ?: return null
        val path = resolveInside(publishedDirectory(runKey), run.glossaryPath)
        return runCatching {
            json.decodeGlossary(fileSystem.readUtf8(path)).also {
                require(it.sha256 == TranslationArtifactIdentity.glossarySha256(it.entries))
            }
        }.getOrNull()
    }

    private fun checkpointDirectory(job: TranslationJobRecord): Path = projectDirectory
        .resolve("staging/translation/${job.jobId}/${job.runArtifactKey}").normalize()

    private fun publishedDirectory(runKey: String): Path =
        projectDirectory.resolve("artifacts/translation/$runKey").normalize()

    private fun replaceUnique(target: Path, content: String) {
        fileSystem.createDirectories(target.parent)
        if (fileSystem.exists(target)) {
            require(fileSystem.readUtf8(target) == content) { "checkpoint content mismatch" }
            return
        }
        val part = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        try {
            fileSystem.writeUtf8(part, content)
            fileSystem.moveFile(part, target)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteIfExists(part) }
            throw failure
        }
    }

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank())
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root.normalize())) { "relative path escapes artifact root" }
        return resolved
    }

    private fun requireSafeId(value: String) = require(SAFE_ID.matches(value)) { "unsafe id" }
    private fun requireSha256(value: String) = require(SHA256.matches(value)) { "invalid SHA-256" }

    private fun requireValidOutcome(item: ValidatedTranslationItem) {
        requireSha256(item.translationRegionId)
        when (item.state) {
            TranslationResultState.TRANSLATED -> {
                require(!item.translatedText.isNullOrBlank() && item.preserveReason == null)
            }
            TranslationResultState.PRESERVED_SOURCE -> {
                require(item.translatedText == null && item.preserveReason != null)
            }
        }
    }

    private fun requireValidGlossary(entries: List<TranslationGlossaryEntry>) {
        require(entries.all { it.source.isNotBlank() && it.translation.isNotBlank() })
        require(entries.map(TranslationGlossaryEntry::source).distinct().size == entries.size)
        require(entries == entries.sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation)))
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
