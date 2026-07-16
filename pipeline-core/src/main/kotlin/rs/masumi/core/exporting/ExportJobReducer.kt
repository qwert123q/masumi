package rs.masumi.core.exporting

object ExportJobReducer {
    fun start(job: ExportJobRecord, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.QUEUED)
        return job.updated(now).copy(status = ExportJobStatus.RUNNING, cancelRequested = false, error = null)
    }

    fun startPage(job: ExportJobRecord, pageOrder: Int, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.RUNNING && !job.cancelRequested)
        require(job.pages.none { it.state == ExportPageState.RUNNING })
        require(job.pages.single { it.pageOrder == pageOrder }.state == ExportPageState.PENDING)
        return job.updated(now).copy(
            pages = job.pages.map { page ->
                if (page.pageOrder == pageOrder) page.copy(
                    state = ExportPageState.RUNNING,
                    attemptCount = page.attemptCount + 1,
                    outputSha256 = null,
                    byteLength = 0L,
                    reusedExisting = false,
                    error = null,
                ) else page
            },
        )
    }

    fun commitPage(
        job: ExportJobRecord,
        pageOrder: Int,
        outputSha256: String,
        byteLength: Long,
        reusedExisting: Boolean,
        now: Long,
    ): ExportJobRecord {
        require(SHA256.matches(outputSha256) && byteLength > 0L)
        require(job.pages.single { it.pageOrder == pageOrder }.state == ExportPageState.RUNNING)
        return job.updated(now).copy(
            pages = job.pages.map { page ->
                if (page.pageOrder == pageOrder) page.copy(
                    state = ExportPageState.COMMITTED,
                    outputSha256 = outputSha256,
                    byteLength = byteLength,
                    reusedExisting = reusedExisting,
                    error = null,
                ) else page
            },
        )
    }

    fun requestCancellation(job: ExportJobRecord, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.RUNNING)
        return job.updated(now).copy(cancelRequested = true)
    }

    fun invalidateCommittedPage(job: ExportJobRecord, pageOrder: Int, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.RUNNING)
        require(job.pages.single { it.pageOrder == pageOrder }.state == ExportPageState.COMMITTED)
        return job.updated(now).copy(
            pages = job.pages.map { page ->
                if (page.pageOrder == pageOrder) page.copy(
                    state = ExportPageState.PENDING,
                    outputSha256 = null,
                    byteLength = 0L,
                    reusedExisting = false,
                    error = null,
                ) else page
            },
        )
    }

    fun finishCancellation(job: ExportJobRecord, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.RUNNING && job.cancelRequested)
        return job.updated(now).copy(
            status = ExportJobStatus.CANCELLED,
            cancelRequested = false,
            pages = job.pages.map { page ->
                if (page.state == ExportPageState.RUNNING) page.copy(
                    state = ExportPageState.PENDING,
                    outputSha256 = null,
                    byteLength = 0L,
                    reusedExisting = false,
                    error = null,
                ) else page
            },
        )
    }

    fun recover(job: ExportJobRecord, now: Long): ExportJobRecord {
        require(job.status in setOf(ExportJobStatus.RUNNING, ExportJobStatus.CANCELLED, ExportJobStatus.FAILED))
        return job.updated(now).copy(
            status = ExportJobStatus.QUEUED,
            cancelRequested = false,
            error = null,
            pages = job.pages.map { page ->
                if (page.state == ExportPageState.RUNNING) page.copy(
                    state = ExportPageState.PENDING,
                    outputSha256 = null,
                    byteLength = 0L,
                    reusedExisting = false,
                    error = null,
                ) else page
            },
        )
    }

    fun finishSuccess(job: ExportJobRecord, now: Long): ExportJobRecord {
        require(job.status == ExportJobStatus.RUNNING)
        require(job.pages.isNotEmpty() && job.pages.all { it.state == ExportPageState.COMMITTED })
        return job.updated(now).copy(status = ExportJobStatus.SUCCEEDED, cancelRequested = false)
    }

    fun fail(job: ExportJobRecord, error: ExportError, now: Long): ExportJobRecord =
        job.updated(now).copy(status = ExportJobStatus.FAILED, error = error)

    private fun ExportJobRecord.updated(now: Long): ExportJobRecord {
        require(now >= updatedAtEpochMillis)
        return copy(updatedAtEpochMillis = now)
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
