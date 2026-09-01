package rs.masumi.core.translation

import java.nio.file.Path
import java.util.UUID
import rs.masumi.core.identity.SafeOpaqueId
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
        requireValidJobStorageIdentity(job)
        val directory = projectDirectory.resolve("jobs")
        fileSystem.createDirectories(directory)
        fileSystem.replaceUtf8(directory.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): TranslationJobRecord? {
        SafeOpaqueId.require(jobId)
        val path = projectDirectory.resolve("jobs/$jobId.json")
        if (!fileSystem.exists(path)) return null
        return json.decodeJob(fileSystem.readUtf8(path)).also {
            require(it.jobId == jobId)
            requireValidJobStorageIdentity(it)
        }
    }

    fun findRecoveryCandidates(): List<TranslationJobRecord> = fileSystem.list(projectDirectory.resolve("jobs"))
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { path ->
            runCatching {
                val fileJobId = path.fileName.toString().removeSuffix(".json")
                SafeOpaqueId.require(fileJobId)
                json.decodeJob(fileSystem.readUtf8(path)).also { job ->
                    require(job.jobId == fileJobId)
                    requireValidJobStorageIdentity(job)
                }
            }.getOrNull()
        }
        .filter { it.status in setOf(TranslationJobStatus.QUEUED, TranslationJobStatus.RUNNING, TranslationJobStatus.CANCELLED) }
        .sortedWith(compareByDescending<TranslationJobRecord> { it.updatedAtEpochMillis }.thenByDescending { it.jobId })
        .toList()

    fun findResumableJob(): TranslationJobRecord? = findRecoveryCandidates().firstOrNull()

    fun prepareRun(job: TranslationJobRecord) {
        fileSystem.createDirectories(checkpointDirectory(job))
    }

    fun commitWindow(job: TranslationJobRecord, artifact: TranslationWindowArtifact): String {
        require(job.status == TranslationJobStatus.RUNNING)
        val checkpoint = job.windows.single { it.windowIndex == artifact.windowIndex }
        require(checkpoint.state == TranslationWindowState.RUNNING)
        require(checkpoint.windowArtifactKey == artifact.windowArtifactKey)
        require(artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION)
        require(artifact.contextTranslationRegionIds == checkpoint.contextTranslationRegionIds)
        require(artifact.translationRegionIds == checkpoint.translationRegionIds)
        require(artifact.items.map(ValidatedTranslationItem::translationRegionId) == checkpoint.translationRegionIds)
        artifact.items.forEach(::requireValidOutcome)
        val expectedInputGlossary = if (artifact.windowIndex == 0) {
            job.dependencies.initialGlossary
        } else {
            val previous = job.windows.single { it.windowIndex == artifact.windowIndex - 1 }
            require(previous.state.isTerminal()) { "previous window must be terminal" }
            requireNotNull(readWindowCheckpoint(job, previous.windowIndex)).outputGlossary
        }
        require(artifact.inputGlossary == expectedInputGlossary)
        requireValidGlossary(artifact.inputGlossary)
        requireValidGlossary(artifact.outputGlossary)
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
        val content = fileSystem.readUtf8(path)
        val legacyInputGlossary = if (!json.windowCheckpointNeedsLegacyInputGlossary(content)) {
            emptyList()
        } else if (index == 0) {
            job.dependencies.initialGlossary
        } else {
            val previous = job.windows.single { it.windowIndex == index - 1 }
            require(previous.state.isTerminal()) { "previous window must be terminal" }
            requireNotNull(readWindowCheckpoint(job, previous.windowIndex)).outputGlossary
        }
        json.decodeWindowCheckpoint(
            content = content,
            legacyInputGlossary = legacyInputGlossary,
            legacyContextTranslationRegionIds = window.contextTranslationRegionIds,
            legacyTranslationRegionIds = window.translationRegionIds,
        ).also {
            require(it.windowIndex == index && it.windowArtifactKey == window.windowArtifactKey)
        }
    }.getOrNull()

    fun cleanInterruptedWindow(job: TranslationJobRecord, index: Int) {
        requireValidJobStorageIdentity(job)
        val window = job.windows.single { it.windowIndex == index }
        require(window.state == TranslationWindowState.RUNNING)
        fileSystem.deleteIfExists(defaultWindowCheckpointPath(job, window))
    }

    /**
     * Discards a no-longer-trusted glossary-dependent suffix before its job
     * journal is rewritten for recovery. Deletions are deliberately
     * idempotent so a process death during recovery can safely repeat them.
     */
    fun discardWindowSuffix(job: TranslationJobRecord, firstWindowIndex: Int) {
        // Validate the complete journal before the first deletion. In
        // particular, a damaged page later in the suffix must not be able to
        // leave an earlier checkpoint half-deleted.
        requireValidJobStorageIdentity(job)
        require(job.windows.any { it.windowIndex == firstWindowIndex })
        val discardedWindows = job.windows.filter { it.windowIndex >= firstWindowIndex }
        val discardedRegionIds = discardedWindows
            .flatMapTo(mutableSetOf(), TranslationJobWindow::translationRegionIds)

        discardedWindows.forEach { window ->
            fileSystem.deleteIfExists(defaultWindowCheckpointPath(job, window))
        }
        job.pages
            .filter { page -> page.translationRegionIds.any(discardedRegionIds::contains) }
            .forEach { page ->
                fileSystem.deleteIfExists(defaultPageCheckpointPath(job, page))
            }
    }

    /** Removes legacy page aggregates before their journal states are reset to PENDING. */
    fun discardPageCheckpoints(job: TranslationJobRecord) {
        requireValidJobStorageIdentity(job)
        val paths = job.pages.map { page -> defaultPageCheckpointPath(job, page) }
        paths.forEach(fileSystem::deleteIfExists)
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
        requireValidGlossary(glossary.entries)
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
        SafeOpaqueId.require(runKey, "runKey")
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
        SafeOpaqueId.require(runKey, "runKey")
        val path = publishedDirectory(runKey).resolve("report.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also { require(it.runArtifactKey == runKey) }
        }.getOrNull()
    }

    fun readPublishedGlossary(runKey: String): TranslationGlossaryArtifact? {
        SafeOpaqueId.require(runKey, "runKey")
        val run = readPublishedRun(runKey) ?: return null
        val path = resolveInside(publishedDirectory(runKey), run.glossaryPath)
        return runCatching {
            json.decodeGlossary(fileSystem.readUtf8(path)).also { requireValidGlossary(it.entries) }
        }.getOrNull()
    }

    fun readPublishedPage(runKey: String, entry: TranslationRunEntry): PageTranslationArtifact? {
        SafeOpaqueId.require(runKey, "runKey")
        val run = readPublishedRun(runKey) ?: return null
        require(run.entries.any { it == entry })
        val path = resolveInside(publishedDirectory(runKey), entry.artifactPath)
        return runCatching {
            json.decodePageArtifact(fileSystem.readUtf8(path)).also { artifact ->
                require(artifact.pageId == entry.pageId)
                require(artifact.pageOrder == entry.pageOrder)
                require(artifact.ocrPageArtifactKey == entry.ocrPageArtifactKey)
                require(artifact.pageArtifactKey == entry.pageArtifactKey)
                require(artifact.dependencies == run.dependencies)
            }
        }.getOrNull()
    }

    private fun checkpointDirectory(job: TranslationJobRecord): Path {
        SafeOpaqueId.require(job.jobId, "jobId")
        SafeOpaqueId.require(job.runArtifactKey, "runArtifactKey")
        val root = projectDirectory.resolve("staging/translation").normalize()
        val directory = root.resolve(job.jobId).resolve(job.runArtifactKey).normalize()
        require(directory.startsWith(root) && directory.parent?.parent == root) {
            "translation checkpoint root escaped staging"
        }
        return directory
    }

    private fun defaultWindowCheckpointPath(job: TranslationJobRecord, window: TranslationJobWindow): Path {
        require(window.windowIndex >= 0)
        SafeOpaqueId.require(window.windowArtifactKey, "windowArtifactKey")
        return resolveInside(checkpointDirectory(job), defaultWindowCheckpointRelative(window))
    }

    private fun defaultPageCheckpointPath(job: TranslationJobRecord, page: TranslationJobPage): Path {
        require(page.pageOrder >= 0)
        SafeOpaqueId.require(page.pageId, "pageId")
        return resolveInside(checkpointDirectory(job), defaultPageCheckpointRelative(page))
    }

    private fun publishedDirectory(runKey: String): Path {
        SafeOpaqueId.require(runKey, "runKey")
        val root = projectDirectory.resolve("artifacts/translation").normalize()
        return resolveInside(root, runKey)
    }

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

    private fun requireValidJobStorageIdentity(job: TranslationJobRecord) {
        require(job.schemaVersion == TRANSLATION_SCHEMA_VERSION)
        SafeOpaqueId.require(job.jobId, "jobId")
        SafeOpaqueId.require(job.projectId, "projectId")
        SafeOpaqueId.require(job.runArtifactKey, "runArtifactKey")
        SafeOpaqueId.require(job.dependencies.ocrRunArtifactKey, "ocrRunArtifactKey")
        requireValidGlossary(job.dependencies.initialGlossary)
        checkpointDirectory(job)

        require(job.windows.map(TranslationJobWindow::windowIndex).distinct().size == job.windows.size)
        job.windows.forEach { window ->
            require(window.windowIndex >= 0)
            SafeOpaqueId.require(window.windowArtifactKey, "windowArtifactKey")
            window.contextTranslationRegionIds.forEach { SafeOpaqueId.require(it, "contextTranslationRegionId") }
            window.translationRegionIds.forEach { SafeOpaqueId.require(it, "translationRegionId") }
            require(window.attemptCount >= 0)
            require(window.translatedItemCount >= 0 && window.preservedItemCount >= 0)
            window.checkpointPath?.let { relative ->
                require(relative == defaultWindowCheckpointRelative(window)) {
                    "window checkpoint path does not match its identity"
                }
            }
        }

        require(job.pages.map(TranslationJobPage::pageOrder).distinct().size == job.pages.size)
        job.pages.forEach { page ->
            require(page.pageOrder >= 0)
            SafeOpaqueId.require(page.pageId, "pageId")
            SafeOpaqueId.require(page.ocrPageArtifactKey, "ocrPageArtifactKey")
            SafeOpaqueId.require(page.pageArtifactKey, "pageArtifactKey")
            page.translationRegionIds.forEach { SafeOpaqueId.require(it, "translationRegionId") }
            require(page.protectedOcrRegionCount >= 0)
            page.artifactPath?.let { relative ->
                require(relative == defaultPageCheckpointRelative(page)) {
                    "page checkpoint path does not match its identity"
                }
            }
        }
    }

    private fun defaultWindowCheckpointRelative(window: TranslationJobWindow): String =
        "windows/${window.windowIndex.toString().padStart(4, '0')}-${window.windowArtifactKey}.json"

    private fun defaultPageCheckpointRelative(page: TranslationJobPage): String =
        "pages/${page.pageOrder.toString().padStart(4, '0')}-${page.pageId}/translation.json"

    private fun requireValidOutcome(item: ValidatedTranslationItem) {
        SafeOpaqueId.require(item.translationRegionId, "translationRegionId")
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
}
