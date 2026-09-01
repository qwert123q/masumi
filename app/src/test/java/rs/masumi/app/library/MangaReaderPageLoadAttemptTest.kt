package rs.masumi.app.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderPageLoadAttemptTest {
    @Test
    fun `decode exception releases the pending slot so the page can be requested again`() {
        var pendingGeneration: Int? = null
        var delivered: String? = null
        var failureCount = 0

        fun schedule(load: () -> String?): Boolean {
            if (pendingGeneration == GENERATION) return false
            pendingGeneration = GENERATION
            runReaderPageLoadAttempt(
                isStale = { false },
                load = load,
                deliver = { delivered = it },
                discard = {},
                onFailure = { failureCount += 1 },
                onFinished = {
                    if (pendingGeneration == GENERATION) pendingGeneration = null
                },
            )
            return true
        }

        assertTrue(schedule { throw IOException("synthetic decode failure") })
        assertNull(pendingGeneration)
        assertEquals(1, failureCount)

        assertTrue("the failed page remained permanently pending", schedule { "decoded-page" })
        assertEquals("decoded-page", delivered)
        assertNull(pendingGeneration)
    }

    @Test
    fun `generation change after decode discards stale result and releases pending slot`() {
        var activeGeneration = GENERATION
        var pendingGeneration: Int? = GENERATION
        var delivered = false
        var discarded: String? = null

        runReaderPageLoadAttempt(
            isStale = { activeGeneration != GENERATION },
            load = {
                activeGeneration += 1
                "stale-page"
            },
            deliver = { delivered = true },
            discard = { discarded = it },
            onFailure = { throw AssertionError("stale completion is not a decode failure") },
            onFinished = {
                if (pendingGeneration == GENERATION) pendingGeneration = null
            },
        )

        assertFalse(delivered)
        assertEquals("stale-page", discarded)
        assertNull(pendingGeneration)
    }

    private companion object {
        const val GENERATION = 7
    }
}
