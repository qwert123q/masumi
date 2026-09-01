package rs.masumi.app.library

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private interface DisposableCacheWrite : Runnable {
    val cacheKey: String
    fun discard()
}

/**
 * Owns cache-write payloads from [submit] until they are written or discarded.
 * Every payload is disposed exactly once on all accepted, rejected, and shutdown paths.
 */
internal class ReaderPageCacheWriteQueue<T : Any>(
    maxPendingWrites: Int,
    threadFactory: ThreadFactory,
    private val write: (key: String, payload: T) -> Unit,
    private val dispose: (T) -> Unit,
) {
    private val submissionLock = Any()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingWrites.also { require(it > 0) }),
        threadFactory,
    )

    fun submit(key: String, payload: T): Boolean = synchronized(submissionLock) {
        val task = CacheWriteTask(key, payload)
        executor.queue.toList()
            .filterIsInstance<DisposableCacheWrite>()
            .filter { queued -> queued.cacheKey == key }
            .forEach { queued ->
                if (executor.remove(queued)) queued.discard()
            }
        while (!executor.isShutdown) {
            try {
                executor.execute(task)
                return@synchronized true
            } catch (_: RejectedExecutionException) {
                if (executor.isShutdown) break
                val displaced = executor.queue.poll() as? DisposableCacheWrite
                if (displaced != null) displaced.discard()
            }
        }
        task.discard()
        false
    }

    fun shutdownNow() {
        executor.shutdownNow().forEach { task ->
            (task as? DisposableCacheWrite)?.discard()
        }
    }

    private inner class CacheWriteTask(
        override val cacheKey: String,
        private val payload: T,
    ) : DisposableCacheWrite {
        private val claimed = AtomicBoolean()

        override fun run() {
            if (!claimed.compareAndSet(false, true)) return
            try {
                runCatching { write(cacheKey, payload) }
            } finally {
                dispose(payload)
            }
        }

        override fun discard() {
            if (claimed.compareAndSet(false, true)) dispose(payload)
        }
    }
}
