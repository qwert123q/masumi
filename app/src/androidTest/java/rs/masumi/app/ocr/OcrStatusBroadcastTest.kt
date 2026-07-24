package rs.masumi.app.ocr

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.ocr.OcrJobStatus

@RunWith(AndroidJUnit4::class)
class OcrStatusBroadcastTest {
    @Test
    fun broadcastIsPackageScopedAndContainsOnlyStructuredSafeFields() {
        val progress = OcrProgress(
            projectId = "project-1",
            jobId = "ocr-job-1",
            runArtifactKey = "a".repeat(64),
            status = OcrJobStatus.RUNNING,
            terminalRegionCount = 3,
            totalRegionCount = 8,
            committedPageCount = 1,
            totalPageCount = 4,
            currentOrder = 1,
            currentPageId = "b".repeat(64),
            currentRegionId = "c".repeat(64),
            downloadedBytes = 100,
            totalDownloadBytes = 200,
            errorCode = "STABLE_ERROR",
        )

        val intent = OcrStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(OcrStatusBroadcast.ACTION, intent.action)
        assertEquals("rs.masumi.app.dev", intent.`package`)
        assertEquals(OcrStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
        assertEquals(progress, OcrStatusBroadcast.parse(intent))
        intent.extras!!.keySet().forEach { key ->
            val value = intent.extras!!.get(key)
            assertFalse(value is ByteArray)
            if (value is String) {
                assertFalse(value.contains('/'))
                assertFalse(value.contains("http", ignoreCase = true))
            }
        }
    }

    @Test
    fun malformedIntentIsRejectedAndOnlyInterruptedActiveJobAutoResumes() {
        assertNull(OcrStatusBroadcast.parse(Intent("foreign")))
        assertTrue(OcrResumePolicy.shouldResume(OcrJobStatus.QUEUED, false))
        assertTrue(OcrResumePolicy.shouldResume(OcrJobStatus.LOADING_MODEL, false))
        assertTrue(OcrResumePolicy.shouldResume(OcrJobStatus.RUNNING, false))
        assertFalse(OcrResumePolicy.shouldResume(OcrJobStatus.RUNNING, true))
        assertFalse(OcrResumePolicy.shouldResume(OcrJobStatus.CANCELLED, false))
        assertFalse(OcrResumePolicy.shouldResume(OcrJobStatus.SUCCEEDED, false))
    }
}
