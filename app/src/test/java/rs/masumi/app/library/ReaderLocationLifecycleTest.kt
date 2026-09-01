package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLocationLifecycleTest {
    @Test
    fun `save before asynchronous page bind keeps the pending durable anchor`() {
        val pending = ReadingLocation(37, 0.625f)

        val saved = ReaderLocationLifecycle.locationForSave(
            pagesBound = false,
            readerLaidOut = false,
            pendingRestoreApplied = false,
            pending = pending,
            captureBoundView = { throw AssertionError("unbound view must not replace the durable anchor") },
        )

        assertEquals(pending, saved)
    }

    @Test
    fun `save after page bind but before layout keeps the pending durable anchor`() {
        val pending = ReadingLocation(37, 0.625f)

        val saved = ReaderLocationLifecycle.locationForSave(
            pagesBound = true,
            readerLaidOut = false,
            pendingRestoreApplied = false,
            pending = pending,
            captureBoundView = { throw AssertionError("unlaid-out view must not replace the durable anchor") },
        )

        assertEquals(pending, saved)
    }

    @Test
    fun `save after layout but before pending restore keeps the pending durable anchor`() {
        val pending = ReadingLocation(37, 0.625f)

        val saved = ReaderLocationLifecycle.locationForSave(
            pagesBound = true,
            readerLaidOut = true,
            pendingRestoreApplied = false,
            pending = pending,
            captureBoundView = { throw AssertionError("unrestored view must not replace the durable anchor") },
        )

        assertEquals(pending, saved)
    }

    @Test
    fun `save after layout and pending restore captures the live continuous scroll anchor`() {
        val live = ReadingLocation(41, 0.25f)

        assertEquals(
            live,
            ReaderLocationLifecycle.locationForSave(
                pagesBound = true,
                readerLaidOut = true,
                pendingRestoreApplied = true,
                pending = ReadingLocation(2, 0.1f),
                captureBoundView = { live },
            ),
        )
    }
}
