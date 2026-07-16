package rs.masumi.app.cleanup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.cleanup.CleanupJobStatus

@RunWith(AndroidJUnit4::class)
class CleanupStatusBroadcastTest {
    @Test
    fun broadcastContainsOnlyStructuredSafeProgress() {
        val progress = CleanupProgress(
            projectId = "project-1",
            jobId = "cleanup-job-1",
            runArtifactKey = "a".repeat(64),
            status = CleanupJobStatus.RUNNING,
            terminalPageCount = 1,
            totalPageCount = 3,
            cleanedRegionCount = 5,
            preservedRegionCount = 2,
            currentPageOrder = 1,
            errorCode = "SAFE_ERROR",
        )

        val intent = CleanupStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(progress, CleanupStatusBroadcast.parse(intent))
        assertEquals(CleanupStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
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
        assertNull(CleanupStatusBroadcast.parse(Intent("foreign")))
        assertTrue(CleanupResumePolicy.shouldResume(CleanupJobStatus.QUEUED, false))
        assertTrue(CleanupResumePolicy.shouldResume(CleanupJobStatus.RUNNING, false))
        assertFalse(CleanupResumePolicy.shouldResume(CleanupJobStatus.RUNNING, true))
        assertFalse(CleanupResumePolicy.shouldResume(CleanupJobStatus.CANCELLED, false))
    }
}
