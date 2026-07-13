package rs.masumi.core.importer

import java.io.ByteArrayInputStream
import java.io.IOException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rs.masumi.core.model.ImportErrorCode
import rs.masumi.core.model.ImportStatus
import rs.masumi.core.serialization.ProjectJson

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

    @Test
    fun `failed source read publishes no project and writes sanitized reports`() {
        val importer = importer(ids = listOf("project-1", "job-1"))

        val error = assertFailsWith<ProjectImportException> {
            importer.importProject(listOf(failing("1.jpg")))
        }

        assertEquals(ImportErrorCode.IMPORT_IO_FAILED, error.code)
        assertFalse(root.resolve("projects/project-1").exists())
        assertFalse(root.resolve("staging/project-1").exists())
        val reportFile = root.resolve("failed-reports/job-1.json")
        val textReportFile = root.resolve("failed-reports/job-1.txt")
        assertTrue(reportFile.exists())
        assertTrue(textReportFile.exists())
        val reportContent = Files.readString(reportFile)
        assertFalse(reportContent.contains(root.toString()))
        assertFalse(Files.readString(textReportFile).contains(root.toString()))
        val report = ProjectJson().decodeReport(reportContent)
        assertEquals(ImportStatus.FAILED, report.status)
        assertEquals(ImportErrorCode.IMPORT_IO_FAILED, report.error?.code)
        assertEquals(0, report.importedCount)
    }

    @Test
    fun `empty supported selection fails with a stable error code`() {
        val importer = importer(ids = listOf("project-1", "job-1"))

        val error = assertFailsWith<ProjectImportException> {
            importer.importProject(listOf(bytes("notes.txt", "text", "text/plain")))
        }

        assertEquals(ImportErrorCode.NO_SUPPORTED_PAGES, error.code)
        assertFalse(root.resolve("projects/project-1").exists())
        val report = ProjectJson().decodeReport(
            Files.readString(root.resolve("failed-reports/job-1.json")),
        )
        assertEquals(1, report.discoveredCount)
        assertEquals(1, report.skippedCount)
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

    private fun failing(name: String): SourceCandidate = object : SourceCandidate {
        override val displayName: String = name
        override val mediaType: String = "image/jpeg"
        override val isDirectory: Boolean = false

        override fun openStream(): InputStream = object : InputStream() {
            private var firstRead = true

            override fun read(): Int {
                if (firstRead) {
                    firstRead = false
                    return 'x'.code
                }
                throw IOException("Read failed at ${root.resolve("private-source.jpg")}")
            }
        }
    }

    private class QueueIdSource(ids: List<String>) : IdSource {
        private val queue = ArrayDeque(ids)

        override fun nextId(): String = queue.removeFirst()
    }
}
