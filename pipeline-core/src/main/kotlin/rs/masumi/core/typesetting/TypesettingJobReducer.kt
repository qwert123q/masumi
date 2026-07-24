package rs.masumi.core.typesetting

object TypesettingJobReducer {
    fun start(job: TypesettingJobRecord, now: Long): TypesettingJobRecord {
        require(job.status == TypesettingJobStatus.QUEUED || job.status == TypesettingJobStatus.CANCELLED)
        return job.updated(now).copy(status = TypesettingJobStatus.RUNNING, cancelRequested = false, error = null)
    }

    fun startPage(job: TypesettingJobRecord, pageOrder: Int, now: Long): TypesettingJobRecord {
        require(job.status == TypesettingJobStatus.RUNNING && !job.cancelRequested)
        require(job.pages.none { it.state == TypesettingPageState.RUNNING })
        require(job.pages.single { it.pageOrder == pageOrder }.state == TypesettingPageState.PENDING)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = TypesettingPageState.RUNNING,
                    attemptCount = it.attemptCount + 1,
                    artifactPath = null,
                    imagePath = null,
                    error = null,
                ) else it
            },
        )
    }

    fun commitPage(
        job: TypesettingJobRecord,
        pageOrder: Int,
        artifactPath: String,
        imagePath: String,
        typesetRegionCount: Int,
        preservedRegionCount: Int,
        now: Long,
    ): TypesettingJobRecord {
        require(job.pages.single { it.pageOrder == pageOrder }.state == TypesettingPageState.RUNNING)
        require(typesetRegionCount >= 0 && preservedRegionCount >= 0)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = TypesettingPageState.COMMITTED,
                    artifactPath = artifactPath,
                    imagePath = imagePath,
                    typesetRegionCount = typesetRegionCount,
                    preservedRegionCount = preservedRegionCount,
                ) else it
            },
        )
    }

    fun preservePage(
        job: TypesettingJobRecord,
        pageOrder: Int,
        error: TypesettingError,
        now: Long,
    ): TypesettingJobRecord {
        require(job.pages.single { it.pageOrder == pageOrder }.state == TypesettingPageState.RUNNING)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = TypesettingPageState.PRESERVED_CLEANED_PAGE,
                    error = error,
                ) else it
            },
        )
    }

    fun requestCancellation(job: TypesettingJobRecord, now: Long): TypesettingJobRecord {
        require(job.status == TypesettingJobStatus.RUNNING)
        return job.updated(now).copy(cancelRequested = true)
    }

    fun finishCancellation(job: TypesettingJobRecord, now: Long): TypesettingJobRecord {
        require(job.cancelRequested)
        return job.updated(now).copy(
            status = TypesettingJobStatus.CANCELLED,
            pages = job.pages.map {
                if (it.state == TypesettingPageState.RUNNING) it.copy(state = TypesettingPageState.PENDING) else it
            },
        )
    }

    fun recoverInterrupted(job: TypesettingJobRecord, now: Long): TypesettingJobRecord {
        require(job.status == TypesettingJobStatus.RUNNING || job.status == TypesettingJobStatus.CANCELLED)
        return job.updated(now).copy(
            status = TypesettingJobStatus.QUEUED,
            cancelRequested = false,
            pages = job.pages.map {
                if (it.state == TypesettingPageState.RUNNING) it.copy(
                    state = TypesettingPageState.PENDING,
                    artifactPath = null,
                    imagePath = null,
                    error = null,
                ) else it
            },
            error = null,
        )
    }

    fun finishSuccess(job: TypesettingJobRecord, now: Long): TypesettingJobRecord {
        require(job.status == TypesettingJobStatus.RUNNING)
        require(job.pages.all {
            it.state == TypesettingPageState.COMMITTED || it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE
        })
        val preserved = job.pages.any {
            it.state == TypesettingPageState.PRESERVED_CLEANED_PAGE || it.preservedRegionCount > 0
        }
        return job.updated(now).copy(
            status = if (preserved) {
                TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS
            } else {
                TypesettingJobStatus.SUCCEEDED
            },
            cancelRequested = false,
        )
    }

    fun fail(job: TypesettingJobRecord, error: TypesettingError, now: Long): TypesettingJobRecord =
        job.updated(now).copy(status = TypesettingJobStatus.FAILED, error = error)

    private fun TypesettingJobRecord.updated(now: Long): TypesettingJobRecord {
        require(now >= updatedAtEpochMillis)
        return copy(updatedAtEpochMillis = now)
    }
}
