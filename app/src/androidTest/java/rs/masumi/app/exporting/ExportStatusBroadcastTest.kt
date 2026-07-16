package rs.masumi.app.exporting

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.exporting.ExportJobStatus

@RunWith(AndroidJUnit4::class)
class ExportStatusBroadcastTest {
    @Test
    fun broadcastContainsOnlyStructuredSafeProgress() {
        val progress = ExportProgress(
            projectId = "project-1",
            jobId = "export-job-1",
            exportKey = "a".repeat(64),
            status = ExportJobStatus.RUNNING,
            terminalPageCount = 1,
            totalPageCount = 3,
            flattenedPageCount = 1,
            cleanedFallbackPageCount = 0,
            sourceFallbackPageCount = 0,
            reusedPageCount = 1,
            currentPageOrder = 1,
            errorCode = "SAFE_ERROR",
        )

        val intent = ExportStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(progress, ExportStatusBroadcast.parse(intent))
        assertEquals(ExportStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
        intent.extras!!.keySet().forEach { key ->
            val value = intent.extras!!.get(key)
            assertFalse(value is ByteArray)
            if (value is String) {
                assertFalse(value.contains("http", ignoreCase = true))
                assertFalse(value.contains('/'))
            }
        }
    }

    @Test
    fun malformedBroadcastIsRejectedAndOnlyInterruptedWorkAutoResumes() {
        assertNull(ExportStatusBroadcast.parse(Intent("foreign")))
        assertTrue(ExportResumePolicy.shouldResume(ExportJobStatus.QUEUED, false))
        assertTrue(ExportResumePolicy.shouldResume(ExportJobStatus.RUNNING, false))
        assertFalse(ExportResumePolicy.shouldResume(ExportJobStatus.RUNNING, true))
        assertFalse(ExportResumePolicy.shouldResume(ExportJobStatus.CANCELLED, false))
        assertFalse(ExportResumePolicy.shouldResume(ExportJobStatus.FAILED, false))
    }
}
