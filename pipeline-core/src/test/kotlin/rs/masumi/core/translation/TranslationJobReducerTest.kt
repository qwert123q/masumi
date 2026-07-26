package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `salvaged window items move preserved counts back to translated`() {
        val base = TranslationArtifactFixtures.job()
        val running = base.copy(
            status = TranslationJobStatus.RUNNING,
            windows = listOf(
                base.windows.single().copy(
                    state = TranslationWindowState.COMMITTED,
                    translatedItemCount = 0,
                    preservedItemCount = 1,
                ),
            ),
        )

        val salvaged = TranslationJobReducer.salvageWindowItems(running, 0, 1, 3L)

        assertEquals(1, salvaged.windows.single().translatedItemCount)
        assertEquals(0, salvaged.windows.single().preservedItemCount)
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
