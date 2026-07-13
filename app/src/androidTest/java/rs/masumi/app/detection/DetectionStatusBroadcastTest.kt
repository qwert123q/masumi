package rs.masumi.app.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectionJobStatus

@RunWith(AndroidJUnit4::class)
class DetectionStatusBroadcastTest {
    @Test
    fun broadcastIsPackageScopedAndContainsOnlySafeStructuredFields() {
        val progress = DetectionProgress(
            projectId = "project-1",
            jobId = "job-1",
            runArtifactKey = "a".repeat(64),
            status = DetectionJobStatus.RUNNING,
            committedPageCount = 2,
            preservedPageCount = 1,
            totalPageCount = 5,
            currentOrder = 3,
            downloadedBytes = 100,
            totalBytes = 200,
            errorCode = "STABLE_ERROR_CODE",
        )

        val intent = DetectionStatusBroadcast.create(
            packageName = "rs.masumi.app.dev",
            progress = progress,
        )

        assertEquals(DetectionStatusBroadcast.ACTION, intent.action)
        assertEquals("rs.masumi.app.dev", intent.`package`)
        assertNull(intent.data)
        assertTrue(intent.categories.isNullOrEmpty())
        assertEquals(DetectionStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
        assertEquals(progress, DetectionStatusBroadcast.parse(intent))
        intent.extras!!.keySet().forEach { key ->
            val value = intent.extras!!.get(key)
            assertFalse(value is ByteArray)
            if (value is String) {
                assertFalse(value.contains('/'))
                assertFalse(value.contains("http", ignoreCase = true))
                assertFalse(value.contains("Exception", ignoreCase = true))
            }
        }
    }

    @Test
    fun malformedOrForeignIntentIsRejected() {
        val foreign = android.content.Intent("foreign.action")
        assertNull(DetectionStatusBroadcast.parse(foreign))

        val missingFields = android.content.Intent(DetectionStatusBroadcast.ACTION)
        assertNull(DetectionStatusBroadcast.parse(missingFields))
    }

    @Test
    fun onlyAnUnrequestedActiveJournalIsAutoResumed() {
        assertTrue(DetectionResumePolicy.shouldResume(DetectionJobStatus.QUEUED, false))
        assertTrue(DetectionResumePolicy.shouldResume(DetectionJobStatus.RUNNING, false))
        assertTrue(DetectionResumePolicy.shouldResume(DetectionJobStatus.DOWNLOADING_MODEL, false))
        assertFalse(DetectionResumePolicy.shouldResume(DetectionJobStatus.RUNNING, true))
        assertFalse(DetectionResumePolicy.shouldResume(DetectionJobStatus.CANCELLED, false))
        assertFalse(DetectionResumePolicy.shouldResume(DetectionJobStatus.FAILED, false))
        assertFalse(DetectionResumePolicy.shouldResume(DetectionJobStatus.SUCCEEDED, false))
    }
}
