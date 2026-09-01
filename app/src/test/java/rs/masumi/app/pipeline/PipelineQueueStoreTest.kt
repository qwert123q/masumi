package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineQueueStoreTest {
    @Test
    fun `reads queue json written by the previous org json implementation`() {
        val persistence = InMemoryQueuePersistence(
            initialValue = """
                [
                  {
                    "projectId":"fcc0d4e9-391c-4bea-9712-4157c55d172b",
                    "enqueuedAtEpochMillis":1,
                    "status":"PAUSED",
                    "errorCode":"USER_PAUSED",
                    "consecutiveFailureCount":0,
                    "retryNotBeforeEpochMillis":0
                  },
                  {
                    "projectId":"legacy-with-defaults",
                    "enqueuedAtEpochMillis":2,
                    "status":"ACTIVE"
                  }
                ]
            """.trimIndent(),
        )

        val entries = PipelineQueueStore(persistence).entries()

        assertEquals(2, entries.size)
        assertEquals(PipelineQueueStatus.PAUSED, entries[0].status)
        assertEquals("USER_PAUSED", entries[0].errorCode)
        assertEquals(PipelineQueueStatus.ACTIVE, entries[1].status)
        assertNull(entries[1].errorCode)
    }

    @Test
    fun `malformed queue json remains fail closed`() {
        val queue = PipelineQueueStore(InMemoryQueuePersistence("not-json"))

        assertEquals(emptyList<PipelineQueueEntry>(), queue.entries())
    }

    @Test
    fun `writing an entry without an error omits the legacy nullable field`() {
        val persistence = InMemoryQueuePersistence(null)

        PipelineQueueStore(persistence).enqueue("active-project", now = 1L)

        assertFalse(requireNotNull(persistence.read()).contains("\"errorCode\""))
    }

    @Test
    fun `legacy active retry is migrated to an immediately paused failure`() {
        val persistence = InMemoryQueuePersistence(
            initialValue = """
                [
                  {
                    "projectId":"legacy-retry",
                    "enqueuedAtEpochMillis":2,
                    "status":"ACTIVE",
                    "errorCode":"TIMEOUT",
                    "consecutiveFailureCount":3,
                    "retryNotBeforeEpochMillis":999999
                  }
                ]
            """.trimIndent(),
        )

        val entry = PipelineQueueStore(persistence).entries().single()

        assertEquals(PipelineQueueStatus.PAUSED, entry.status)
        assertEquals("TIMEOUT", entry.errorCode)
    }

    @Test
    fun `pausing a failure writes no retry counters or retry deadline`() {
        val persistence = InMemoryQueuePersistence(null)
        val queue = PipelineQueueStore(persistence)
        queue.enqueue("failed-project", now = 1L)

        queue.pause("failed-project", "NETWORK")

        val written = requireNotNull(persistence.read())
        assertFalse(written.contains("consecutiveFailureCount"))
        assertFalse(written.contains("retryNotBeforeEpochMillis"))
        assertEquals(PipelineQueueStatus.PAUSED, queue.entries().single().status)
        assertEquals("NETWORK", queue.entries().single().errorCode)
    }

    @Test
    fun `failure pauses only an active project and never overwrites a user pause`() {
        val queue = PipelineQueueStore(InMemoryQueuePersistence(null))
        queue.enqueue("project", now = 1L)

        assertTrue(queue.fail("project", "NETWORK"))
        assertEquals(PipelineQueueStatus.PAUSED, queue.entries().single().status)
        assertEquals("NETWORK", queue.entries().single().errorCode)

        queue.enqueue("project", now = 2L)
        queue.pause("project", "USER_PAUSED")

        assertFalse(queue.fail("project", "STAGE_FAILED"))
        assertEquals(PipelineQueueStatus.PAUSED, queue.entries().single().status)
        assertEquals("USER_PAUSED", queue.entries().single().errorCode)
    }

    private class InMemoryQueuePersistence(
        initialValue: String?,
    ) : PipelineQueuePersistence {
        private var storedEntries: String? = initialValue

        override fun read(): String? = storedEntries

        override fun write(value: String): Boolean {
            storedEntries = value
            return true
        }
    }
}
