package rs.masumi.core.detection

object DetectionJobReducer {
    fun startModelDownload(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status == DetectionJobStatus.QUEUED) { "job must be queued" }
        return job.copy(
            status = DetectionJobStatus.DOWNLOADING_MODEL,
            updatedAtEpochMillis = nowEpochMillis,
            error = null,
        )
    }

    fun startRunning(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status == DetectionJobStatus.DOWNLOADING_MODEL) { "model download must start first" }
        return job.copy(
            status = DetectionJobStatus.RUNNING,
            updatedAtEpochMillis = nowEpochMillis,
            error = null,
        )
    }

    fun startPage(
        job: DetectionJobRecord,
        pageId: String,
        nowEpochMillis: Long,
    ): DetectionJobRecord {
        require(job.status == DetectionJobStatus.RUNNING) { "job must be running" }
        require(!job.cancelRequested) { "cancelled job cannot start another page" }
        val selected = job.pages.filter { it.pageId == pageId }
        require(selected.isNotEmpty()) { "page does not belong to job" }
        require(selected.all { it.state == DetectionPageState.PENDING }) { "page must be pending" }

        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = DetectionPageState.RUNNING,
                attemptCount = page.attemptCount + 1,
                regionsPath = null,
                previewPath = null,
                error = null,
            )
        }
    }

    fun recordRetry(
        job: DetectionJobRecord,
        pageId: String,
        error: DetectionError,
        nowEpochMillis: Long,
    ): DetectionJobRecord {
        val selected = requireRunningPage(job, pageId)
        require(selected.all { it.attemptCount == 1 }) { "only the first failure may retry" }
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(state = DetectionPageState.PENDING, error = error)
        }
    }

    fun commitPage(
        job: DetectionJobRecord,
        pageId: String,
        regionsPath: String,
        previewPaths: Map<Int, String>,
        nowEpochMillis: Long,
    ): DetectionJobRecord {
        val selected = requireRunningPage(job, pageId)
        requireSafeRelativePath(regionsPath)
        require(previewPaths.keys == selected.map { it.order }.toSet()) {
            "every ordered page entry must have one preview path"
        }
        previewPaths.values.forEach(::requireSafeRelativePath)

        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = DetectionPageState.COMMITTED,
                regionsPath = regionsPath,
                previewPath = previewPaths.getValue(page.order),
                error = null,
            )
        }
    }

    fun preservePage(
        job: DetectionJobRecord,
        pageId: String,
        error: DetectionError,
        nowEpochMillis: Long,
    ): DetectionJobRecord {
        val selected = requireRunningPage(job, pageId)
        require(selected.all { it.attemptCount >= 2 }) { "page must fail twice before preservation" }
        return job.withPages(pageId, nowEpochMillis) { page ->
            page.copy(
                state = DetectionPageState.PRESERVED_SOURCE,
                regionsPath = null,
                previewPath = null,
                error = error,
            )
        }
    }

    fun requestCancel(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status.isNonTerminal()) { "terminal job cannot request cancellation" }
        return job.copy(cancelRequested = true, updatedAtEpochMillis = nowEpochMillis)
    }

    fun finishCancellation(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.cancelRequested) { "cancellation must be requested" }
        require(job.status.isNonTerminal()) { "job must be non-terminal" }
        require(job.pages.none { it.state == DetectionPageState.RUNNING }) {
            "cancellation completes only at a page boundary"
        }
        return job.copy(
            status = DetectionJobStatus.CANCELLED,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    fun recoverInterrupted(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status.isNonTerminal()) { "only interrupted non-terminal jobs recover" }
        return job.copy(
            status = DetectionJobStatus.QUEUED,
            updatedAtEpochMillis = nowEpochMillis,
            pages = job.pages.map { page ->
                if (page.state == DetectionPageState.RUNNING) {
                    page.copy(
                        state = DetectionPageState.PENDING,
                        regionsPath = null,
                        previewPath = null,
                    )
                } else {
                    page
                }
            },
        )
    }

    fun resumeCancelled(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status == DetectionJobStatus.CANCELLED) { "job must be cancelled" }
        require(job.pages.none { it.state == DetectionPageState.RUNNING }) {
            "cancelled job must be at a page boundary"
        }
        return job.copy(
            status = DetectionJobStatus.QUEUED,
            updatedAtEpochMillis = nowEpochMillis,
            cancelRequested = false,
            error = null,
        )
    }

    fun finish(job: DetectionJobRecord, nowEpochMillis: Long): DetectionJobRecord {
        require(job.status == DetectionJobStatus.RUNNING) { "job must be running" }
        require(!job.cancelRequested) { "cancelled job cannot finish successfully" }
        require(job.pages.all { it.state.isCompleted() }) { "every page must be terminal" }
        val status = if (job.pages.any { it.state == DetectionPageState.PRESERVED_SOURCE }) {
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES
        } else {
            DetectionJobStatus.SUCCEEDED
        }
        return job.copy(status = status, updatedAtEpochMillis = nowEpochMillis, error = null)
    }

    fun failJob(
        job: DetectionJobRecord,
        error: DetectionError,
        nowEpochMillis: Long,
    ): DetectionJobRecord {
        require(job.status.isNonTerminal()) { "terminal job cannot fail again" }
        return job.copy(
            status = DetectionJobStatus.FAILED,
            updatedAtEpochMillis = nowEpochMillis,
            error = error,
        )
    }

    private fun requireRunningPage(
        job: DetectionJobRecord,
        pageId: String,
    ): List<DetectionJobPage> {
        require(job.status == DetectionJobStatus.RUNNING) { "job must be running" }
        val selected = job.pages.filter { it.pageId == pageId }
        require(selected.isNotEmpty()) { "page does not belong to job" }
        require(selected.all { it.state == DetectionPageState.RUNNING }) { "page must be running" }
        return selected
    }

    private fun DetectionJobRecord.withPages(
        pageId: String,
        nowEpochMillis: Long,
        transform: (DetectionJobPage) -> DetectionJobPage,
    ): DetectionJobRecord = copy(
        updatedAtEpochMillis = nowEpochMillis,
        pages = pages.map { page -> if (page.pageId == pageId) transform(page) else page },
    )

    private fun DetectionJobStatus.isNonTerminal(): Boolean = when (this) {
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

    private fun DetectionPageState.isCompleted(): Boolean =
        this == DetectionPageState.COMMITTED || this == DetectionPageState.PRESERVED_SOURCE

    private fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank()) { "artifact path must not be blank" }
        require(!path.startsWith('/')) { "artifact path must be relative" }
        require(path.split('/').none { it == ".." || it.isBlank() }) { "artifact path is unsafe" }
    }
}
