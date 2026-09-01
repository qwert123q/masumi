package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationJobReducerTest {
    @Test
    fun `pending window identity can follow the committed glossary chain`() {
        var job = TranslationJobReducer.startRunning(TranslationArtifactFixtures.job(), 2L)

        job = TranslationJobReducer.prepareWindow(job, 0, "e".repeat(64), 3L)

        assertEquals("e".repeat(64), job.windows.single().windowArtifactKey)
        assertEquals(TranslationWindowState.PENDING, job.windows.single().state)
    }

    @Test
    fun `recovery resets only active window and keeps committed work`() {
        val base = TranslationArtifactFixtures.job()
        val second = base.windows.single().copy(
            windowIndex = 1,
            windowArtifactKey = "d".repeat(64),
            state = TranslationWindowState.RUNNING,
            attemptCount = 1,
        )
        val interrupted = base.copy(
            status = TranslationJobStatus.RUNNING,
            windows = listOf(
                base.windows.single().copy(
                    state = TranslationWindowState.COMMITTED,
                    checkpointPath = "windows/first.json",
                    translatedItemCount = 1,
                ),
                second,
            ),
        )

        val recovered = TranslationJobReducer.recoverInterrupted(interrupted, 2L)

        assertEquals(TranslationJobStatus.QUEUED, recovered.status)
        assertEquals(TranslationWindowState.COMMITTED, recovered.windows[0].state)
        assertEquals("windows/first.json", recovered.windows[0].checkpointPath)
        assertEquals(TranslationWindowState.PENDING, recovered.windows[1].state)
        assertEquals(1, recovered.windows[1].attemptCount)
    }

    @Test
    fun `recovery rewinds legacy provider failure and its glossary dependent suffix`() {
        listOf(
            "NETWORK",
            "TIMEOUT",
            "HTTP_TRANSIENT",
            "HTTP_CLIENT",
            "MALFORMED_RESPONSE",
        ).forEachIndexed { caseIndex, code ->
            val base = TranslationArtifactFixtures.job()
            val prefixRegionId = "1".repeat(64)
            val failedRegionId = "2".repeat(64)
            val dependentRegionId = "3".repeat(64)
            val committedPrefix = base.windows.single().copy(
                translationRegionIds = listOf(prefixRegionId),
                state = TranslationWindowState.COMMITTED,
                checkpointPath = "windows/prefix.json",
                translatedItemCount = 1,
                attemptCount = 1,
            )
            val retryableFailure = base.windows.single().copy(
                windowIndex = 1,
                windowArtifactKey = "d".repeat(64),
                translationRegionIds = listOf(failedRegionId),
                state = TranslationWindowState.PRESERVED_SOURCE,
                checkpointPath = "windows/retryable.json",
                preservedItemCount = 1,
                attemptCount = 2,
                usage = TranslationUsage(10, 5, 15),
                error = TranslationError(code),
            )
            val dependentSuffix = base.windows.single().copy(
                windowIndex = 2,
                windowArtifactKey = "e".repeat(64),
                translationRegionIds = listOf(dependentRegionId),
                state = TranslationWindowState.COMMITTED,
                checkpointPath = "windows/dependent.json",
                translatedItemCount = 1,
                attemptCount = 1,
            )
            val unrelatedCommittedPage = base.pages.single().copy(
                translationRegionIds = listOf(prefixRegionId),
                state = TranslationPageState.COMMITTED,
                artifactPath = "pages/prefix/translation.json",
            )
            val failedWindowPage = base.pages.single().copy(
                pageId = "4".repeat(64),
                pageOrder = 1,
                pageArtifactKey = "5".repeat(64),
                translationRegionIds = listOf(failedRegionId),
                state = TranslationPageState.COMMITTED,
                artifactPath = "pages/failed/translation.json",
                error = TranslationError("STALE_PAGE_ERROR"),
            )
            val dependentSuffixPage = base.pages.single().copy(
                pageId = "6".repeat(64),
                pageOrder = 2,
                pageArtifactKey = "7".repeat(64),
                translationRegionIds = listOf(dependentRegionId),
                state = TranslationPageState.COMMITTED,
                artifactPath = "pages/dependent/translation.json",
                error = TranslationError("STALE_PAGE_ERROR"),
            )
            val interrupted = base.copy(
                status = TranslationJobStatus.CANCELLED,
                updatedAtEpochMillis = 2L,
                windows = listOf(committedPrefix, retryableFailure, dependentSuffix),
                pages = listOf(unrelatedCommittedPage, failedWindowPage, dependentSuffixPage),
            )

            val recovered = TranslationJobReducer.recoverInterrupted(interrupted, 3L + caseIndex)

            assertEquals(committedPrefix, recovered.windows[0], code)
            assertEquals(
                listOf(
                    TranslationWindowState.COMMITTED,
                    TranslationWindowState.PENDING,
                    TranslationWindowState.PENDING,
                ),
                recovered.windows.map(TranslationJobWindow::state),
                code,
            )
            recovered.windows.drop(1).forEach { window ->
                assertNull(window.checkpointPath, code)
                assertEquals(0, window.translatedItemCount, code)
                assertEquals(0, window.preservedItemCount, code)
                assertNull(window.usage, code)
                assertNull(window.error, code)
            }
            assertEquals(unrelatedCommittedPage, recovered.pages[0], code)
            recovered.pages.drop(1).forEach { page ->
                assertEquals(TranslationPageState.PENDING, page.state, code)
                assertNull(page.artifactPath, code)
                assertNull(page.error, code)
            }
        }
    }

    @Test
    fun `recovery does not rewind a semantic preserved window`() {
        val base = TranslationArtifactFixtures.job()
        val semanticFailure = base.windows.single().copy(
            state = TranslationWindowState.PRESERVED_SOURCE,
            checkpointPath = "windows/semantic.json",
            preservedItemCount = 1,
            attemptCount = 2,
            error = null,
        )
        val interrupted = base.copy(
            status = TranslationJobStatus.CANCELLED,
            windows = listOf(semanticFailure),
        )

        val recovered = TranslationJobReducer.recoverInterrupted(interrupted, 2L)

        assertEquals(semanticFailure, recovered.windows.single())
    }

    @Test
    fun `queued recovery normalizes a terminal suffix after an existing pending gap`() {
        val base = TranslationArtifactFixtures.job()
        val prefix = base.windows.single().copy(
            state = TranslationWindowState.COMMITTED,
            checkpointPath = "windows/prefix.json",
            translatedItemCount = 1,
        )
        val pending = base.windows.single().copy(
            windowIndex = 1,
            windowArtifactKey = "d".repeat(64),
        )
        val staleSuffix = base.windows.single().copy(
            windowIndex = 2,
            windowArtifactKey = "e".repeat(64),
            state = TranslationWindowState.COMMITTED,
            checkpointPath = "windows/stale.json",
            translatedItemCount = 1,
        )
        val queued = base.copy(
            status = TranslationJobStatus.QUEUED,
            windows = listOf(prefix, pending, staleSuffix),
        )

        val recovered = TranslationJobReducer.recoverInterrupted(queued, 2L)

        assertEquals(prefix, recovered.windows[0])
        assertEquals(TranslationWindowState.PENDING, recovered.windows[1].state)
        assertEquals(TranslationWindowState.PENDING, recovered.windows[2].state)
        assertNull(recovered.windows[2].checkpointPath)
        assertEquals(0, recovered.windows[2].translatedItemCount)
    }

    @Test
    fun `protected OCR makes a fully committed job successful with protection`() {
        var job = TranslationJobReducer.startRunning(TranslationArtifactFixtures.job(protectedOcrCount = 1), 2L)
        job = TranslationJobReducer.startWindow(job, 0, 3L)
        job = TranslationJobReducer.commitWindow(
            job,
            index = 0,
            checkpointPath = "windows/window.json",
            translatedCount = 1,
            preservedCount = 0,
            usage = TranslationUsage(10, 5, 15),
            error = null,
            now = 4L,
        )
        job = TranslationJobReducer.commitPage(job, job.pages.single().pageOrder, "pages/page/translation.json", 5L)
        job = TranslationJobReducer.finishSuccess(job, 6L)

        assertEquals(TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS, job.status)
        assertEquals(TranslationPageState.COMMITTED, job.pages.single().state)
    }

    @Test
    fun `cancellation returns only active window to pending`() {
        var job = TranslationJobReducer.startRunning(TranslationArtifactFixtures.job(), 2L)
        job = TranslationJobReducer.startWindow(job, 0, 3L)
        job = TranslationJobReducer.requestCancellation(job, 4L)
        job = TranslationJobReducer.finishCancellation(job, 5L)

        assertEquals(TranslationJobStatus.CANCELLED, job.status)
        assertEquals(TranslationWindowState.PENDING, job.windows.single().state)
        assertEquals(1, job.windows.single().attemptCount)
    }
}
