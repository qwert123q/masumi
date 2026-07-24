package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineDeviceCapacityTest {
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
