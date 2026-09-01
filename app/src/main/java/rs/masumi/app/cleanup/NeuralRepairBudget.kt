package rs.masumi.app.cleanup

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * One run-wide gate for the serialized neural inpainter.
 *
 * The elapsed limit is cooperative: callers and ONNX run options observe it,
 * but model loading and native session creation are not a process-level hard
 * deadline. Device reports therefore record observed time as well as the cap.
 */
class NeuralRepairBudget(
    private val maximumAttempts: Int,
    private val maximumTotalMillis: Long,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var attemptCount = 0
    private var elapsedMillis = 0L
    private var inFlight = false
    private val lock = ReentrantLock(true)
    private val available = lock.newCondition()

    init {
        require(maximumAttempts >= 0)
        require(maximumTotalMillis >= 0L)
    }

    fun acquire(shouldCancel: () -> Boolean = { false }): Lease? = lock.withLock {
        // Attempts are reserved on acquire, so a caller beyond the cap can
        // fail immediately instead of waiting for an in-flight final attempt.
        if (attemptCount >= maximumAttempts || elapsedMillis >= maximumTotalMillis) return null
        while (inFlight) {
            if (runCatching(shouldCancel).getOrDefault(true)) return null
            try {
                available.await(ACQUIRE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (runCatching(shouldCancel).getOrDefault(true)) return null
        if (attemptCount >= maximumAttempts || elapsedMillis >= maximumTotalMillis) return null
        val startedAt = clockMillis()
        val remaining = (maximumTotalMillis - elapsedMillis).coerceAtLeast(0L)
        inFlight = true
        attemptCount += 1
        Lease(startedAt, startedAt.saturatingAdd(remaining))
    }

    inner class Lease internal constructor(
        private val startedAtMillis: Long,
        private val deadlineMillis: Long,
    ) : NeuralRunControl, AutoCloseable {
        private var closed = false
        var elapsedMillis: Long = 0L
            private set

        override fun shouldStop(): Boolean = remainingMillis() <= 0L

        override fun remainingMillis(): Long =
            (deadlineMillis - clockMillis()).coerceAtLeast(0L)

        override fun close() {
            lock.withLock {
                if (closed) return
                elapsedMillis = (clockMillis() - startedAtMillis).coerceAtLeast(0L)
                this@NeuralRepairBudget.elapsedMillis += elapsedMillis
                inFlight = false
                closed = true
                available.signalAll()
            }
        }
    }

    private fun Long.saturatingAdd(value: Long): Long =
        if (value > Long.MAX_VALUE - this) Long.MAX_VALUE else this + value

    private companion object {
        const val ACQUIRE_POLL_MILLIS = 25L
    }
}
