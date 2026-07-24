package rs.masumi.app.typesetting

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.typesetting.TypesettingJobStatus

@RunWith(AndroidJUnit4::class)
class TypesettingStatusBroadcastTest {
    @Test
    fun broadcastContainsOnlyStructuredSafeProgress() {
        val progress = TypesettingProgress(
            projectId = "project-1",
            jobId = "typesetting-job-1",
            runArtifactKey = "a".repeat(64),
            status = TypesettingJobStatus.RUNNING,
            terminalPageCount = 1,
            totalPageCount = 3,
            typesetRegionCount = 5,
            preservedRegionCount = 2,
            currentPageOrder = 1,
            errorCode = "SAFE_ERROR",
        )

        val intent = TypesettingStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(progress, TypesettingStatusBroadcast.parse(intent))
        assertEquals(TypesettingStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
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
        assertNull(TypesettingStatusBroadcast.parse(Intent("foreign")))
        assertTrue(TypesettingResumePolicy.shouldResume(TypesettingJobStatus.QUEUED, false))
        assertTrue(TypesettingResumePolicy.shouldResume(TypesettingJobStatus.RUNNING, false))
        assertFalse(TypesettingResumePolicy.shouldResume(TypesettingJobStatus.RUNNING, true))
        assertFalse(TypesettingResumePolicy.shouldResume(TypesettingJobStatus.CANCELLED, false))
    }
}
