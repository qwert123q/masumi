package rs.masumi.core.ocr

object OcrJobReducer {
    fun startModelDownload(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status == OcrJobStatus.QUEUED) { "job must be queued" }
        return job.updated(nowEpochMillis).copy(
            status = OcrJobStatus.DOWNLOADING_MODEL,
            error = null,
        )
    }

    fun startModelLoad(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status == OcrJobStatus.DOWNLOADING_MODEL) { "model download must start first" }
        return job.updated(nowEpochMillis).copy(
            status = OcrJobStatus.LOADING_MODEL,
            error = null,
        )
    }

    fun startRunning(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status == OcrJobStatus.LOADING_MODEL) { "model load must start first" }
        return job.updated(nowEpochMillis).copy(
            status = OcrJobStatus.RUNNING,
            error = null,
        )
    }

    fun startRegion(
        job: OcrJobRecord,
        pageId: String,
        ocrRegionId: String,
        nowEpochMillis: Long,
    ): OcrJobRecord {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        require(!job.cancelRequested) { "cancelled job cannot start another region" }
        val pages = requirePages(job, pageId)
        require(pages.all { it.state == OcrPageState.PENDING || it.state == OcrPageState.RUNNING }) {
            "page must be pending or running"
        }
        require(pages.all { page -> page.regions.none { it.state == OcrRegionState.RUNNING } }) {
            "another region is already running"
        }
        require(pages.all { page -> page.regions.any { it.ocrRegionId == ocrRegionId } }) {
            "region does not belong to every duplicate page entry"
        }
        require(pages.all { page -> page.regions.single { it.ocrRegionId == ocrRegionId }.state == OcrRegionState.PENDING }) {
            "region must be pending"
        }
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = OcrPageState.RUNNING,
                regions = page.regions.map { region ->
                    if (region.ocrRegionId == ocrRegionId) {
                        region.copy(
                            state = OcrRegionState.RUNNING,
                            attemptCount = region.attemptCount + 1,
                            checkpointPath = null,
                            error = null,
                        )
                    } else {
                        region
                    }
                },
                artifactPath = null,
                previewPath = null,
                error = null,
            )
        }
    }

    fun commitTerminalRegion(
        job: OcrJobRecord,
        pageId: String,
        ocrRegionId: String,
        state: OcrRegionState,
        checkpointPath: String,
        error: OcrError?,
        nowEpochMillis: Long,
    ): OcrJobRecord {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        require(state.isTerminal()) { "region state must be terminal" }
        requireSafeRelativePath(checkpointPath)
        val pages = requirePages(job, pageId)
        require(pages.all { it.state == OcrPageState.RUNNING }) { "page must be running" }
        require(pages.all { page ->
            page.regions.singleOrNull { it.ocrRegionId == ocrRegionId }?.state == OcrRegionState.RUNNING
        }) { "region must be running" }
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                regions = page.regions.map { region ->
                    if (region.ocrRegionId == ocrRegionId) {
                        region.copy(
                            state = state,
                            checkpointPath = checkpointPath,
                            error = error,
                        )
                    } else {
                        region
                    }
                },
            )
        }
    }

    fun commitPage(
        job: OcrJobRecord,
        pageId: String,
        artifactPath: String,
        previewPaths: Map<Int, String>,
        nowEpochMillis: Long,
    ): OcrJobRecord {
        val pages = requirePages(job, pageId)
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        require(pages.all { it.state == OcrPageState.RUNNING }) { "page must be running" }
        require(pages.all { page -> page.regions.all { it.state.isTerminal() } }) {
            "every region must be terminal"
        }
        requireSafeRelativePath(artifactPath)
        require(previewPaths.keys == pages.map(OcrJobPage::order).toSet()) {
            "every ordered page entry must have one preview"
        }
        previewPaths.values.forEach(::requireSafeRelativePath)
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = OcrPageState.COMMITTED,
                artifactPath = artifactPath,
                previewPath = previewPaths.getValue(page.order),
                error = null,
            )
        }
    }

    fun preservePage(
        job: OcrJobRecord,
        pageId: String,
        error: OcrError,
        nowEpochMillis: Long,
    ): OcrJobRecord {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        val pages = requirePages(job, pageId)
        require(pages.all { it.state == OcrPageState.PENDING || it.state == OcrPageState.RUNNING }) {
            "page must be incomplete"
        }
        require(pages.all { page -> page.regions.none { it.state == OcrRegionState.RUNNING } }) {
            "active region must stop before preserving page"
        }
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = OcrPageState.PRESERVED_SOURCE,
                artifactPath = null,
                previewPath = null,
                error = error,
            )
        }
    }

    fun requestCancel(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status.isNonTerminal()) { "terminal job cannot request cancellation" }
        return job.updated(nowEpochMillis).copy(cancelRequested = true)
    }

    fun finishCancellation(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.cancelRequested) { "cancellation must be requested" }
        require(job.status.isNonTerminal()) { "job must be non-terminal" }
        return resetActiveRegions(job, nowEpochMillis).copy(status = OcrJobStatus.CANCELLED)
    }

    fun recoverInterrupted(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status.isNonTerminal()) { "only interrupted non-terminal jobs recover" }
        return resetActiveRegions(job, nowEpochMillis).copy(status = OcrJobStatus.QUEUED)
    }

    fun resumeCancelled(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status == OcrJobStatus.CANCELLED) { "job must be cancelled" }
        require(job.pages.none { page -> page.regions.any { it.state == OcrRegionState.RUNNING } }) {
            "cancelled job cannot contain a running region"
        }
        return job.updated(nowEpochMillis).copy(
            status = OcrJobStatus.QUEUED,
            cancelRequested = false,
            error = null,
        )
    }

    fun finishSuccess(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord {
        require(job.status == OcrJobStatus.RUNNING) { "job must be running" }
        require(!job.cancelRequested) { "cancelled job cannot finish successfully" }
        require(job.pages.all { it.state == OcrPageState.COMMITTED || it.state == OcrPageState.PRESERVED_SOURCE }) {
            "every page must be terminal"
        }
        val hasPreservedRegion = job.pages.any { page ->
            page.state == OcrPageState.PRESERVED_SOURCE || page.regions.any { region ->
                region.state == OcrRegionState.NEEDS_FALLBACK ||
                    region.state == OcrRegionState.PRESERVED_SOURCE
            }
        }
        return job.updated(nowEpochMillis).copy(
            status = if (hasPreservedRegion) {
                OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS
            } else {
                OcrJobStatus.SUCCEEDED
            },
            error = null,
        )
    }

    fun failJob(job: OcrJobRecord, error: OcrError, nowEpochMillis: Long): OcrJobRecord {
        require(job.status.isNonTerminal()) { "terminal job cannot fail again" }
        return job.updated(nowEpochMillis).copy(status = OcrJobStatus.FAILED, error = error)
    }

    private fun resetActiveRegions(job: OcrJobRecord, nowEpochMillis: Long): OcrJobRecord =
        job.updated(nowEpochMillis).copy(
            pages = job.pages.map { page ->
                val hadRunningRegion = page.regions.any { it.state == OcrRegionState.RUNNING }
                page.copy(
                    state = if (hadRunningRegion || page.state == OcrPageState.RUNNING) {
                        OcrPageState.PENDING
                    } else {
                        page.state
                    },
                    regions = page.regions.map { region ->
                        if (region.state == OcrRegionState.RUNNING) {
                            region.copy(
                                state = OcrRegionState.PENDING,
                                checkpointPath = null,
                                error = null,
                            )
                        } else {
                            region
                        }
                    },
                    artifactPath = if (page.state == OcrPageState.RUNNING) null else page.artifactPath,
                    previewPath = if (page.state == OcrPageState.RUNNING) null else page.previewPath,
                )
            },
        )

    private fun requirePages(job: OcrJobRecord, pageId: String): List<OcrJobPage> =
        job.pages.filter { it.pageId == pageId }.also { pages ->
            require(pages.isNotEmpty()) { "page does not belong to job" }
        }

    private fun OcrJobRecord.withPages(
        pageId: String,
        nowEpochMillis: Long,
        transform: (OcrJobPage) -> OcrJobPage,
    ): OcrJobRecord = updated(nowEpochMillis).copy(
        pages = pages.map { page -> if (page.pageId == pageId) transform(page) else page },
    )

    private fun OcrJobRecord.updated(nowEpochMillis: Long): OcrJobRecord =
        copy(updatedAtEpochMillis = nowEpochMillis)

    private fun OcrJobStatus.isNonTerminal(): Boolean = when (this) {
        OcrJobStatus.QUEUED,
        OcrJobStatus.DOWNLOADING_MODEL,
        OcrJobStatus.LOADING_MODEL,
        OcrJobStatus.RUNNING,
        -> true
        OcrJobStatus.SUCCEEDED,
        OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
        OcrJobStatus.CANCELLED,
        OcrJobStatus.FAILED,
        -> false
    }

    private fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank()) { "artifact path must not be blank" }
        require(!path.startsWith('/')) { "artifact path must be relative" }
        require(path.split('/').none { it == ".." || it.isBlank() }) { "artifact path is unsafe" }
    }
}
