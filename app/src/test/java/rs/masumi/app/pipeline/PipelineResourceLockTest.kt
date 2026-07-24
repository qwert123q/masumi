package rs.masumi.app.pipeline

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineResourceLockTest {
    @Test
    fun `only one local pipeline lease can be held`() {
        val workspace = Files.createTempDirectory("masumi-resource-lock")
        try {
            val first = PipelineResourceLease.acquire(workspace) { false }
            assertNotNull(first)
            val cancelled = AtomicBoolean(false)
            val contender = Thread {
                Thread.sleep(50)
                cancelled.set(true)
            }.apply { start() }

            val second = PipelineResourceLease.acquire(workspace, cancelled::get)

            contender.join()
            assertNull(second)
            first?.close()
            assertNotNull(PipelineResourceLease.acquire(workspace) { false }?.also { it.close() })
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
