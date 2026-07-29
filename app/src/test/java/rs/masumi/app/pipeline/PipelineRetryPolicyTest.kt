package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRetryPolicyTest {
    @Test
    fun `provider timeouts and incomplete responses retry automatically`() {
        assertTrue(PipelineRetryPolicy.isRetryable(PipelineStage.TRANSLATION, "TIMEOUT"))
        assertTrue(
            PipelineRetryPolicy.isRetryable(
                PipelineStage.TRANSLATION,
                "INCOMPLETE_TRANSLATION_RESPONSE",
            ),
        )
    }

    @Test
    fun `authentication and corrupt local artifacts remain paused`() {
        assertFalse(PipelineRetryPolicy.isRetryable(PipelineStage.TRANSLATION, "HTTP_CLIENT"))
        assertFalse(PipelineRetryPolicy.isRetryable(PipelineStage.OCR, "SOURCE_HASH_MISMATCH"))
    }

    @Test
    fun `backoff grows and caps at thirty minutes`() {
        assertEquals(30_000L, PipelineRetryPolicy.delayMillis(1))
        assertEquals(60_000L, PipelineRetryPolicy.delayMillis(2))
        assertEquals(8L * 60L * 1_000L, PipelineRetryPolicy.delayMillis(5))
        assertEquals(30L * 60L * 1_000L, PipelineRetryPolicy.delayMillis(100))
    }
}
