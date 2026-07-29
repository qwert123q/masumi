package rs.masumi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineErrorMessageTest {
    @Test
    fun modelInstallFailureGetsModelAdvice() {
        assertEquals(
            PipelineErrorAdvice.MODEL,
            classifyPipelineError("MODEL_INSTALL_IO"),
        )
    }

    @Test
    fun commonServiceAndProjectFailuresGetActionableCategories() {
        assertEquals(PipelineErrorAdvice.CREDENTIALS, classifyPipelineError("HTTP_CLIENT"))
        assertEquals(
            PipelineErrorAdvice.RESPONSE,
            classifyPipelineError("INCOMPLETE_TRANSLATION_RESPONSE"),
        )
        assertEquals(PipelineErrorAdvice.NETWORK, classifyPipelineError("TIMEOUT"))
        assertEquals(PipelineErrorAdvice.STORAGE, classifyPipelineError("PAGE_ARTIFACT_WRITE_FAILED"))
        assertEquals(PipelineErrorAdvice.SOURCE, classifyPipelineError("SOURCE_HASH_MISMATCH"))
        assertEquals(PipelineErrorAdvice.DEVICE, classifyPipelineError("ACCELERATOR_UNAVAILABLE"))
    }

    @Test
    fun missingAndUnrecognizedCodesRemainSafe() {
        assertEquals(PipelineErrorAdvice.UNKNOWN, classifyPipelineError(null))
        assertEquals(PipelineErrorAdvice.UNKNOWN, classifyPipelineError("UNEXPECTED_FAILURE"))
    }
}
