package rs.masumi.core.cleanup

object CleanupJobReducer {
    fun start(job: CleanupJobRecord, now: Long): CleanupJobRecord {
        require(job.status == CleanupJobStatus.QUEUED || job.status == CleanupJobStatus.CANCELLED)
        return job.updated(now).copy(status = CleanupJobStatus.RUNNING, cancelRequested = false, error = null)
    }

    fun startPage(job: CleanupJobRecord, pageOrder: Int, now: Long): CleanupJobRecord {
        require(job.status == CleanupJobStatus.RUNNING && !job.cancelRequested)
        require(job.pages.none { it.state == CleanupPageState.RUNNING })
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == CleanupPageState.PENDING)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = CleanupPageState.RUNNING,
                    attemptCount = it.attemptCount + 1,
                    artifactPath = null,
                    imagePath = null,
                    error = null,
                ) else it
            },
        )
    }

    fun commitPage(
        job: CleanupJobRecord,
        pageOrder: Int,
        artifactPath: String,
        imagePath: String,
        cleanedRegionCount: Int,
        preservedRegionCount: Int,
        now: Long,
    ): CleanupJobRecord {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == CleanupPageState.RUNNING)
        require(cleanedRegionCount >= 0 && preservedRegionCount >= 0)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = CleanupPageState.COMMITTED,
                    artifactPath = artifactPath,
                    imagePath = imagePath,
                    cleanedRegionCount = cleanedRegionCount,
                    preservedRegionCount = preservedRegionCount,
                ) else it
            },
        )
    }

    fun preservePage(job: CleanupJobRecord, pageOrder: Int, error: CleanupError, now: Long): CleanupJobRecord {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == CleanupPageState.RUNNING)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = CleanupPageState.PRESERVED_SOURCE,
                    error = error,
                ) else it
            },
        )
    }

    fun requestCancellation(job: CleanupJobRecord, now: Long): CleanupJobRecord {
        require(job.status == CleanupJobStatus.RUNNING)
        return job.updated(now).copy(cancelRequested = true)
    }

    fun finishCancellation(job: CleanupJobRecord, now: Long): CleanupJobRecord {
        require(job.cancelRequested)
        return job.updated(now).copy(
            status = CleanupJobStatus.CANCELLED,
            pages = job.pages.map {
                if (it.state == CleanupPageState.RUNNING) it.copy(state = CleanupPageState.PENDING) else it
            },
        )
    }

    fun recoverInterrupted(job: CleanupJobRecord, now: Long): CleanupJobRecord {
        require(job.status == CleanupJobStatus.RUNNING || job.status == CleanupJobStatus.CANCELLED)
        return job.updated(now).copy(
            status = CleanupJobStatus.QUEUED,
            cancelRequested = false,
            pages = job.pages.map {
                if (it.state == CleanupPageState.RUNNING) it.copy(
                    state = CleanupPageState.PENDING,
                    artifactPath = null,
                    imagePath = null,
                    error = null,
                ) else it
            },
            error = null,
        )
    }

    fun finishSuccess(job: CleanupJobRecord, now: Long): CleanupJobRecord {
        require(job.status == CleanupJobStatus.RUNNING)
        require(job.pages.all { it.state == CleanupPageState.COMMITTED || it.state == CleanupPageState.PRESERVED_SOURCE })
        val protected = job.pages.any {
            it.state == CleanupPageState.PRESERVED_SOURCE || it.preservedRegionCount > 0
        }
        return job.updated(now).copy(
            status = if (protected) {
                CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS
            } else {
                CleanupJobStatus.SUCCEEDED
            },
            cancelRequested = false,
        )
    }

    fun fail(job: CleanupJobRecord, error: CleanupError, now: Long): CleanupJobRecord =
        job.updated(now).copy(status = CleanupJobStatus.FAILED, error = error)

    private fun CleanupJobRecord.updated(now: Long): CleanupJobRecord {
        require(now >= updatedAtEpochMillis)
        return copy(updatedAtEpochMillis = now)
    }
}
