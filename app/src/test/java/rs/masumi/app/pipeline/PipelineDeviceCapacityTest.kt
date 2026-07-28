package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineDeviceCapacityTest {
    @Test
    fun `eight core phone reserves two processors while retaining dual OCR throughput`() {
        assertEquals(
            PipelineDeviceCapacity.OcrPlan(engineCount = 2, threadsPerEngine = 3),
            PipelineDeviceCapacity.ocrPlan(
                totalMemoryBytes = 12L * 1_024L * 1_024L * 1_024L,
                processorCount = 8,
                thermalSevere = false,
            ),
        )
    }

    @Test
    fun `thermal pressure uses one bounded OCR engine`() {
        assertEquals(
            PipelineDeviceCapacity.OcrPlan(engineCount = 1, threadsPerEngine = 5),
            PipelineDeviceCapacity.ocrPlan(
                totalMemoryBytes = 12L * 1_024L * 1_024L * 1_024L,
                processorCount = 8,
                thermalSevere = true,
            ),
        )
    }

    @Test
    fun `small phone never requests more OCR threads than available`() {
        assertEquals(
            PipelineDeviceCapacity.OcrPlan(engineCount = 1, threadsPerEngine = 2),
            PipelineDeviceCapacity.ocrPlan(
                totalMemoryBytes = 4L * 1_024L * 1_024L * 1_024L,
                processorCount = 4,
                thermalSevere = false,
            ),
        )
    }

    @Test
    fun `modern high memory phone receives two network slots`() {
        assertEquals(
            2,
            PipelineDeviceCapacity.translationSlots(
                totalMemoryBytes = 12L * 1_024L * 1_024L * 1_024L,
                processorCount = 8,
                thermalSevere = false,
            ),
        )
    }

    @Test
    fun `thermal pressure or smaller device reduces translation to one`() {
        assertEquals(
            1,
            PipelineDeviceCapacity.translationSlots(
                totalMemoryBytes = 12L * 1_024L * 1_024L * 1_024L,
                processorCount = 8,
                thermalSevere = true,
            ),
        )
        assertEquals(
            1,
            PipelineDeviceCapacity.translationSlots(
                totalMemoryBytes = 4L * 1_024L * 1_024L * 1_024L,
                processorCount = 8,
                thermalSevere = false,
            ),
        )
    }
}
