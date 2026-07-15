package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.ocr.OcrJobStatus

@RunWith(AndroidJUnit4::class)
class OcrProgressThrottleTest {
    @Test
    fun limitsRepeatedDownloadUpdatesButNeverHidesStatusChanges() {
        var now = 1_000L
        val throttle = OcrProgressThrottle(nowMillis = { now }, minimumIntervalMillis = 500L)

        assertTrue(throttle.shouldPublish(progress(OcrJobStatus.DOWNLOADING_MODEL, 1L, 100L)))
        now += 100L
        assertFalse(throttle.shouldPublish(progress(OcrJobStatus.DOWNLOADING_MODEL, 2L, 100L)))
        now += 400L
        assertTrue(throttle.shouldPublish(progress(OcrJobStatus.DOWNLOADING_MODEL, 3L, 100L)))
        now += 1L
        assertTrue(throttle.shouldPublish(progress(OcrJobStatus.LOADING_MODEL, 100L, 100L)))
    }

    @Test
    fun publishesCompletedDownloadEvenInsideTheInterval() {
        var now = 1_000L
        val throttle = OcrProgressThrottle(nowMillis = { now }, minimumIntervalMillis = 500L)

        assertTrue(throttle.shouldPublish(progress(OcrJobStatus.DOWNLOADING_MODEL, 90L, 100L)))
        now += 10L
        assertTrue(throttle.shouldPublish(progress(OcrJobStatus.DOWNLOADING_MODEL, 100L, 100L)))
    }

    private fun progress(status: OcrJobStatus, downloaded: Long, total: Long) = OcrProgress(
        projectId = "project",
        jobId = "job",
        runArtifactKey = "a".repeat(64),
        status = status,
        terminalRegionCount = 0,
        totalRegionCount = 1,
        committedPageCount = 0,
        totalPageCount = 1,
        downloadedBytes = downloaded,
        totalDownloadBytes = total,
    )
}
