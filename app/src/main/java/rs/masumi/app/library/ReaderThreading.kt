package rs.masumi.app.library

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Reader decode work is interactive work, unlike background pipeline stages. */
internal object ReaderThreading {
    fun factory(namePrefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { task -> Thread(task, "$namePrefix-${sequence.incrementAndGet()}") }
    }

    fun lowPriorityFactory(namePrefix: String): ThreadFactory {
        val base = factory(namePrefix)
        return ThreadFactory { task ->
            base.newThread(task).apply { priority = Thread.MIN_PRIORITY }
        }
    }

    fun decodeProfile(
        viewportWidth: Int,
        maximumPixels: Long,
        detailWidthMultiplier: Float,
    ): String = "reader-v2-w${viewportWidth.coerceAtLeast(1)}-p$maximumPixels-d$detailWidthMultiplier"
}
