package rs.masumi.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.pipeline.PipelineQueuePersistence
import rs.masumi.app.pipeline.PipelineQueueStatus
import rs.masumi.app.pipeline.PipelineQueueStore

class TranslationSettingsSaveCoordinatorTest {
    @Test
    fun `saving a provider keeps a paused project paused`() {
        val queue = queueStore()
        queue.enqueue("4008174", now = 1L)
        queue.pause("4008174", "USER_PAUSED")
        var refreshCount = 0
        var wakeCount = 0

        val pipelineActive = TranslationSettingsSaveCoordinator(queue).onSettingsSaved(
            projectId = "4008174",
            refreshDurableState = { refreshCount += 1 },
            wakeScheduler = { wakeCount += 1 },
        )

        assertFalse(pipelineActive)
        assertEquals(1, refreshCount)
        assertEquals(1, wakeCount)
        assertEquals(
            PipelineQueueStatus.PAUSED,
            queue.entries().single { it.projectId == "4008174" }.status,
        )
    }

    @Test
    fun `saving a provider does not enqueue an unqueued project`() {
        val queue = queueStore()

        val pipelineActive = TranslationSettingsSaveCoordinator(queue).onSettingsSaved(
            projectId = "4008174",
            refreshDurableState = {},
            wakeScheduler = {},
        )

        assertFalse(pipelineActive)
        assertTrue(queue.entries().isEmpty())
    }

    private fun queueStore(): PipelineQueueStore = PipelineQueueStore(InMemoryQueuePersistence())

    private class InMemoryQueuePersistence : PipelineQueuePersistence {
        private var storedEntries: String? = null

        override fun read(): String? = storedEntries

        override fun write(value: String): Boolean {
            storedEntries = value
            return true
        }
    }
}
