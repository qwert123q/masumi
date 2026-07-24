package rs.masumi.app.pipeline

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class PipelineResourceLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(
            workspaceRoot: Path,
            cancellation: () -> Boolean,
        ): PipelineResourceLease? {
            val runtime = workspaceRoot.toAbsolutePath().normalize().resolve("runtime")
            java.nio.file.Files.createDirectories(runtime)
            val channel = FileChannel.open(
                runtime.resolve("local-pipeline.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            while (!cancellation()) {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return PipelineResourceLease(channel, lock)
                try {
                    Thread.sleep(RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            channel.close()
            return null
        }

        private const val RETRY_MILLIS = 250L
    }
}
