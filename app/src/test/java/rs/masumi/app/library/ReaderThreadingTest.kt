package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderThreadingTest {
    @Test
    fun `wider viewport never reuses a narrow decoded page cache entry`() {
        val narrow = ReaderThreading.decodeProfile(720, 4_000_000L, 1.25f)
        val wide = ReaderThreading.decodeProfile(1_440, 4_000_000L, 1.25f)

        assertNotEquals(narrow, wide)
    }

    @Test
    fun `cache writer threads run at low priority`() {
        val thread = ReaderThreading.lowPriorityFactory("cache-writer-test").newThread {}

        assertEquals(Thread.MIN_PRIORITY, thread.priority)
    }
}
