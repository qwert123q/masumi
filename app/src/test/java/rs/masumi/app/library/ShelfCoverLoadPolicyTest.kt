package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCoverLoadPolicyTest {
    @Test
    fun `splits the first viewport cohort from deferred covers`() {
        assertEquals(
            ShelfCoverLoadPolicy.Plan(initial = listOf("a", "b"), deferred = listOf("c", "d")),
            ShelfCoverLoadPolicy.split(listOf("a", "b", "c", "d"), initialViewportCount = 2),
        )
    }

    @Test
    fun `deferred cover work is bounded to one adjacent viewport cohort`() {
        val projectIds = (0 until 100).map { "project-$it" }

        assertEquals(
            ShelfCoverLoadPolicy.Plan(
                initial = listOf("project-0", "project-1", "project-2"),
                deferred = listOf("project-3", "project-4", "project-5"),
            ),
            ShelfCoverLoadPolicy.split(projectIds, initialViewportCount = 3),
        )
    }

    @Test
    fun `unmeasured empty cohort schedules no deferred cover work`() {
        assertEquals(
            ShelfCoverLoadPolicy.Plan(initial = emptyList(), deferred = emptyList()),
            ShelfCoverLoadPolicy.split(listOf("a", "b"), initialViewportCount = 0),
        )
    }

    @Test
    fun `minimum cohort keeps the shelf interactive on an unmeasured viewport`() {
        assertEquals(1, ShelfCoverLoadPolicy.initialViewportCount(viewportHeight = 0, cardHeight = 0, itemCount = 5))
        assertEquals(3, ShelfCoverLoadPolicy.initialViewportCount(viewportHeight = 300, cardHeight = 120, itemCount = 5))
        assertEquals(6, ShelfCoverLoadPolicy.initialViewportCount(viewportHeight = 900, cardHeight = 150, itemCount = 8))
    }

    @Test
    fun `shelf reveal deadline is absolute from activity launch`() {
        assertEquals(650L, ShelfCoverLoadPolicy.remainingRevealDelay(100L, 900L, 350L))
        assertEquals(0L, ShelfCoverLoadPolicy.remainingRevealDelay(100L, 900L, 1_001L))
    }

    @Test
    fun `resolved shelf refresh keeps the actual viewport instead of restarting the initial cohort`() {
        assertTrue(
            ShelfCoverLoadPolicy.shouldUseInitialCohort(
                shelfAlreadyRevealed = false,
                missingCoverCount = 40,
            ),
        )
        assertFalse(
            ShelfCoverLoadPolicy.shouldUseInitialCohort(
                shelfAlreadyRevealed = true,
                missingCoverCount = 40,
            ),
        )
        assertFalse(
            ShelfCoverLoadPolicy.shouldUseInitialCohort(
                shelfAlreadyRevealed = false,
                missingCoverCount = 0,
            ),
        )
    }

    @Test
    fun `viewport loading waits for the atomic shelf reveal`() {
        assertFalse(ShelfCoverLoadPolicy.shouldScheduleViewport(shelfAlreadyRevealed = false))
        assertTrue(ShelfCoverLoadPolicy.shouldScheduleViewport(shelfAlreadyRevealed = true))
    }

    @Test
    fun `null visible cover completion stays retryable after the shelf reveal`() {
        val requests = ShelfCoverRequestTracker()
        val request = requireNotNull(requests.tryStart("cover-a", renderGeneration = 1L))

        assertEquals(
            ShelfCoverCompletionAction.KEEP_RETRYABLE,
            requests.complete(
                request = request,
                currentRenderGeneration = 1L,
                stillDesired = true,
                bitmapAvailable = false,
            ),
        )
        assertNotNull(requests.tryStart("cover-a", renderGeneration = 1L))
    }

    @Test
    fun `new render can request a cover while the previous generation is still in flight`() {
        val requests = ShelfCoverRequestTracker()
        val stale = requireNotNull(requests.tryStart("cover-a", renderGeneration = 1L))

        assertNull(requests.tryStart("cover-a", renderGeneration = 1L))
        val current = requests.tryStart("cover-a", renderGeneration = 2L)
        assertNotNull(current)

        assertEquals(
            ShelfCoverCompletionAction.DISCARD,
            requests.complete(
                request = stale,
                currentRenderGeneration = 2L,
                stillDesired = true,
                bitmapAvailable = true,
            ),
        )
        assertNull(
            "finishing the stale request removed the current request",
            requests.tryStart("cover-a", renderGeneration = 2L),
        )
        assertEquals(
            ShelfCoverCompletionAction.APPLY_AND_RESOLVE,
            requests.complete(
                request = requireNotNull(current),
                currentRenderGeneration = 2L,
                stillDesired = true,
                bitmapAvailable = true,
            ),
        )
    }

    @Test
    fun `late completion after activity teardown is discarded without retaining the request`() {
        val requests = ShelfCoverRequestTracker()
        val request = requireNotNull(requests.tryStart("cover-a", renderGeneration = 1L))

        requests.clear()

        assertEquals(
            ShelfCoverCompletionAction.DISCARD,
            requests.complete(
                request = request,
                currentRenderGeneration = 1L,
                stillDesired = true,
                bitmapAvailable = true,
            ),
        )
        assertNotNull(requests.tryStart("cover-a", renderGeneration = 1L))
    }
}
