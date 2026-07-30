package rs.masumi.app.pipeline

import rs.masumi.app.cleanup.CleanupProgress
import rs.masumi.app.detection.DetectionProgress
import rs.masumi.app.detection.ProjectRef
import rs.masumi.app.detection.PublishedCleanupRun
import rs.masumi.app.detection.PublishedDetectionRun
import rs.masumi.app.detection.PublishedOcrRun
import rs.masumi.app.detection.PublishedTranslationRun
import rs.masumi.app.detection.PublishedTypesettingRun
import rs.masumi.app.exporting.ExportProgress
import rs.masumi.app.ocr.OcrProgress
import rs.masumi.app.translation.TranslationProgress
import rs.masumi.app.typesetting.TypesettingProgress
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.detection.DetectionArtifactStore
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportPageSource
import rs.masumi.core.exporting.ExportPageState
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationDependencies
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationPageState
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationWindowState
import rs.masumi.core.translation.isTerminal
import rs.masumi.core.typesetting.TypesettingArtifactStore
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPageState
import rs.masumi.core.typesetting.TypesettingPolicy

/**
 * Reconstructs UI progress from durable pipeline jobs and published reports.
 *
 * Keeping artifact-store reads here leaves MainActivity responsible for
 * presentation and navigation instead of also knowing every stage's recovery
 * schema.
 */
internal object DurablePipelineProgress {
    fun detection(
        project: ProjectRef,
        published: PublishedDetectionRun?,
    ): DetectionProgress? {
        published?.report?.let { report ->
            return DetectionProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                committedPageCount = report.committedPageCount,
                preservedPageCount = report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                errorCode = report.error?.code,
            )
        }
        val job = DetectionArtifactStore(project.directory).findLatestJob() ?: return null
        if (job.projectId != project.manifest.projectId) return null
        return DetectionProgress(
            projectId = job.projectId,
            jobId = job.jobId,
            runArtifactKey = job.runArtifactKey,
            status = job.status,
            committedPageCount = job.pages.count { it.state == DetectionPageState.COMMITTED },
            preservedPageCount = job.pages.count { it.state == DetectionPageState.PRESERVED_SOURCE },
            totalPageCount = job.pages.size,
            currentOrder = job.pages.firstOrNull { it.state == DetectionPageState.RUNNING }?.order,
            errorCode = job.error?.code,
        )
    }

    fun ocrMatchesDetection(
        project: ProjectRef,
        progress: OcrProgress,
        detectionRunArtifactKey: String,
    ): Boolean {
        val job = OcrArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.detectionRunArtifactKey == detectionRunArtifactKey
    }

    fun ocr(
        project: ProjectRef,
        detectionRunArtifactKey: String,
        published: PublishedOcrRun?,
    ): OcrProgress? {
        published?.report?.let { report ->
            val terminalCount = report.recognizedRegionCount +
                report.needsFallbackRegionCount +
                report.noTextRegionCount +
                report.preservedRegionCount
            return OcrProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalRegionCount = terminalCount,
                totalRegionCount = report.totalRegionCount,
                committedPageCount = report.committedPageCount,
                totalPageCount = report.totalPageCount,
                errorCode = report.error?.code,
            )
        }
        val job = OcrArtifactStore(project.directory).findLatestJob() ?: return null
        if (
            job.projectId != project.manifest.projectId ||
            job.detectionRunArtifactKey != detectionRunArtifactKey
        ) {
            return null
        }
        return OcrProgress(
            projectId = job.projectId,
            jobId = job.jobId,
            runArtifactKey = job.runArtifactKey,
            status = job.status,
            terminalRegionCount = job.pages.sumOf { page ->
                page.regions.count { region -> region.state.isTerminalForProgress() }
            },
            totalRegionCount = job.pages.sumOf { it.regions.size },
            committedPageCount = job.pages.count { it.state == OcrPageState.COMMITTED },
            totalPageCount = job.pages.size,
            currentOrder = job.pages.firstOrNull { it.state == OcrPageState.RUNNING }?.order,
            currentPageId = job.pages.firstOrNull { it.state == OcrPageState.RUNNING }?.pageId,
            currentRegionId = job.pages.asSequence()
                .flatMap { it.regions.asSequence() }
                .firstOrNull { it.state == OcrRegionState.RUNNING }
                ?.ocrRegionId,
            errorCode = job.error?.code,
        )
    }

    fun translationMatchesOcr(
        project: ProjectRef,
        progress: TranslationProgress,
        ocrRunArtifactKey: String,
    ): Boolean {
        val job = TranslationArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            translationDependenciesAreCurrent(job.dependencies, ocrRunArtifactKey)
    }

    fun translation(
        project: ProjectRef,
        ocrRunArtifactKey: String,
        published: PublishedTranslationRun?,
    ): TranslationProgress? {
        val job = TranslationArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            translationDependenciesAreCurrent(job.dependencies, ocrRunArtifactKey) &&
            (
                job.status == TranslationJobStatus.QUEUED ||
                    job.status == TranslationJobStatus.RUNNING ||
                    published == null ||
                    job.updatedAtEpochMillis >= published.report.finishedAtEpochMillis
                )
        ) {
            return TranslationProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalWindowCount = job.windows.count { it.state.isTerminal() },
                totalWindowCount = job.windows.size,
                committedPageCount = job.pages.count { it.state == TranslationPageState.COMMITTED },
                totalPageCount = job.pages.size,
                translatedItemCount = job.windows.sumOf { it.translatedItemCount },
                preservedItemCount = job.windows.sumOf { it.preservedItemCount },
                protectedOcrCount = job.pages.sumOf { it.protectedOcrRegionCount },
                currentWindowIndex = job.windows
                    .firstOrNull { it.state == TranslationWindowState.RUNNING }
                    ?.windowIndex,
                errorCode = job.error?.code,
            )
        }
        return published?.report?.let { report ->
            TranslationProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalWindowCount = report.committedWindowCount,
                totalWindowCount = report.totalWindowCount,
                committedPageCount = report.committedPageCount,
                totalPageCount = report.totalPageCount,
                translatedItemCount = report.translatedItemCount,
                preservedItemCount = report.preservedItemCount,
                protectedOcrCount = report.protectedOcrRegionCount,
                errorCode = report.error?.code,
            )
        }
    }

    fun cleanupMatchesTranslation(
        project: ProjectRef,
        progress: CleanupProgress,
        translationRunArtifactKey: String,
    ): Boolean {
        val job = CleanupArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.translationRunArtifactKey == translationRunArtifactKey &&
            job.dependencies.policy == CleanupPolicy() &&
            job.dependencies.maskModel == PinnedComicTextSegmenter.descriptor.toModelRef()
    }

    fun cleanup(
        project: ProjectRef,
        translationRunArtifactKey: String,
        published: PublishedCleanupRun?,
    ): CleanupProgress? {
        val job = CleanupArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.translationRunArtifactKey == translationRunArtifactKey &&
            job.dependencies.policy == CleanupPolicy() &&
            job.dependencies.maskModel == PinnedComicTextSegmenter.descriptor.toModelRef() &&
            (
                job.status == CleanupJobStatus.QUEUED ||
                    job.status == CleanupJobStatus.RUNNING ||
                    published == null ||
                    job.updatedAtEpochMillis >= published.report.finishedAtEpochMillis
                )
        ) {
            return CleanupProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalPageCount = job.pages.count {
                    it.state == CleanupPageState.COMMITTED || it.state == CleanupPageState.PRESERVED_SOURCE
                },
                totalPageCount = job.pages.size,
                cleanedRegionCount = job.pages.sumOf { it.cleanedRegionCount },
                preservedRegionCount = job.pages.sumOf { it.preservedRegionCount },
                currentPageOrder = job.pages.firstOrNull { it.state == CleanupPageState.RUNNING }?.pageOrder,
                errorCode = job.error?.code,
            )
        }
        return published?.report?.let { report ->
            CleanupProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalPageCount = report.committedPageCount + report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                cleanedRegionCount = report.cleanedRegionCount,
                preservedRegionCount = report.preservedRegionCount,
                errorCode = report.error?.code,
            )
        }
    }

    fun typesettingMatchesCleanup(
        project: ProjectRef,
        progress: TypesettingProgress,
        cleanupRunArtifactKey: String,
    ): Boolean {
        val job = TypesettingArtifactStore(project.directory).readJob(progress.jobId) ?: return false
        return job.runArtifactKey == progress.runArtifactKey &&
            job.dependencies.cleanupRunArtifactKey == cleanupRunArtifactKey &&
            job.dependencies.policy == TypesettingPolicy()
    }

    fun typesetting(
        project: ProjectRef,
        cleanupRunArtifactKey: String,
        published: PublishedTypesettingRun?,
    ): TypesettingProgress? {
        val job = TypesettingArtifactStore(project.directory).findResumableJob()
        if (
            job != null &&
            job.projectId == project.manifest.projectId &&
            job.dependencies.cleanupRunArtifactKey == cleanupRunArtifactKey &&
            job.dependencies.policy == TypesettingPolicy() &&
            (
                job.status == TypesettingJobStatus.QUEUED ||
                    job.status == TypesettingJobStatus.RUNNING ||
                    published == null ||
                    job.updatedAtEpochMillis >= published.report.finishedAtEpochMillis
                )
        ) {
            return TypesettingProgress(
                projectId = job.projectId,
                jobId = job.jobId,
                runArtifactKey = job.runArtifactKey,
                status = job.status,
                terminalPageCount = job.pages.count {
                    it.state == TypesettingPageState.COMMITTED ||
                        it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE
                },
                totalPageCount = job.pages.size,
                typesetRegionCount = job.pages.sumOf { it.typesetRegionCount },
                preservedRegionCount = job.pages.sumOf { it.preservedRegionCount },
                currentPageOrder = job.pages.firstOrNull { it.state == TypesettingPageState.RUNNING }?.pageOrder,
                errorCode = job.error?.code,
            )
        }
        return published?.report?.let { report ->
            TypesettingProgress(
                projectId = report.projectId,
                jobId = report.jobId,
                runArtifactKey = report.runArtifactKey,
                status = report.status,
                terminalPageCount = report.committedPageCount + report.preservedPageCount,
                totalPageCount = report.totalPageCount,
                typesetRegionCount = report.typesetRegionCount,
                preservedRegionCount = report.preservedRegionCount,
                errorCode = report.error?.code,
            )
        }
    }

    fun export(
        project: ProjectRef,
        typesettingRunArtifactKey: String,
        progressOverride: ExportProgress?,
    ): ExportProgress? {
        val job = ExportArtifactStore(project.directory).findLatestJob()?.takeIf {
            it.projectId == project.manifest.projectId &&
                it.dependencies.typesettingRunArtifactKey == typesettingRunArtifactKey
        }
        return progressOverride
            ?.takeIf { progress ->
                progress.projectId == project.manifest.projectId &&
                    job?.jobId == progress.jobId &&
                    job.exportKey == progress.exportKey
            }
            ?: job?.let { exportJob ->
                val committed = exportJob.pages.filter { it.state == ExportPageState.COMMITTED }
                ExportProgress(
                    projectId = exportJob.projectId,
                    jobId = exportJob.jobId,
                    exportKey = exportJob.exportKey,
                    status = exportJob.status,
                    terminalPageCount = committed.size,
                    totalPageCount = exportJob.pages.size,
                    flattenedPageCount = committed.count { it.source == ExportPageSource.FLATTENED },
                    cleanedFallbackPageCount = committed.count {
                        it.source == ExportPageSource.CLEANED_FALLBACK
                    },
                    sourceFallbackPageCount = committed.count { it.source == ExportPageSource.SOURCE_FALLBACK },
                    reusedPageCount = committed.count { it.reusedExisting },
                    currentPageOrder = exportJob.pages
                        .firstOrNull { it.state == ExportPageState.RUNNING }
                        ?.pageOrder,
                    errorCode = exportJob.error?.code,
                )
            }
    }

    private fun translationDependenciesAreCurrent(
        dependencies: TranslationDependencies,
        ocrRunArtifactKey: String,
    ): Boolean =
        dependencies.ocrRunArtifactKey == ocrRunArtifactKey &&
            dependencies.policy == TranslationPolicy() &&
            dependencies.prompt == TranslationPromptRef() &&
            dependencies.batching == TranslationBatchingConfig()

    private fun OcrRegionState.isTerminalForProgress(): Boolean = when (this) {
        OcrRegionState.PENDING,
        OcrRegionState.RUNNING,
        -> false
        OcrRegionState.RECOGNIZED,
        OcrRegionState.NEEDS_FALLBACK,
        OcrRegionState.NO_TEXT_CONFIRMED,
        OcrRegionState.PRESERVED_SOURCE,
        -> true
    }
}
