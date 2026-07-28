package rs.masumi.app.translation

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.translation.PageTranslationArtifact
import rs.masumi.core.translation.PageTranslationInput
import rs.masumi.core.translation.ProtectedTranslationRegion
import rs.masumi.core.translation.TranslationArtifactIdentity
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationBatchItem
import rs.masumi.core.translation.TranslationBatchPlanner
import rs.masumi.core.translation.TranslationBatchWindow
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationDependencies
import rs.masumi.core.translation.TranslationError
import rs.masumi.core.translation.TranslationGlossaryArtifact
import rs.masumi.core.translation.TranslationGlossaryEntry
import rs.masumi.core.translation.TranslationGlossaryMemory
import rs.masumi.core.translation.TranslationInputBuilder
import rs.masumi.core.translation.TranslationJobPage
import rs.masumi.core.translation.TranslationJobRecord
import rs.masumi.core.translation.TranslationJobReducer
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationJobWindow
import rs.masumi.core.translation.TranslationPageState
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPreserveReason
import rs.masumi.core.translation.TranslationPromptBuilder
import rs.masumi.core.translation.TranslationPromptMessages
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationProtectionReason
import rs.masumi.core.translation.TranslationProviderDependency
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationResponseValidator
import rs.masumi.core.translation.TranslationResultState
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.translation.TranslationRunEntry
import rs.masumi.core.translation.TranslationTextNormalizer
import rs.masumi.core.translation.TranslationUsage
import rs.masumi.core.translation.TranslationWindowArtifact
import rs.masumi.core.translation.TranslationWindowState
import rs.masumi.core.translation.ValidatedTranslationItem
import rs.masumi.core.translation.isSuccessful
import rs.masumi.core.translation.isTerminal

data class TranslationProgress(
    val projectId: String,
    val jobId: String,
    val runArtifactKey: String,
    val status: TranslationJobStatus,
    val terminalWindowCount: Int,
    val totalWindowCount: Int,
    val committedPageCount: Int,
    val totalPageCount: Int,
    val translatedItemCount: Int,
    val preservedItemCount: Int,
    val protectedOcrCount: Int,
    val currentWindowIndex: Int? = null,
    val errorCode: String? = null,
)

data class TranslationRunResult(
    val job: TranslationJobRecord,
    val runArtifact: TranslationRunArtifact? = null,
    val report: TranslationReport? = null,
    val publishedDirectory: Path? = null,
)

class TranslationRunner(
    workspaceRoot: Path,
    private val provider: TranslationProvider,
    private val policy: TranslationPolicy = TranslationPolicy(),
    private val prompt: TranslationPromptRef = TranslationPromptRef(),
    private val batching: TranslationBatchingConfig = TranslationBatchingConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
    private val glossaryMemory: TranslationGlossaryMemory? = null,
) {
    private val catalog = ProjectCatalog(workspaceRoot.toAbsolutePath().normalize())
    private val promptBuilder = TranslationPromptBuilder()
    private val responseValidator = TranslationResponseValidator()
    private val externallyCancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<TranslationProviderCall?>()

    fun cancel() {
        externallyCancelled.set(true)
        activeCall.get()?.cancel()
    }

    fun run(
        projectId: String,
        settings: TranslationProviderSettings,
        cancellation: () -> Boolean,
        onProgress: (TranslationProgress) -> Unit,
    ): TranslationRunResult {
        externallyCancelled.set(false)
        val project = requireNotNull(catalog.openProject(projectId)) { "project was not found" }
        val ocrRun = requireNotNull(catalog.latestPublishedOcrRun(projectId)) {
            "completed OCR run was not found"
        }
        validateOcrDependency(project, ocrRun)
        val occurrenceInputs = loadInputs(project, ocrRun)
        val canonicalInputs = occurrenceInputs.distinctBy(PageTranslationInput::pageId)
        // Series-level glossary keeps names and honorifics consistent across
        // chapters; it participates in the artifact identity, so a changed
        // glossary correctly invalidates cached translations.
        val initialGlossary = glossaryMemory?.load().orEmpty()
        val initialGlossarySha256 = TranslationArtifactIdentity.glossarySha256(initialGlossary)
        val dependencies = TranslationDependencies(
            ocrRunArtifactKey = ocrRun.artifact.runArtifactKey,
            policy = policy,
            prompt = prompt,
            batching = batching,
            provider = TranslationProviderDependency(
                modelId = sanitizedModelId(settings.model),
                temperature = settings.temperature,
                maximumOutputTokens = settings.maximumOutputTokens,
                requestJsonObjectFormat = settings.requestJsonObjectFormat,
                reference = settings.artifactReference(),
            ),
            initialGlossarySha256 = initialGlossarySha256,
        )
        val windows = TranslationBatchPlanner(batching, promptBuilder).plan(canonicalInputs)
        val pageKeys = occurrenceInputs.associate { input ->
            input.pageOrder to TranslationArtifactIdentity.pageArtifactKey(input.ocrPageArtifactKey, dependencies)
        }
        val runKey = TranslationArtifactIdentity.runArtifactKey(
            occurrenceInputs.map { it.pageOrder to pageKeys.getValue(it.pageOrder) },
            dependencies,
        )
        val store = TranslationArtifactStore(project.directory)
        readPublishedResult(store, project, runKey, dependencies)?.let { cached ->
            onProgress(cached.job.toProgress())
            return cached
        }

        var job = recoverOrCreateJob(
            store = store,
            project = project,
            inputs = occurrenceInputs,
            windows = windows,
            pageKeys = pageKeys,
            runKey = runKey,
            dependencies = dependencies,
        )
        store.prepareRun(job)

        fun isCancelled(): Boolean = externallyCancelled.get() || cancellation()
        fun persist(updated: TranslationJobRecord, currentWindowIndex: Int? = null): TranslationJobRecord {
            store.writeJob(updated)
            onProgress(updated.toProgress(currentWindowIndex))
            return updated
        }

        try {
            job = persist(TranslationJobReducer.startRunning(job, clock.millis()))
            val outcomes = linkedMapOf<String, ValidatedTranslationItem>()
            val windowArtifacts = mutableListOf<TranslationWindowArtifact>()
            var glossary = initialGlossary

            job.windows.sortedBy(TranslationJobWindow::windowIndex)
                .takeWhile { it.state.isTerminal() }
                .forEach { checkpoint ->
                    val artifact = store.readWindowCheckpoint(job, checkpoint.windowIndex)
                        ?: throw FatalTranslationException("COMMITTED_WINDOW_INVALID")
                    require(artifact.inputGlossarySha256 == TranslationArtifactIdentity.glossarySha256(glossary))
                    glossary = artifact.outputGlossary
                    artifact.items.forEach { outcomes[it.translationRegionId] = it }
                    windowArtifacts += artifact
                }

            windows.sortedBy(TranslationBatchWindow::windowIndex).forEach { planned ->
                val checkpoint = job.windows.single { it.windowIndex == planned.windowIndex }
                if (checkpoint.state.isTerminal()) return@forEach
                if (isCancelled()) throw TranslationCancellationSignal()

                val inputGlossarySha256 = TranslationArtifactIdentity.glossarySha256(glossary)
                val activeWindow = planned.copy(glossary = glossary)
                val windowKey = TranslationArtifactIdentity.windowArtifactKey(
                    activeWindow,
                    inputGlossarySha256,
                    dependencies,
                )
                job = persist(
                    TranslationJobReducer.prepareWindow(job, planned.windowIndex, windowKey, clock.millis()),
                    planned.windowIndex,
                )
                job = persist(
                    TranslationJobReducer.startWindow(job, planned.windowIndex, clock.millis()),
                    planned.windowIndex,
                )
                val artifact = executeWindow(
                    window = activeWindow,
                    windowKey = windowKey,
                    inputGlossarySha256 = inputGlossarySha256,
                    inputGlossary = glossary,
                    settings = settings,
                    cancellation = ::isCancelled,
                )
                val checkpointPath = store.commitWindow(job, artifact)
                val translated = artifact.items.count { it.state == TranslationResultState.TRANSLATED }
                val preserved = artifact.items.size - translated
                job = persist(
                    TranslationJobReducer.commitWindow(
                        job = job,
                        index = planned.windowIndex,
                        checkpointPath = checkpointPath,
                        translatedCount = translated,
                        preservedCount = preserved,
                        usage = artifact.usage,
                        error = artifact.error,
                        now = clock.millis(),
                    ),
                    planned.windowIndex,
                )
                glossary = artifact.outputGlossary
                artifact.items.forEach { outcomes[it.translationRegionId] = it }
                windowArtifacts += artifact
            }

            // A model can return valid prose wrapped in malformed JSON for a
            // large batch. Split only those failed batches and retry smaller
            // groups; semantic item omissions are retried in isolation.
            val recoveredIds = salvagePreservedItems(
                windows = windows,
                artifacts = windowArtifacts,
                outcomes = outcomes,
                glossary = glossary,
                dependencies = dependencies,
                settings = settings,
                cancellation = ::isCancelled,
            )
            if (recoveredIds.isNotEmpty()) {
                for (index in windowArtifacts.indices) {
                    val artifact = windowArtifacts[index]
                    windowArtifacts[index] = artifact.copy(
                        items = artifact.items.map { outcomes[it.translationRegionId] ?: it },
                    )
                }
                // The job's window counters drive the final status and the
                // progress numbers; without this the run keeps reporting the
                // pre-salvage outcome it no longer publishes.
                recoveredIds
                    .groupingBy { id ->
                        windows.single { window ->
                            window.items.any { it.input.translationRegionId == id }
                        }.windowIndex
                    }
                    .eachCount()
                    .forEach { (windowIndex, count) ->
                        job = persist(
                            TranslationJobReducer.salvageWindowItems(job, windowIndex, count, clock.millis()),
                        )
                    }
            }
            failWhenRequiredTranslationsRemain(outcomes, windowArtifacts)

            occurrenceInputs.sortedBy(PageTranslationInput::pageOrder).forEach { input ->
                val checkpoint = job.pages.single { it.pageOrder == input.pageOrder }
                if (checkpoint.state == TranslationPageState.COMMITTED) return@forEach
                if (isCancelled()) throw TranslationCancellationSignal()
                val artifact = PageTranslationArtifact(
                    pageId = input.pageId,
                    pageOrder = input.pageOrder,
                    ocrPageArtifactKey = input.ocrPageArtifactKey,
                    pageArtifactKey = checkpoint.pageArtifactKey,
                    dependencies = dependencies,
                    items = input.items.map { item ->
                        outcomes[item.translationRegionId]
                            ?: throw FatalTranslationException("WINDOW_OUTCOME_MISSING")
                    },
                    protectedOcrRegions = input.protectedRegions,
                )
                val artifactPath = store.commitPage(job, artifact)
                job = persist(
                    TranslationJobReducer.commitPage(job, input.pageOrder, artifactPath, clock.millis()),
                )
            }

            val finishedAt = clock.millis()
            job = TranslationJobReducer.finishSuccess(job, finishedAt)
            val run = job.toRunArtifact(finishedAt)
            val report = job.toReport(finishedAt, windowArtifacts)
            val glossaryArtifact = TranslationGlossaryArtifact(
                sha256 = TranslationArtifactIdentity.glossarySha256(glossary),
                entries = glossary,
            )
            val published = store.publishRun(job, run, glossaryArtifact, report)
            glossaryMemory?.record(glossary)
            job = persist(job)
            return TranslationRunResult(job, run, report, published)
        } catch (_: TranslationCancellationSignal) {
            activeCall.getAndSet(null)?.cancel()
            if (job.status == TranslationJobStatus.RUNNING) {
                if (!job.cancelRequested) {
                    job = persist(TranslationJobReducer.requestCancellation(job, clock.millis()))
                }
                job = persist(TranslationJobReducer.finishCancellation(job, clock.millis()))
            }
            return TranslationRunResult(job)
        } catch (failure: Throwable) {
            val error = failure.toFatalError()
            if (job.status == TranslationJobStatus.RUNNING || job.status == TranslationJobStatus.QUEUED) {
                job = TranslationJobReducer.fail(job, error, clock.millis())
                store.writeJob(job)
            }
            onProgress(job.toProgress(errorCode = error.code))
            return TranslationRunResult(job)
        } finally {
            activeCall.getAndSet(null)?.cancel()
        }
    }

    private fun executeWindow(
        window: TranslationBatchWindow,
        windowKey: String,
        inputGlossarySha256: String,
        inputGlossary: List<TranslationGlossaryEntry>,
        settings: TranslationProviderSettings,
        cancellation: () -> Boolean,
        discoverGlossary: Boolean = true,
    ): TranslationWindowArtifact {
        val started = clock.millis()
        if (window.exceedsBudget) {
            val items = window.items.map { item -> item.preserved(TranslationPreserveReason.OVERSIZED_INPUT) }
            return TranslationWindowArtifact(
                windowIndex = window.windowIndex,
                windowArtifactKey = windowKey,
                inputGlossarySha256 = inputGlossarySha256,
                outputGlossarySha256 = inputGlossarySha256,
                outputGlossary = inputGlossary,
                items = items,
                ignoredResponseIds = emptyList(),
                attemptCount = 0,
                durationMillis = (clock.millis() - started).coerceAtLeast(0L),
                error = TranslationError("OVERSIZED_INPUT"),
            )
        }
        if (cancellation()) throw TranslationCancellationSignal()
        var attemptCount = 0
        var usage: TranslationUsage? = null
        var workingGlossary = inputGlossary

        if (discoverGlossary) {
            val discoveryWindow = window.copy(
                glossary = inputGlossary,
                contextItems = (window.contextItems + window.items)
                    .distinctBy { it.input.translationRegionId },
                items = emptyList(),
            )
            try {
                val discovery = executeProviderCall(
                    settings = settings,
                    messages = promptBuilder.buildGlossaryDiscovery(window),
                    cancellation = cancellation,
                )
                attemptCount += discovery.attemptCount
                usage = usage.plus(discovery.usage)
                val discovered = responseValidator.validate(discoveryWindow, discovery.response)
                workingGlossary = mergeGlossary(workingGlossary, discovered.glossaryUpdates)
            } catch (failure: TranslationProviderException) {
                if (failure.code == TranslationProviderErrorCode.CANCELLED || cancellation()) {
                    throw TranslationCancellationSignal()
                }
                attemptCount += failure.attemptCount
            }
        }

        val translationWindow = window.copy(glossary = workingGlossary)
        return try {
            val result = executeProviderCall(
                settings = settings,
                messages = promptBuilder.build(translationWindow),
                cancellation = cancellation,
            )
            attemptCount += result.attemptCount
            usage = usage.plus(result.usage)
            val validation = responseValidator.validate(translationWindow, result.response)
            val outputGlossary = mergeGlossary(workingGlossary, validation.glossaryUpdates)
            val normalizedItems = validation.items.map { item ->
                val translated = item.translatedText ?: return@map item
                val source = translationWindow.items.single {
                    it.input.translationRegionId == item.translationRegionId
                }.input.sourceText
                item.copy(
                    translatedText = TranslationTextNormalizer.normalize(
                        sourceText = source,
                        translatedText = translated,
                        glossary = outputGlossary,
                    ),
                )
            }
            TranslationWindowArtifact(
                windowIndex = window.windowIndex,
                windowArtifactKey = windowKey,
                inputGlossarySha256 = inputGlossarySha256,
                outputGlossarySha256 = TranslationArtifactIdentity.glossarySha256(outputGlossary),
                outputGlossary = outputGlossary,
                items = normalizedItems,
                ignoredResponseIds = validation.ignoredResponseIds,
                usage = usage,
                providerModelId = result.modelId,
                attemptCount = attemptCount,
                durationMillis = (clock.millis() - started).coerceAtLeast(0L),
            )
        } catch (failure: TranslationProviderException) {
            if (failure.code == TranslationProviderErrorCode.CANCELLED || cancellation()) {
                throw TranslationCancellationSignal()
            }
            attemptCount += failure.attemptCount
            val items = window.items.map { item -> item.preserved(TranslationPreserveReason.PROVIDER_FAILURE) }
            TranslationWindowArtifact(
                windowIndex = window.windowIndex,
                windowArtifactKey = windowKey,
                inputGlossarySha256 = inputGlossarySha256,
                outputGlossarySha256 = TranslationArtifactIdentity.glossarySha256(workingGlossary),
                outputGlossary = workingGlossary,
                items = items,
                ignoredResponseIds = emptyList(),
                usage = usage,
                attemptCount = attemptCount,
                durationMillis = (clock.millis() - started).coerceAtLeast(0L),
                error = TranslationError(failure.code.name, failure.httpStatus),
            )
        }
    }

    private fun executeProviderCall(
        settings: TranslationProviderSettings,
        messages: TranslationPromptMessages,
        cancellation: () -> Boolean,
    ): TranslationProviderResult {
        if (cancellation()) throw TranslationCancellationSignal()
        val call = provider.newCall(settings, messages)
        activeCall.set(call)
        return try {
            val result = call.execute()
            if (cancellation()) throw TranslationCancellationSignal()
            result
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun TranslationUsage?.plus(other: TranslationProviderUsage?): TranslationUsage? {
        if (this == null && other == null) return null
        return TranslationUsage(
            promptTokens = sumKnown(this?.promptTokens, other?.promptTokens),
            completionTokens = sumKnown(this?.completionTokens, other?.completionTokens),
            totalTokens = sumKnown(this?.totalTokens, other?.totalTokens),
        )
    }

    private fun sumKnown(first: Long?, second: Long?): Long? {
        if (first == null && second == null) return null
        return (first ?: 0L) + (second ?: 0L)
    }

    private fun loadInputs(project: ProjectRef, ocrRun: PublishedOcrRun): List<PageTranslationInput> {
        val canonical = mutableMapOf<String, PageTranslationInput>()
        return project.manifest.pages.sortedBy(PageRecord::order).map { page ->
            val entry = ocrRun.artifact.entries.single { it.order == page.order }
            val base = canonical.getOrPut(page.pageId) {
                when (entry.state) {
                    OcrPageState.COMMITTED -> TranslationInputBuilder(policy, prompt).build(
                        page.order,
                        catalog.readPublishedOcrPage(ocrRun, page.pageId)
                            ?: throw FatalTranslationException("OCR_PAGE_INVALID"),
                    )
                    OcrPageState.PRESERVED_SOURCE -> PageTranslationInput(
                        pageId = page.pageId,
                        pageOrder = page.order,
                        ocrPageArtifactKey = entry.pageArtifactKey,
                        policy = policy,
                        prompt = prompt,
                        items = emptyList(),
                        protectedRegions = listOf(
                            ProtectedTranslationRegion(
                                ocrRegionId = page.pageId,
                                readingOrderRank = 0,
                                reason = TranslationProtectionReason.OCR_NOT_TRUSTED,
                            ),
                        ),
                    )
                    OcrPageState.PENDING,
                    OcrPageState.RUNNING,
                    -> throw FatalTranslationException("OCR_RUN_NOT_TERMINAL")
                }
            }
            base.copy(pageOrder = page.order)
        }
    }

    private fun recoverOrCreateJob(
        store: TranslationArtifactStore,
        project: ProjectRef,
        inputs: List<PageTranslationInput>,
        windows: List<TranslationBatchWindow>,
        pageKeys: Map<Int, String>,
        runKey: String,
        dependencies: TranslationDependencies,
    ): TranslationJobRecord {
        val candidate = store.findResumableJob()?.takeIf {
            it.projectId == project.manifest.projectId &&
                it.runArtifactKey == runKey &&
                it.dependencies == dependencies
        }
        if (candidate != null) {
            candidate.windows.filter { it.state == TranslationWindowState.RUNNING }.forEach {
                store.cleanInterruptedWindow(candidate, it.windowIndex)
            }
            val recovered = when (candidate.status) {
                TranslationJobStatus.QUEUED -> candidate
                TranslationJobStatus.RUNNING,
                TranslationJobStatus.CANCELLED,
                -> TranslationJobReducer.recoverInterrupted(candidate, clock.millis())
                else -> error("terminal translation job cannot be resumed")
            }
            store.writeJob(recovered)
            return recovered
        }
        val now = clock.millis()
        return TranslationJobRecord(
            jobId = idSource.nextId(),
            projectId = project.manifest.projectId,
            runArtifactKey = runKey,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            dependencies = dependencies,
            windows = windows.map { window ->
                TranslationJobWindow(
                    windowIndex = window.windowIndex,
                    windowArtifactKey = TranslationArtifactIdentity.windowArtifactKey(
                        window,
                        dependencies.initialGlossarySha256,
                        dependencies,
                    ),
                    translationRegionIds = window.items.map { it.input.translationRegionId },
                )
            },
            pages = inputs.map { input ->
                TranslationJobPage(
                    pageId = input.pageId,
                    pageOrder = input.pageOrder,
                    ocrPageArtifactKey = input.ocrPageArtifactKey,
                    pageArtifactKey = pageKeys.getValue(input.pageOrder),
                    translationRegionIds = input.items.map { it.translationRegionId },
                    protectedOcrRegionCount = input.protectedRegions.size,
                )
            },
        ).also(store::writeJob)
    }

    private fun readPublishedResult(
        store: TranslationArtifactStore,
        project: ProjectRef,
        runKey: String,
        dependencies: TranslationDependencies,
    ): TranslationRunResult? {
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(artifact.projectId == project.manifest.projectId && artifact.dependencies == dependencies)
        val job = store.readJob(report.jobId) ?: return null
        require(job.status == report.status && job.status.isSuccessful())
        return TranslationRunResult(
            job = job,
            runArtifact = artifact,
            report = report,
            publishedDirectory = project.directory.resolve("artifacts/translation/$runKey"),
        )
    }

    private fun validateOcrDependency(project: ProjectRef, run: PublishedOcrRun) {
        require(run.artifact.projectId == project.manifest.projectId)
        require(run.artifact.entries.size == project.manifest.pages.size)
        project.manifest.pages.forEach { page ->
            val entry = run.artifact.entries.single { it.order == page.order }
            require(entry.pageId == page.pageId)
        }
    }

    private fun mergeGlossary(
        existing: List<TranslationGlossaryEntry>,
        updates: List<TranslationGlossaryEntry>,
    ): List<TranslationGlossaryEntry> {
        val merged = linkedMapOf<String, String>()
        existing.forEach { merged[it.source] = it.translation }
        updates.forEach { merged.putIfAbsent(it.source, it.translation) }
        return merged.map { TranslationGlossaryEntry(it.key, it.value) }
            .sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
    }

    private fun TranslationJobRecord.toRunArtifact(createdAt: Long): TranslationRunArtifact =
        TranslationRunArtifact(
            runArtifactKey = runArtifactKey,
            projectId = projectId,
            createdAtEpochMillis = createdAt,
            dependencies = dependencies,
            entries = pages.sortedBy(TranslationJobPage::pageOrder).map { page ->
                TranslationRunEntry(
                    pageId = page.pageId,
                    pageOrder = page.pageOrder,
                    ocrPageArtifactKey = page.ocrPageArtifactKey,
                    pageArtifactKey = page.pageArtifactKey,
                    artifactPath = requireNotNull(page.artifactPath),
                )
            },
            glossaryPath = "glossary.json",
        )

    private fun TranslationJobRecord.toReport(
        finishedAt: Long,
        artifacts: List<TranslationWindowArtifact>,
    ): TranslationReport = TranslationReport(
        jobId = jobId,
        projectId = projectId,
        runArtifactKey = runArtifactKey,
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAt,
        status = status,
        totalPageCount = pages.size,
        committedPageCount = pages.count { it.state == TranslationPageState.COMMITTED },
        totalWindowCount = windows.size,
        committedWindowCount = windows.count { it.state.isTerminal() },
        translatedItemCount = windows.sumOf(TranslationJobWindow::translatedItemCount),
        preservedItemCount = windows.sumOf(TranslationJobWindow::preservedItemCount),
        protectedOcrRegionCount = pages.sumOf(TranslationJobPage::protectedOcrRegionCount),
        promptTokens = artifacts.sumOf { it.usage?.promptTokens ?: 0L },
        completionTokens = artifacts.sumOf { it.usage?.completionTokens ?: 0L },
        totalTokens = artifacts.sumOf { it.usage?.totalTokens ?: 0L },
        retryCount = artifacts.sumOf { (it.attemptCount - EXPECTED_PROVIDER_CALLS).coerceAtLeast(0) },
        provider = dependencies.provider.reference,
    )

    private fun TranslationJobRecord.toProgress(
        currentWindowIndex: Int? = windows.firstOrNull { it.state == TranslationWindowState.RUNNING }?.windowIndex,
        errorCode: String? = error?.code,
    ): TranslationProgress = TranslationProgress(
        projectId = projectId,
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = status,
        terminalWindowCount = windows.count { it.state.isTerminal() },
        totalWindowCount = windows.size,
        committedPageCount = pages.count { it.state == TranslationPageState.COMMITTED },
        totalPageCount = pages.size,
        translatedItemCount = windows.sumOf(TranslationJobWindow::translatedItemCount),
        preservedItemCount = windows.sumOf(TranslationJobWindow::preservedItemCount),
        protectedOcrCount = pages.sumOf(TranslationJobPage::protectedOcrRegionCount),
        currentWindowIndex = currentWindowIndex,
        errorCode = errorCode,
    )

    private fun rs.masumi.core.translation.TranslationBatchItem.preserved(
        reason: TranslationPreserveReason,
    ): ValidatedTranslationItem = ValidatedTranslationItem(
        translationRegionId = input.translationRegionId,
        ocrRegionId = input.ocrRegionId,
        state = TranslationResultState.PRESERVED_SOURCE,
        preserveReason = reason,
    )

    private fun sanitizedModelId(model: String): String {
        val normalized = model.trim()
        if (normalized.length in 1..128 && SAFE_MODEL_ID.matches(normalized)) return normalized
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "model-${digest.take(16)}"
    }

    private fun Throwable.toFatalError(): TranslationError = when (this) {
        is TerminalProviderFailure -> error
        is FatalTranslationException -> TranslationError(code)
        is IllegalArgumentException -> TranslationError("PROJECT_INVALID")
        else -> TranslationError("TRANSLATION_RUN_FAILED")
    }

    private class TranslationCancellationSignal : RuntimeException()
    private class FatalTranslationException(val code: String) : RuntimeException(code)
    private fun salvagePreservedItems(
        windows: List<TranslationBatchWindow>,
        artifacts: List<TranslationWindowArtifact>,
        outcomes: MutableMap<String, ValidatedTranslationItem>,
        glossary: List<TranslationGlossaryEntry>,
        dependencies: TranslationDependencies,
        settings: TranslationProviderSettings,
        cancellation: () -> Boolean,
    ): Set<String> {
        val template = windows.firstOrNull() ?: return emptySet()
        val batchItemsById = windows.flatMap(TranslationBatchWindow::items)
            .associateBy { it.input.translationRegionId }
        val glossarySha256 = TranslationArtifactIdentity.glossarySha256(glossary)
        val recovered = mutableSetOf<String>()
        var recoveryWindowIndex = SALVAGE_WINDOW_INDEX_BASE

        fun request(items: List<TranslationBatchItem>): TranslationWindowArtifact {
            if (cancellation()) throw TranslationCancellationSignal()
            val window = template.copy(
                windowIndex = recoveryWindowIndex++,
                glossary = glossary,
                contextItems = emptyList(),
                items = items,
                estimatedInputTokens = 0,
                exceedsBudget = false,
            )
            return executeWindow(
                window = window,
                windowKey = TranslationArtifactIdentity.windowArtifactKey(window, glossarySha256, dependencies),
                inputGlossarySha256 = glossarySha256,
                inputGlossary = glossary,
                settings = settings,
                cancellation = cancellation,
                discoverGlossary = false,
            )
        }

        fun recoverGroup(items: List<TranslationBatchItem>, retryWholeGroup: Boolean) {
            if (items.isEmpty()) return
            if (!retryWholeGroup && items.size > 1) {
                val midpoint = items.size / 2
                recoverGroup(items.subList(0, midpoint), retryWholeGroup = true)
                recoverGroup(items.subList(midpoint, items.size), retryWholeGroup = true)
                return
            }
            val artifact = request(items)
            artifact.items
                .filter { it.state == TranslationResultState.TRANSLATED }
                .forEach { translated ->
                    outcomes[translated.translationRegionId] = translated
                    recovered += translated.translationRegionId
                }
            val remaining = artifact.items
                .filter { it.state == TranslationResultState.PRESERVED_SOURCE }
                .mapNotNull { batchItemsById[it.translationRegionId] }
            if (
                remaining.isNotEmpty() &&
                items.size > 1 &&
                artifact.error?.code
                    ?.let { it == TranslationProviderErrorCode.MALFORMED_RESPONSE.name }
                    != false
            ) {
                if (remaining.size == 1) {
                    recoverGroup(remaining, retryWholeGroup = true)
                } else {
                    val midpoint = remaining.size / 2
                    recoverGroup(remaining.subList(0, midpoint), retryWholeGroup = true)
                    recoverGroup(remaining.subList(midpoint, remaining.size), retryWholeGroup = true)
                }
            }
        }

        // Retrying the same malformed batch tends to reproduce the same JSON
        // truncation. Start one level smaller, then keep bisecting only a group
        // that still fails.
        artifacts
            .filter { it.error?.code == TranslationProviderErrorCode.MALFORMED_RESPONSE.name }
            .forEach { artifact ->
                recoverGroup(
                    items = artifact.items.mapNotNull { batchItemsById[it.translationRegionId] },
                    retryWholeGroup = artifact.items.size == 1,
                )
            }

        // Successful responses can still omit or invalidate individual items.
        // One isolated request removes batch interactions and gives each item a
        // final bounded provider retry.
        outcomes.values
            .filter {
                it.state == TranslationResultState.PRESERVED_SOURCE &&
                    it.preserveReason in SEMANTIC_RECOVERY_REASONS
            }
            .mapNotNull { batchItemsById[it.translationRegionId] }
            .forEach { item ->
                recoverGroup(listOf(item), retryWholeGroup = true)
            }

        return recovered
    }

    private fun failWhenRequiredTranslationsRemain(
        outcomes: Map<String, ValidatedTranslationItem>,
        artifacts: List<TranslationWindowArtifact>,
    ) {
        val remainingIds = outcomes.values
            .filter {
                it.state == TranslationResultState.PRESERVED_SOURCE &&
                    it.preserveReason in NON_PUBLISHABLE_REASONS
            }
            .mapTo(mutableSetOf(), ValidatedTranslationItem::translationRegionId)
        if (remainingIds.isEmpty()) return
        val providerError = artifacts.firstNotNullOfOrNull { artifact ->
            artifact.error?.takeIf {
                artifact.items.any { item -> item.translationRegionId in remainingIds }
            }
        }
        throw TerminalProviderFailure(providerError ?: TranslationError("INCOMPLETE_TRANSLATION_RESPONSE"))
    }

    private class TerminalProviderFailure(val error: TranslationError) : RuntimeException(error.code)

    private companion object {
        const val EXPECTED_PROVIDER_CALLS = 2
        val SAFE_MODEL_ID = Regex("[A-Za-z0-9._:/-]+")
        val SEMANTIC_RECOVERY_REASONS = setOf(
            TranslationPreserveReason.MISSING_RESPONSE,
            TranslationPreserveReason.DUPLICATE_RESPONSE,
            TranslationPreserveReason.INVALID_ROLE,
            TranslationPreserveReason.BLANK_TRANSLATION,
        )
        val NON_PUBLISHABLE_REASONS = SEMANTIC_RECOVERY_REASONS + TranslationPreserveReason.PROVIDER_FAILURE
        const val SALVAGE_WINDOW_INDEX_BASE = 100_000
    }
}
