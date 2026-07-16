package rs.masumi.core.translation

object TranslationJobReducer {
    fun prepareWindow(
        job: TranslationJobRecord,
        index: Int,
        windowArtifactKey: String,
        now: Long,
    ): TranslationJobRecord {
        require(job.status == TranslationJobStatus.RUNNING && !job.cancelRequested)
        val target = job.windows.single { it.windowIndex == index }
        require(target.state == TranslationWindowState.PENDING)
        require(windowArtifactKey.matches(Regex("[0-9a-f]{64}")))
        return job.updated(now).copy(
            windows = job.windows.map {
                if (it.windowIndex == index) it.copy(windowArtifactKey = windowArtifactKey) else it
            },
        )
    }

    fun startRunning(job: TranslationJobRecord, now: Long): TranslationJobRecord {
        require(job.status == TranslationJobStatus.QUEUED || job.status == TranslationJobStatus.CANCELLED)
        return job.updated(now).copy(status = TranslationJobStatus.RUNNING, cancelRequested = false, error = null)
    }

    fun startWindow(job: TranslationJobRecord, index: Int, now: Long): TranslationJobRecord {
        require(job.status == TranslationJobStatus.RUNNING && !job.cancelRequested)
        require(job.windows.none { it.state == TranslationWindowState.RUNNING })
        val target = job.windows.single { it.windowIndex == index }
        require(target.state == TranslationWindowState.PENDING)
        val pageIds = job.pages.filter { page -> page.translationRegionIds.any(target.translationRegionIds::contains) }
            .map(TranslationJobPage::pageId).toSet()
        return job.updated(now).copy(
            windows = job.windows.map {
                if (it.windowIndex == index) it.copy(
                    state = TranslationWindowState.RUNNING,
                    attemptCount = it.attemptCount + 1,
                    checkpointPath = null,
                    error = null,
                ) else it
            },
            pages = job.pages.map {
                if (it.pageId in pageIds && it.state == TranslationPageState.PENDING) {
                    it.copy(state = TranslationPageState.RUNNING)
                } else it
            },
        )
    }

    fun commitWindow(
        job: TranslationJobRecord,
        index: Int,
        checkpointPath: String,
        translatedCount: Int,
        preservedCount: Int,
        usage: TranslationUsage?,
        error: TranslationError?,
        now: Long,
    ): TranslationJobRecord {
        val target = job.windows.single { it.windowIndex == index }
        require(target.state == TranslationWindowState.RUNNING)
        require(translatedCount >= 0 && preservedCount >= 0)
        require(translatedCount + preservedCount == target.translationRegionIds.size)
        return job.updated(now).copy(
            windows = job.windows.map {
                if (it.windowIndex == index) it.copy(
                    state = if (preservedCount == target.translationRegionIds.size && error != null) {
                        TranslationWindowState.PRESERVED_SOURCE
                    } else {
                        TranslationWindowState.COMMITTED
                    },
                    checkpointPath = checkpointPath,
                    translatedItemCount = translatedCount,
                    preservedItemCount = preservedCount,
                    usage = usage,
                    error = error,
                ) else it
            },
        )
    }

    fun commitPage(job: TranslationJobRecord, pageOrder: Int, artifactPath: String, now: Long): TranslationJobRecord {
        val page = job.pages.single { it.pageOrder == pageOrder }
        require(page.state == TranslationPageState.PENDING || page.state == TranslationPageState.RUNNING)
        val relevant = job.windows.filter { window -> window.translationRegionIds.any(page.translationRegionIds::contains) }
        require(relevant.all { it.state.isTerminal() })
        return job.updated(now).copy(
            pages = job.pages.map {
                if (it.pageOrder == pageOrder) it.copy(
                    state = TranslationPageState.COMMITTED,
                    artifactPath = artifactPath,
                    error = null,
                ) else it
            },
        )
    }

    fun requestCancellation(job: TranslationJobRecord, now: Long): TranslationJobRecord {
        require(job.status == TranslationJobStatus.RUNNING)
        return job.updated(now).copy(cancelRequested = true)
    }

    fun finishCancellation(job: TranslationJobRecord, now: Long): TranslationJobRecord {
        require(job.cancelRequested)
        return job.updated(now).copy(
            status = TranslationJobStatus.CANCELLED,
            windows = job.windows.map {
                if (it.state == TranslationWindowState.RUNNING) it.copy(
                    state = TranslationWindowState.PENDING,
                    checkpointPath = null,
                    error = null,
                ) else it
            },
        )
    }

    fun recoverInterrupted(job: TranslationJobRecord, now: Long): TranslationJobRecord {
        require(job.status == TranslationJobStatus.RUNNING || job.status == TranslationJobStatus.CANCELLED)
        return job.updated(now).copy(
            status = TranslationJobStatus.QUEUED,
            cancelRequested = false,
            windows = job.windows.map {
                if (it.state == TranslationWindowState.RUNNING) it.copy(
                    state = TranslationWindowState.PENDING,
                    checkpointPath = null,
                    error = null,
                ) else it
            },
            error = null,
        )
    }

    fun finishSuccess(job: TranslationJobRecord, now: Long): TranslationJobRecord {
        require(job.status == TranslationJobStatus.RUNNING)
        require(job.windows.all { it.state.isTerminal() })
        require(job.pages.all { it.state == TranslationPageState.COMMITTED })
        val protected = job.windows.any { it.preservedItemCount > 0 } || job.pages.any { it.protectedOcrRegionCount > 0 }
        return job.updated(now).copy(
            status = if (protected) {
                TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS
            } else {
                TranslationJobStatus.SUCCEEDED
            },
            cancelRequested = false,
        )
    }

    fun fail(job: TranslationJobRecord, error: TranslationError, now: Long): TranslationJobRecord =
        job.updated(now).copy(status = TranslationJobStatus.FAILED, error = error)

    private fun TranslationJobRecord.updated(now: Long): TranslationJobRecord {
        require(now >= updatedAtEpochMillis)
        return copy(updatedAtEpochMillis = now)
    }
}
