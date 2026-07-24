package rs.masumi.core.quality

object QualityJobReducer {
    fun start(job: QualityJobRecord, now: Long): QualityJobRecord {
        require(job.status == QualityJobStatus.QUEUED || job.status == QualityJobStatus.CANCELLED)
        return job.updated(now).copy(status = QualityJobStatus.RUNNING, cancelRequested = false, error = null)
    }

    fun startPage(job: QualityJobRecord, pageOrder: Int, now: Long): QualityJobRecord {
        require(job.status == QualityJobStatus.RUNNING && !job.cancelRequested)
        require(job.pages.none { it.state == QualityPageState.RUNNING })
        require(job.pages.single { it.pageOrder == pageOrder }.state == QualityPageState.PENDING)
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = QualityPageState.RUNNING,
                    attemptCount = it.attemptCount + 1,
                    artifactPath = null,
                    verdict = null,
                    warningCount = 0,
                    blockingCount = 0,
                    error = null,
                ) else it
            },
        )
    }

    fun commitPage(
        job: QualityJobRecord,
        pageOrder: Int,
        artifactPath: String,
        verdict: QualityPageVerdict,
        warningCount: Int,
        blockingCount: Int,
        now: Long,
    ): QualityJobRecord {
        require(job.pages.single { it.pageOrder == pageOrder }.state == QualityPageState.RUNNING)
        require(warningCount >= 0 && blockingCount >= 0)
        require((verdict == QualityPageVerdict.BLOCKED) == (blockingCount > 0))
        require((verdict == QualityPageVerdict.PASS_WITH_WARNINGS) == (blockingCount == 0 && warningCount > 0))
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = QualityPageState.COMMITTED,
                    artifactPath = artifactPath,
                    verdict = verdict,
                    warningCount = warningCount,
                    blockingCount = blockingCount,
                ) else it
            },
        )
    }

    fun requestCancellation(job: QualityJobRecord, now: Long): QualityJobRecord {
        require(job.status == QualityJobStatus.RUNNING)
        return job.updated(now).copy(cancelRequested = true)
    }

    fun finishCancellation(job: QualityJobRecord, now: Long): QualityJobRecord {
        require(job.cancelRequested)
        return job.updated(now).copy(
            status = QualityJobStatus.CANCELLED,
            pages = job.pages.map {
                if (it.state == QualityPageState.RUNNING) it.copy(state = QualityPageState.PENDING) else it
            },
        )
    }

    fun recoverInterrupted(job: QualityJobRecord, now: Long): QualityJobRecord {
        require(job.status == QualityJobStatus.RUNNING || job.status == QualityJobStatus.CANCELLED)
        return job.updated(now).copy(
            status = QualityJobStatus.QUEUED,
            cancelRequested = false,
            pages = job.pages.map {
                if (it.state == QualityPageState.RUNNING) it.copy(
                    state = QualityPageState.PENDING,
                    artifactPath = null,
                    verdict = null,
                    warningCount = 0,
                    blockingCount = 0,
                    error = null,
                ) else it
            },
            error = null,
        )
    }

    fun finish(job: QualityJobRecord, now: Long): QualityJobRecord {
        require(job.status == QualityJobStatus.RUNNING)
        require(job.pages.all { it.state == QualityPageState.COMMITTED && it.verdict != null })
        val status = when {
            job.pages.any { it.verdict == QualityPageVerdict.BLOCKED } -> QualityJobStatus.BLOCKED
            job.pages.any { it.verdict == QualityPageVerdict.PASS_WITH_WARNINGS } ->
                QualityJobStatus.SUCCEEDED_WITH_WARNINGS
            else -> QualityJobStatus.SUCCEEDED
        }
        return job.updated(now).copy(status = status, cancelRequested = false)
    }

    fun fail(job: QualityJobRecord, error: QualityError, now: Long): QualityJobRecord =
        job.updated(now).copy(status = QualityJobStatus.FAILED, error = error)

    private fun QualityJobRecord.updated(now: Long): QualityJobRecord {
        require(now >= updatedAtEpochMillis)
        return copy(updatedAtEpochMillis = now)
    }
}
