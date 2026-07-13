package rs.masumi.core.importer

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectImporterTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("masumi-importer-test")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `imports ordered pages and reuses duplicate source objects`() {
        val importer = importer(ids = listOf("project-1", "job-1"))

        val outcome = importer.importProject(
            listOf(
                bytes("2.jpg", "same"),
                bytes("1.jpg", "same"),
                bytes("10.png", "other", "image/png"),
            ),
        )

        assertEquals(
            listOf("1.jpg", "2.jpg", "10.png"),
            outcome.manifest.pages.map { it.originalName },
        )
        assertEquals(outcome.manifest.pages[0].pageId, outcome.manifest.pages[1].pageId)
        Files.list(outcome.projectDirectory.resolve("sources")).use { files ->
            assertEquals(2, files.count())
        }
        assertTrue(outcome.projectDirectory.resolve("manifest.json").exists())
        assertTrue(outcome.projectDirectory.resolve("reports/job-1.json").exists())
        assertTrue(outcome.projectDirectory.resolve("reports/job-1.txt").exists())
        assertEquals(3, outcome.report.importedCount)
        assertEquals(13, outcome.report.byteCount)
    }

    private fun importer(ids: List<String>): ProjectImporter = ProjectImporter(
        workspaceRoot = root,
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        idSource = QueueIdSource(ids),
    )

    private fun bytes(
        name: String,
        content: String,
        mediaType: String = "image/jpeg",
    ): SourceCandidate = object : SourceCandidate {
        override val displayName: String = name
        override val mediaType: String = mediaType
        override val isDirectory: Boolean = false

        override fun openStream(): InputStream =
            ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
    }

    private class QueueIdSource(ids: List<String>) : IdSource {
        private val queue = ArrayDeque(ids)

        override fun nextId(): String = queue.removeFirst()
    }
}
