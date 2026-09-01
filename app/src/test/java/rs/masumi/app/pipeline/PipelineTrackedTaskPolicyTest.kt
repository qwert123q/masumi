package rs.masumi.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTrackedTaskPolicyTest {
    @Test
    fun `paused queue releases a matching tracked service`() {
        assertTrue(
            PipelineTrackedTaskPolicy.shouldRelease(
                queueActive = false,
                state = activeState(PipelineStage.OCR),
                expectedStage = PipelineStage.OCR,
            ),
        )
    }

    @Test
    fun `active queue retains a matching tracked service`() {
        assertFalse(
            PipelineTrackedTaskPolicy.shouldRelease(
                queueActive = true,
                state = activeState(PipelineStage.OCR),
                expectedStage = PipelineStage.OCR,
            ),
        )
    }

    @Test
    fun `terminal or changed state releases a tracked service`() {
        assertTrue(PipelineTrackedTaskPolicy.shouldRelease(true, null, PipelineStage.OCR))
        assertTrue(
            PipelineTrackedTaskPolicy.shouldRelease(
                queueActive = true,
                state = activeState(PipelineStage.TRANSLATION),
                expectedStage = PipelineStage.OCR,
            ),
        )
        assertTrue(
            PipelineTrackedTaskPolicy.shouldRelease(
                queueActive = true,
                state = activeState(PipelineStage.OCR).copy(blocked = true),
                expectedStage = PipelineStage.OCR,
            ),
        )
        assertTrue(
            PipelineTrackedTaskPolicy.shouldRelease(
                queueActive = true,
                state = activeState(PipelineStage.OCR).copy(complete = true),
                expectedStage = PipelineStage.OCR,
            ),
        )
    }

    private fun activeState(stage: PipelineStage) = ProjectPipelineState(
        projectId = "project",
        pageCount = 1,
        nextStage = stage,
    )
}
