package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineDeviceCapacityTest {
    @Test
    fun `eight core phone uses all processors across two background priority engines`() {
        assertEquals(
            PipelineDeviceCapacity.OcrPlan(engineCount = 2, threadsPerEngine = 4),
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
            PipelineDeviceCapacity.OcrPlan(engineCount = 1, threadsPerEngine = 6),
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
            PipelineDeviceCapacity.OcrPlan(engineCount = 1, threadsPerEngine = 4),
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
