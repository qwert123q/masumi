package rs.masumi.app.pipeline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceJanitorTest {
    @Test
    fun sweepDeletesOnlyColdUnreferencedRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.toPath().resolve("janitor-${UUID.randomUUID()}")
        val stageRoot = root.resolve("artifacts/cleanup")
        val stale = FileTime.from(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2), TimeUnit.MILLISECONDS)

        val kept = runDirectory(stageRoot, "a".repeat(64), stale)
        val superseded = runDirectory(stageRoot, "b".repeat(64), stale)
        val inFlight = runDirectory(stageRoot, "c".repeat(64), null)
        val foreign = Files.createDirectories(stageRoot.resolve("not-a-run-key"))

        val cutoff = FileTime.from(
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30),
            TimeUnit.MILLISECONDS,
        )
        val freed = WorkspaceJanitor.sweepStage(stageRoot, setOf("a".repeat(64)), cutoff)

        try {
            assertTrue(Files.isDirectory(kept))
            assertFalse(Files.exists(superseded))
            assertTrue(Files.isDirectory(inFlight))
            assertTrue(Files.isDirectory(foreign))
            assertTrue(freed > 0)
            assertEquals(
                0,
                WorkspaceJanitor.sweepStage(stageRoot, setOf("a".repeat(64)), cutoff),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun runDirectory(stageRoot: Path, runKey: String, mtime: FileTime?): Path {
        val directory = Files.createDirectories(stageRoot.resolve(runKey))
        val pages = Files.createDirectories(directory.resolve("pages/page-1"))
        Files.write(pages.resolve("cleaned.png"), ByteArray(4096))
        if (mtime != null) {
            Files.walk(directory).use { paths ->
                paths.forEach { Files.setLastModifiedTime(it, mtime) }
            }
        }
        return directory
    }
}
