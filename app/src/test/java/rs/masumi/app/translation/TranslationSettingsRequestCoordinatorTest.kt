package rs.masumi.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSettingsRequestCoordinatorTest {
    @Test
    fun `starting a new request cancels the old request and rejects its stale completion`() {
        var oldCancellationCount = 0
        var newCancellationCount = 0
        val coordinator = TranslationSettingsRequestCoordinator()
        val oldTicket = coordinator.start { oldCancellationCount += 1 }

        val newTicket = coordinator.start { newCancellationCount += 1 }

        assertEquals(1, oldCancellationCount)
        assertEquals(0, newCancellationCount)
        assertFalse(coordinator.finish(oldTicket))
        assertTrue(coordinator.finish(newTicket))
    }

    @Test
    fun `cancelling the coordinator cancels current request and invalidates its completion`() {
        var cancellationCount = 0
        val coordinator = TranslationSettingsRequestCoordinator()
        val ticket = coordinator.start { cancellationCount += 1 }

        coordinator.cancelActive()

        assertEquals(1, cancellationCount)
        assertFalse(coordinator.finish(ticket))
    }
}
