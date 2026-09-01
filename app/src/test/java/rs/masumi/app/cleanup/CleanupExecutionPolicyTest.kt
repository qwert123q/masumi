package rs.masumi.app.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupExecutionPolicyTest {
    @Test
    fun `configured neural fallback without a potential candidate keeps deterministic groups parallel`() {
        assertEquals(
            4,
            CleanupExecutionPolicy.workerCount(
                availableWorkerCount = 6,
                groupCount = 4,
                neuralPipelineConfigured = true,
                potentialNeuralCandidateExists = false,
            ),
        )
    }

    @Test
    fun `potential neural candidate keeps cleanup serialized in target order`() {
        assertEquals(
            1,
            CleanupExecutionPolicy.workerCount(
                availableWorkerCount = 6,
                groupCount = 4,
                neuralPipelineConfigured = true,
                potentialNeuralCandidateExists = true,
            ),
        )
    }
}
