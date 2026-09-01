package rs.masumi.app.pipeline

import java.util.concurrent.atomic.AtomicInteger

/** Process-local activity signal; it dies with the UI process and cannot leave a stale pause behind. */
internal object ReaderForegroundState {
    private val readers = AtomicInteger()

    fun enter() {
        readers.incrementAndGet()
    }

    fun exit() {
        readers.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    fun isForeground(): Boolean = readers.get() > 0
}
