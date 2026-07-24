package rs.masumi.app.ocr

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import rs.masumi.core.ocr.OcrExecutionBackend

/**
 * Keeps one successfully initialized OCR engine warm between sequential projects.
 *
 * The cache is process-local: Android can still reclaim the dedicated OCR process,
 * but queued chapters handled by the same process avoid remapping the model and
 * rebuilding its vision/context state for every chapter.
 */
internal class OcrEngineSessionCache(
    private val factory: OcrEngineFactory,
) : AutoCloseable {
    private val lock = Any()
    private var cached: CachedEngine? = null
    private var leased = false
    private var closed = false

    fun open(model: Path, projector: Path): OcrEngine = synchronized(lock) {
        check(!closed) { "OCR engine cache is closed" }
        check(!leased) { "OCR engine cache already has an active lease" }
        val selected = cached
            ?.takeIf { it.model == model && it.projector == projector }
            ?.engine
            ?: factory.open(model, projector).also { opened ->
                cached?.engine?.close()
                cached = CachedEngine(model, projector, opened)
            }
        leased = true
        Lease(selected)
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            if (leased) null else cached?.engine.also { cached = null }
        }
        toClose?.close()
    }

    private fun release(engine: OcrEngine, reusable: Boolean) {
        val toClose = synchronized(lock) {
            if (!leased) return
            leased = false
            if (!reusable || closed || cached?.engine !== engine) {
                if (cached?.engine === engine) cached = null
                engine
            } else {
                null
            }
        }
        toClose?.close()
    }

    private inner class Lease(
        private val engine: OcrEngine,
    ) : OcrEngine {
        private val leaseClosed = AtomicBoolean(false)
        private val reusable = AtomicBoolean(true)

        override val executionBackend: OcrExecutionBackend
            get() = engine.executionBackend

        override fun recognize(
            request: OcrEngineRequest,
            cancellation: () -> Boolean,
        ): OcrEngineResult = try {
            engine.recognize(request, cancellation)
        } catch (failure: Throwable) {
            reusable.set(false)
            throw failure
        }

        override fun cancel() {
            reusable.set(false)
            engine.cancel()
        }

        override fun close() {
            if (leaseClosed.compareAndSet(false, true)) {
                release(engine, reusable.get())
            }
        }
    }

    private data class CachedEngine(
        val model: Path,
        val projector: Path,
        val engine: OcrEngine,
    )
}
