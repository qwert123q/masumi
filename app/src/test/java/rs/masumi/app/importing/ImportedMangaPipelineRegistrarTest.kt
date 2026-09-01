package rs.masumi.app.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.pipeline.PipelineQueuePersistence
import rs.masumi.app.pipeline.PipelineQueueStatus
import rs.masumi.app.pipeline.PipelineQueueStore

class ImportedMangaPipelineRegistrarTest {
    @Test
    fun `persisted projects queue once without an activity presentation callback`() {
        val queue = PipelineQueueStore(InMemoryQueuePersistence())
        var schedulerWakeCount = 0
        val registrar = ImportedMangaPipelineRegistrar(
            queueStore = queue,
            hasTranslationSettings = { true },
            wakeScheduler = { schedulerWakeCount += 1 },
            clock = { 100L },
        )

        val first = registrar.register(listOf("project-a", "project-b"))
        queue.pause("project-a", "USER_PAUSED")
        val repeated = registrar.register(listOf("project-a", "project-b"))

        assertTrue(first.hasTranslationSettings)
        assertEquals(2, first.enqueuedProjectCount)
        assertEquals(0, repeated.enqueuedProjectCount)
        assertEquals(1, schedulerWakeCount)
        assertEquals(
            listOf(PipelineQueueStatus.PAUSED, PipelineQueueStatus.ACTIVE),
            queue.entries().map { it.status },
        )
        assertEquals(listOf(100L, 101L), queue.entries().map { it.enqueuedAtEpochMillis })
    }

    @Test
    fun `persisted projects stay ready when translation is not configured`() {
        val queue = PipelineQueueStore(InMemoryQueuePersistence())
        var schedulerWakeCount = 0
        val registrar = ImportedMangaPipelineRegistrar(
            queueStore = queue,
            hasTranslationSettings = { false },
            wakeScheduler = { schedulerWakeCount += 1 },
            clock = { 100L },
        )

        val registration = registrar.register(listOf("project-a"))

        assertEquals(false, registration.hasTranslationSettings)
        assertEquals(0, registration.enqueuedProjectCount)
        assertEquals(emptyList<Any>(), queue.entries())
        assertEquals(0, schedulerWakeCount)
    }

    private class InMemoryQueuePersistence : PipelineQueuePersistence {
        private var value: String? = null

        override fun read(): String? = value

        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
    }
}
