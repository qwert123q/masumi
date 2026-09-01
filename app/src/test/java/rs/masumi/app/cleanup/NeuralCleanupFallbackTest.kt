package rs.masumi.app.cleanup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NeuralCleanupFallbackTest {
    @Test
    fun `small regions are rejected before cleanup scheduling reserves neural order`() {
        assertFalse(
            NeuralCleanupFallback.isPotentiallyEligible(
                corePixelCount = 99_999,
                longestCoreSide = 1_200,
            ),
        )
        assertTrue(
            NeuralCleanupFallback.isPotentiallyEligible(
                corePixelCount = 100_000,
                longestCoreSide = 1_200,
            ),
        )
    }

    @Test
    fun `only an existing large complex eligibility region receives neural fallback`() {
        assertTrue(
            NeuralCleanupFallback.isEligible(
                complexBackground = true,
                residualPixelCount = 11,
                corePixelCount = 100_000,
                longestCoreSide = 1_200,
            ),
        )
        assertFalse(
            NeuralCleanupFallback.isEligible(
                complexBackground = true,
                residualPixelCount = 11,
                corePixelCount = 99_999,
                longestCoreSide = 1_200,
            ),
        )
    }

    @Test
    fun `simple background stays on deterministic path`() {
        assertFalse(
            NeuralCleanupFallback.isEligible(
                complexBackground = false,
                residualPixelCount = 120,
                corePixelCount = 200_000,
                longestCoreSide = 1_500,
            ),
        )
    }

    @Test
    fun `run budget admits eight completed attempts and denies the ninth`() {
        var now = 0L
        val budget = NeuralRepairBudget(8, 120_000L) { now }

        repeat(8) {
            val lease = requireNotNull(budget.acquire())
            now += 10
            lease.close()
        }

        assertTrue(budget.acquire() == null)
    }

    @Test
    fun `lease exposes its cooperative deadline before close`() {
        var now = 0L
        val budget = NeuralRepairBudget(8, 120_000L) { now }
        val lease = requireNotNull(budget.acquire())

        assertFalse(lease.shouldStop())
        now = 120_001L
        assertTrue(lease.shouldStop())
        assertEquals(0L, lease.remainingMillis())
        lease.close()
        lease.close()
        assertTrue(budget.acquire() == null)
    }

    @Test
    fun `concurrent neural work waits for the serialized lease instead of being skipped`() {
        val budget = NeuralRepairBudget(8, 120_000L)
        val first = requireNotNull(budget.acquire())
        val started = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        var second: NeuralRepairBudget.Lease? = null
        val worker = Thread {
            started.countDown()
            second = budget.acquire()
            acquired.countDown()
        }
        worker.start()

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(acquired.await(50, TimeUnit.MILLISECONDS))
        first.close()
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        requireNotNull(second).close()
        worker.join(1_000L)
    }

    @Test
    fun `caller beyond attempt cap does not wait for the last in flight lease`() {
        val budget = NeuralRepairBudget(1, 120_000L)
        val first = requireNotNull(budget.acquire())

        val started = System.nanoTime()
        val denied = budget.acquire()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(denied == null)
        assertTrue("denial took ${elapsedMillis}ms", elapsedMillis < 250L)
        first.close()
    }

    @Test
    fun `queued neural acquire observes pipeline cancellation`() {
        val budget = NeuralRepairBudget(2, 120_000L)
        val first = requireNotNull(budget.acquire())
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var second: NeuralRepairBudget.Lease? = null
        val worker = Thread {
            started.countDown()
            second = budget.acquire(cancelled::get)
            finished.countDown()
        }
        worker.start()

        assertTrue(started.await(1, TimeUnit.SECONDS))
        cancelled.set(true)
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertTrue(second == null)
        first.close()
        worker.join(1_000L)
    }
}
