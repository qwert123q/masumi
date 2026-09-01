package rs.masumi.app.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageCacheWriteQueueTest {
    @Test
    fun `blocked cache encodes never occupy visible page decode workers`() {
        val encodeStarted = CountDownLatch(1)
        val releaseEncode = CountDownLatch(1)
        val visiblePageDecoded = CountDownLatch(1)
        val pageExecutor = Executors.newFixedThreadPool(2)
        val cacheWrites = ReaderPageCacheWriteQueue<String>(
            maxPendingWrites = 1,
            threadFactory = ReaderThreading.factory("cache-write-test"),
            write = { _, _ ->
                encodeStarted.countDown()
                releaseEncode.await(5, TimeUnit.SECONDS)
            },
            dispose = {},
        )

        try {
            pageExecutor.execute { cacheWrites.submit("page-1", "bitmap-1") }
            assertTrue("the first cache encode did not start", encodeStarted.await(2, TimeUnit.SECONDS))
            pageExecutor.execute { cacheWrites.submit("page-2", "bitmap-2") }

            pageExecutor.execute { visiblePageDecoded.countDown() }

            assertTrue(
                "cache encoding occupied both visible-page decode workers",
                visiblePageDecoded.await(2, TimeUnit.SECONDS),
            )
        } finally {
            releaseEncode.countDown()
            pageExecutor.shutdownNow()
            cacheWrites.shutdownNow()
        }
    }

    @Test
    fun `full queue discards the oldest pending write and keeps the newest`() {
        val firstEncodeStarted = CountDownLatch(1)
        val releaseFirstEncode = CountDownLatch(1)
        val writesFinished = CountDownLatch(2)
        val disposed = CopyOnWriteArrayList<String>()
        val written = CopyOnWriteArrayList<String>()
        val cacheWrites = ReaderPageCacheWriteQueue<String>(
            maxPendingWrites = 1,
            threadFactory = ReaderThreading.factory("cache-write-test"),
            write = { _, payload ->
                written += payload
                if (payload == "bitmap-1") {
                    firstEncodeStarted.countDown()
                    releaseFirstEncode.await(5, TimeUnit.SECONDS)
                }
                writesFinished.countDown()
            },
            dispose = disposed::add,
        )

        try {
            assertTrue(cacheWrites.submit("page-1", "bitmap-1"))
            assertTrue("the first cache encode did not start", firstEncodeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(cacheWrites.submit("page-2", "bitmap-2-old"))

            assertTrue("the newest pending write was discarded", cacheWrites.submit("page-3", "bitmap-3-new"))

            releaseFirstEncode.countDown()
            assertTrue("accepted cache writes did not finish", writesFinished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("bitmap-1", "bitmap-3-new"), written)
            assertTrue("the superseded payload was not disposed", disposed.contains("bitmap-2-old"))
        } finally {
            releaseFirstEncode.countDown()
            cacheWrites.shutdownNow()
        }
    }

    @Test
    fun `new pending write replaces an older write for the same cache key`() {
        val firstEncodeStarted = CountDownLatch(1)
        val releaseFirstEncode = CountDownLatch(1)
        val expectedWritesFinished = CountDownLatch(2)
        val disposed = CopyOnWriteArrayList<String>()
        val written = CopyOnWriteArrayList<String>()
        val cacheWrites = ReaderPageCacheWriteQueue<String>(
            maxPendingWrites = 2,
            threadFactory = ReaderThreading.factory("cache-write-test"),
            write = { _, payload ->
                written += payload
                if (payload == "bitmap-1") {
                    firstEncodeStarted.countDown()
                    releaseFirstEncode.await(5, TimeUnit.SECONDS)
                }
                if (payload != "bitmap-2-old") expectedWritesFinished.countDown()
            },
            dispose = disposed::add,
        )

        try {
            assertTrue(cacheWrites.submit("page-1", "bitmap-1"))
            assertTrue("the first cache encode did not start", firstEncodeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(cacheWrites.submit("page-2", "bitmap-2-old"))
            assertTrue(cacheWrites.submit("page-2", "bitmap-2-new"))

            releaseFirstEncode.countDown()
            assertTrue("the replacement write did not finish", expectedWritesFinished.await(2, TimeUnit.SECONDS))
            assertFalse("the superseded cache payload was still encoded", written.contains("bitmap-2-old"))
            assertTrue("the superseded payload was not disposed", disposed.contains("bitmap-2-old"))
        } finally {
            releaseFirstEncode.countDown()
            cacheWrites.shutdownNow()
        }
    }
}
