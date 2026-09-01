package rs.masumi.app.translation

import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.pipeline.PipelineArtifactFreshness
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.UuidIdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.translation.PageTranslationArtifact
import rs.masumi.core.translation.TRANSLATION_SCHEMA_VERSION
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
import rs.masumi.core.translation.TranslationOutputValidationConfig
import rs.masumi.core.translation.TranslationPageState
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPreserveReason
import rs.masumi.core.translation.TranslationPromptBuilder
import rs.masumi.core.translation.TranslationPromptMessages
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationProtectionReason
import rs.masumi.core.translation.TranslationProviderDependency
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationRecoveryCompatibility
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
    private val outputValidation: TranslationOutputValidationConfig = TranslationOutputValidationConfig(),
) {
    private val catalog = ProjectCatalog(workspaceRoot.toAbsolutePath().normalize())
    private val promptBuilder = TranslationPromptBuilder()
    private val responseValidator = TranslationResponseValidator(outputValidation)
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
        val detectionRun = requireNotNull(catalog.publishedDetectionRuns(projectId).firstOrNull {
            PipelineArtifactFreshness.detection(it.artifact, project.manifest)
        }) {
            "completed detection run was not found"
        }
        val ocrRun = requireNotNull(
            catalog.publishedOcrRuns(projectId).firstOrNull {
                PipelineArtifactFreshness.ocr(it.artifact, detectionRun.artifact)
            },
        ) {
            "completed OCR run was not found"
        }
        validateOcrDependency(project, ocrRun)
        val occurrenceInputs = loadInputs(project, ocrRun)
        val canonicalInputs = occurrenceInputs.distinctBy(PageTranslationInput::pageId)
        // Series-level glossary keeps names and honorifics consistent across
        // chapters; it participates in the artifact identity, so a changed
        // glossary correctly invalidates cached translations.
        val initialGlossary = mergeGlossary(emptyList(), glossaryMemory?.load().orEmpty())
        val dependencies = TranslationDependencies(
            ocrRunArtifactKey = ocrRun.artifact.runArtifactKey,
            policy = policy,
            prompt = prompt,
            batching = batching,
            outputValidation = outputValidation,
            provider = TranslationProviderDependency(
                modelId = settings.model.trim(),
                temperature = settings.temperature,
                maximumOutputTokens = settings.maximumOutputTokens,
                requestJsonObjectFormat = settings.requestJsonObjectFormat,
                reference = settings.artifactReference(),
            ),
            initialGlossary = initialGlossary,
        )
        val windows = TranslationBatchPlanner(batching, promptBuilder).plan(
            canonicalInputs,
            initialGlossary.associate { entry -> entry.source to entry.translation },
        )
        val store = TranslationArtifactStore(project.directory)
        val reusable = catalog.publishedTranslationRuns(projectId).firstOrNull { published ->
            published.artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION &&
                published.artifact.dependencies == dependencies &&
                published.artifact.entries.map { it.pageOrder to it.pageId to it.ocrPageArtifactKey } ==
                occurrenceInputs.map { it.pageOrder to it.pageId to it.ocrPageArtifactKey }
        }
        reusable?.let { published ->
            val cached = requireNotNull(readPublishedResult(store, project, published.artifact.runArtifactKey, dependencies))
            onProgress(cached.job.toProgress())
            return cached
        }

        var job = recoverOrCreateJob(
            store = store,
            project = project,
            inputs = occurrenceInputs,
            windows = windows,
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
                    require(artifact.inputGlossary == glossary)
                    glossary = artifact.outputGlossary
                    artifact.items.forEach { outcomes[it.translationRegionId] = it }
                    windowArtifacts += artifact
                }

            windows.sortedBy(TranslationBatchWindow::windowIndex).forEach { planned ->
                val checkpoint = job.windows.single { it.windowIndex == planned.windowIndex }
                if (checkpoint.state.isTerminal()) return@forEach
                if (isCancelled()) throw TranslationCancellationSignal()

                val activeWindow = planned.copy(glossary = glossary)
                val windowKey = checkpoint.windowArtifactKey
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

            // Old resumable jobs may contain a pre-fail-fast provider-error
            // checkpoint. Never publish that legacy preserved result.
            failIfNonPublishable(outcomes.values.toList())

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
                entries = glossary,
            )
            val published = store.publishRun(job, run, glossaryArtifact, report)
            job = persist(job)
            runCatching { glossaryMemory?.record(glossary) }
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

    internal fun executeWindow(
        window: TranslationBatchWindow,
        windowKey: String,
        inputGlossary: List<TranslationGlossaryEntry>,
        settings: TranslationProviderSettings,
        cancellation: () -> Boolean,
    ): TranslationWindowArtifact {
        val started = clock.millis()
        if (window.exceedsBudget) {
            val items = window.items.map { item -> item.preserved(TranslationPreserveReason.OVERSIZED_INPUT) }
            return TranslationWindowArtifact(
                windowIndex = window.windowIndex,
                windowArtifactKey = windowKey,
                contextTranslationRegionIds = window.contextItems.map { it.input.translationRegionId },
                translationRegionIds = window.items.map { it.input.translationRegionId },
                inputGlossary = inputGlossary,
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
            throwProviderFailure(failure, cancellation)
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
            // Validate the response against the glossary that existed before
            // this response. A bad sibling must not smuggle a response-local
            // glossary update into the typography repair of an otherwise
            // valid sibling.
            val baseFirstPassItems = normalizeAndValidateItems(
                translationWindow,
                validation.items,
                workingGlossary,
            )
            val candidateGlossary = mergeGlossary(workingGlossary, validation.glossaryUpdates)
            val candidateFirstPassItems = if (baseFirstPassItems.all(::isGlossaryAcceptableItem)) {
                normalizeAndValidateItems(translationWindow, validation.items, candidateGlossary)
            } else {
                baseFirstPassItems
            }
            val acceptFirstPassGlossary = baseFirstPassItems.any {
                it.state == TranslationResultState.TRANSLATED
            } && baseFirstPassItems.all(::isGlossaryAcceptableItem) &&
                candidateFirstPassItems.all(::isGlossaryAcceptableItem)
            val firstPassItems = if (acceptFirstPassGlossary) {
                candidateFirstPassItems
            } else {
                baseFirstPassItems
            }
            // Missing, duplicate, invalid-role, and blank results are protocol
            // failures. Stop the whole window before spending another request
            // on an unrelated semantic repair.
            failIfNonPublishable(firstPassItems)
            val acceptedFirstPassGlossaryUpdates = if (acceptFirstPassGlossary) {
                validation.glossaryUpdates
            } else {
                emptyList()
            }
            val firstPassGlossary = mergeGlossary(workingGlossary, acceptedFirstPassGlossaryUpdates)
            val invalidItems = translationWindow.items.filter { batchItem ->
                firstPassItems.single { it.translationRegionId == batchItem.input.translationRegionId }
                    .preserveReason in INVALID_OUTPUT_REASONS
            }
            val outputGlossary = firstPassGlossary
            var retryModelId: String? = null
            val retryItems = linkedMapOf<String, ValidatedTranslationItem>()
            val retryIgnoredResponseIds = mutableListOf<String>()
            invalidItems.forEach { invalidItem ->
                val retryWindow = translationWindow.copy(
                    // Every invalid item gets exactly one isolated retry. The
                    // original context and pre-response glossary remain on the
                    // copied window; valid siblings are never resent.
                    items = listOf(invalidItem),
                    estimatedInputTokens = 0,
                    exceedsBudget = false,
                )
                try {
                    val retryResult = executeProviderCall(
                        settings = settings,
                        messages = promptBuilder.build(retryWindow),
                        cancellation = cancellation,
                    )
                    attemptCount += retryResult.attemptCount
                    usage = usage.plus(retryResult.usage)
                    val retryValidation = responseValidator.validate(retryWindow, retryResult.response)
                    retryIgnoredResponseIds += retryValidation.ignoredResponseIds
                    // Isolated repair may replace only the failed item. Its
                    // response cannot amend the glossary shared by successful
                    // siblings or later windows.
                    val normalizedRetry = normalizeAndValidateItems(
                        retryWindow,
                        retryValidation.items,
                        firstPassGlossary,
                    ).single()
                    // A semantically invalid quality repair remains the
                    // original INVALID_* internal result. Blank, missing, or
                    // duplicate repair output never issues a third request.
                    if (normalizedRetry.state == TranslationResultState.TRANSLATED) {
                        retryItems[normalizedRetry.translationRegionId] = normalizedRetry
                    }
                    retryModelId = retryResult.modelId ?: retryModelId
                } catch (failure: TranslationProviderException) {
                    throwProviderFailure(failure, cancellation)
                }
            }
            val normalizedItems = firstPassItems.map { item -> retryItems[item.translationRegionId] ?: item }
            failIfNonPublishable(normalizedItems)
            TranslationWindowArtifact(
                windowIndex = window.windowIndex,
                windowArtifactKey = windowKey,
                contextTranslationRegionIds = window.contextItems.map { it.input.translationRegionId },
                translationRegionIds = window.items.map { it.input.translationRegionId },
                inputGlossary = inputGlossary,
                outputGlossary = outputGlossary,
                items = normalizedItems,
                ignoredResponseIds = (validation.ignoredResponseIds + retryIgnoredResponseIds)
                    .distinct()
                    .sorted(),
                usage = usage,
                providerModelId = retryModelId ?: result.modelId,
                attemptCount = attemptCount,
                durationMillis = (clock.millis() - started).coerceAtLeast(0L),
            )
        } catch (failure: TranslationProviderException) {
            throwProviderFailure(failure, cancellation)
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
        dependencies: TranslationDependencies,
    ): TranslationJobRecord {
        val pageLineage = inputs.map { input ->
            input.pageOrder to input.pageId to input.ocrPageArtifactKey
        }
        val windowLineage = windows.map { window ->
            Triple(
                window.windowIndex,
                window.contextItems.map { item -> item.input.translationRegionId },
                window.items.map { item -> item.input.translationRegionId },
            )
        }
        val candidate = store.findRecoveryCandidates().firstNotNullOfOrNull { existing ->
            if (
                existing.projectId != project.manifest.projectId ||
                existing.schemaVersion != TRANSLATION_SCHEMA_VERSION ||
                existing.pages.map { page -> page.pageOrder to page.pageId to page.ocrPageArtifactKey } != pageLineage
            ) {
                return@firstNotNullOfOrNull null
            }
            val existingWindowLineage = existing.windows.map { window ->
                Triple(window.windowIndex, window.contextTranslationRegionIds, window.translationRegionIds)
            }
            if (existing.dependencies == dependencies && existingWindowLineage == windowLineage) {
                TranslationRecoverySelection(existing)
            } else {
                TranslationRecoveryCompatibility.upgradeLegacyJob(existing, dependencies, windows)
                    ?.let { upgraded -> TranslationRecoverySelection(upgraded, legacySource = existing) }
            }
        }
        if (candidate != null) {
            candidate.legacySource?.let(store::discardPageCheckpoints)
            val recovered = TranslationJobReducer.recoverInterrupted(candidate.job, clock.millis())
            recovered.windows
                .filter { it.state == TranslationWindowState.PENDING }
                .minOfOrNull(TranslationJobWindow::windowIndex)
                ?.let { firstWindowIndex ->
                    // Window identities depend on the glossary emitted by the
                    // preceding window. Delete the complete untrusted suffix
                    // before journalling its PENDING state so recovery remains
                    // crash-safe and cannot collide with stale checkpoints.
                    store.discardWindowSuffix(candidate.job, firstWindowIndex)
                }
            store.writeJob(recovered)
            return recovered
        }
        val now = clock.millis()
        val runKey = idSource.nextId()
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
                    windowArtifactKey = TranslationArtifactIdentity.windowArtifactKey(runKey, window.windowIndex),
                    contextTranslationRegionIds = window.contextItems.map { it.input.translationRegionId },
                    translationRegionIds = window.items.map { it.input.translationRegionId },
                )
            },
            pages = inputs.map { input ->
                TranslationJobPage(
                    pageId = input.pageId,
                    pageOrder = input.pageOrder,
                    ocrPageArtifactKey = input.ocrPageArtifactKey,
                    pageArtifactKey = TranslationArtifactIdentity.pageArtifactKey(runKey, input.pageOrder),
                    translationRegionIds = input.items.map { it.translationRegionId },
                    protectedOcrRegionCount = input.protectedRegions.size,
                )
            },
        ).also(store::writeJob)
    }

    private data class TranslationRecoverySelection(
        val job: TranslationJobRecord,
        val legacySource: TranslationJobRecord? = null,
    )

    private fun readPublishedResult(
        store: TranslationArtifactStore,
        project: ProjectRef,
        runKey: String,
        dependencies: TranslationDependencies,
    ): TranslationRunResult? {
        val artifact = store.readPublishedRun(runKey) ?: return null
        val report = store.readPublishedReport(runKey) ?: return null
        require(artifact.projectId == project.manifest.projectId && artifact.dependencies == dependencies)
        var job = store.readJob(report.jobId) ?: return null
        if (!job.status.isSuccessful()) {
            job = runCatching {
                TranslationJobReducer.finishSuccess(job, report.finishedAtEpochMillis).also(store::writeJob)
            }.getOrNull() ?: return null
        }
        require(job.status == report.status)
        runCatching {
            store.readPublishedGlossary(runKey)?.entries?.let { glossaryMemory?.record(it) }
        }
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

    private fun normalizeAndValidateItems(
        window: TranslationBatchWindow,
        items: List<ValidatedTranslationItem>,
        glossary: List<TranslationGlossaryEntry>,
    ): List<ValidatedTranslationItem> = items.map { item ->
        val translated = item.translatedText ?: return@map item
        val source = window.items.single {
            it.input.translationRegionId == item.translationRegionId
        }.input.sourceText
        val normalized = TranslationTextNormalizer.normalize(
            sourceText = source,
            translatedText = translated,
            glossary = glossary,
        )
        val invalidReason = responseValidator.invalidOutputReason(source, normalized)
        if (invalidReason == null) {
            item.copy(translatedText = normalized)
        } else {
            item.copy(
                translatedText = null,
                state = TranslationResultState.PRESERVED_SOURCE,
                preserveReason = invalidReason,
            )
        }
    }

    private fun isGlossaryAcceptableItem(item: ValidatedTranslationItem): Boolean =
        item.state == TranslationResultState.TRANSLATED ||
            item.preserveReason == TranslationPreserveReason.POLICY_PRESERVED

    private fun Throwable.toFatalError(): TranslationError = when (this) {
        is TerminalProviderFailure -> error
        is FatalTranslationException -> TranslationError(code)
        is IllegalArgumentException -> TranslationError("PROJECT_INVALID")
        else -> TranslationError("TRANSLATION_RUN_FAILED")
    }

    private class TranslationCancellationSignal : RuntimeException()
    private class FatalTranslationException(val code: String) : RuntimeException(code)
    private fun failIfNonPublishable(items: List<ValidatedTranslationItem>) {
        val remaining = items.filter {
            it.state == TranslationResultState.PRESERVED_SOURCE &&
                it.preserveReason in NON_PUBLISHABLE_REASONS
        }
        if (remaining.isEmpty()) return
        val responseError = when {
            remaining.any { it.preserveReason == TranslationPreserveReason.BLANK_TRANSLATION } ->
                "BLANK_TRANSLATION_RESPONSE"
            remaining.any {
                it.preserveReason == TranslationPreserveReason.DUPLICATE_RESPONSE ||
                    it.preserveReason == TranslationPreserveReason.INVALID_ROLE
            } -> TranslationProviderErrorCode.MALFORMED_RESPONSE.name
            else -> "INCOMPLETE_TRANSLATION_RESPONSE"
        }
        throw TerminalProviderFailure(TranslationError(responseError))
    }

    private fun throwProviderFailure(
        failure: TranslationProviderException,
        cancellation: () -> Boolean,
    ): Nothing {
        if (failure.code == TranslationProviderErrorCode.CANCELLED || cancellation()) {
            throw TranslationCancellationSignal()
        }
        throw TerminalProviderFailure(TranslationError(failure.code.name, failure.httpStatus))
    }

    private class TerminalProviderFailure(val error: TranslationError) : RuntimeException(error.code)

    private companion object {
        const val EXPECTED_PROVIDER_CALLS = 2
        val NON_PUBLISHABLE_REASONS = setOf(
            TranslationPreserveReason.MISSING_RESPONSE,
            TranslationPreserveReason.DUPLICATE_RESPONSE,
            TranslationPreserveReason.INVALID_ROLE,
            TranslationPreserveReason.BLANK_TRANSLATION,
            TranslationPreserveReason.PROVIDER_FAILURE,
        )
        val INVALID_OUTPUT_REASONS = setOf(
            TranslationPreserveReason.INVALID_TARGET_SCRIPT,
            TranslationPreserveReason.SOURCE_TEXT_ECHO,
        )
    }
}
