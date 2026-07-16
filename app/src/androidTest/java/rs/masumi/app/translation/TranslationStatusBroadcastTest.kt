package rs.masumi.app.translation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.translation.TranslationJobStatus

@RunWith(AndroidJUnit4::class)
class TranslationStatusBroadcastTest {
    @Test
    fun broadcastContainsOnlyStructuredSafeProgress() {
        val progress = TranslationProgress(
            projectId = "project-1",
            jobId = "translation-job-1",
            runArtifactKey = "a".repeat(64),
            status = TranslationJobStatus.RUNNING,
            terminalWindowCount = 1,
            totalWindowCount = 3,
            committedPageCount = 0,
            totalPageCount = 2,
            translatedItemCount = 5,
            preservedItemCount = 1,
            protectedOcrCount = 2,
            currentWindowIndex = 1,
            errorCode = "SAFE_ERROR",
        )

        val intent = TranslationStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(progress, TranslationStatusBroadcast.parse(intent))
        assertEquals(TranslationStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras!!.keySet())
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
        assertNull(TranslationStatusBroadcast.parse(Intent("foreign")))
        assertTrue(TranslationResumePolicy.shouldResume(TranslationJobStatus.QUEUED, false))
        assertTrue(TranslationResumePolicy.shouldResume(TranslationJobStatus.RUNNING, false))
        assertFalse(TranslationResumePolicy.shouldResume(TranslationJobStatus.RUNNING, true))
        assertFalse(TranslationResumePolicy.shouldResume(TranslationJobStatus.CANCELLED, false))
    }
}
