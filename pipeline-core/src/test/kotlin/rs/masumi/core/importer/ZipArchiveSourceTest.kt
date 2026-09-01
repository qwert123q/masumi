package rs.masumi.core.importer

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipArchiveSourceTest {
    @Test
    fun exposesOnlyVisibleSupportedPagesForNaturalSorting() {
        val archive = zip(
            "./chapter/1.webp" to "one",
            "chapter/10.jpg" to "ten",
            "chapter/2.png" to "two",
            "chapter/notes.txt" to "ignored",
            "__MACOSX/._2.png" to "ignored",
        )
        val workspace = Files.createTempDirectory("masumi-archive-import-")
        try {
            ZipArchiveSource.open(archive).use { source ->
                val selected = SourceSelector.select(source.sources)

                assertEquals(
                    listOf("chapter/1.webp", "chapter/2.png", "chapter/10.jpg"),
                    selected.accepted.map { it.source.displayName },
                )
                assertEquals("one", selected.accepted.first().source.openStream().reader().use { it.readText() })

                val ids = ArrayDeque(listOf("archive-project", "archive-job"))
                val outcome = ProjectImporter(
                    workspaceRoot = workspace,
                    idSource = IdSource { ids.removeFirst() },
                ).importProject(source.sources)
                assertEquals(
                    listOf("chapter/1.webp", "chapter/2.png", "chapter/10.jpg"),
                    outcome.manifest.pages.map { it.originalName },
                )
            }
        } finally {
            archive.deleteIfExists()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeArchivePaths() {
        val archive = zip("../outside.jpg" to "unsafe")
        try {
            assertFailsWith<java.io.IOException> {
                ZipArchiveSource.open(archive)
            }
        } finally {
            archive.deleteIfExists()
        }
    }

    private fun zip(vararg entries: Pair<String, String>) =
        Files.createTempFile("masumi-archive-", ".zip").also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { output ->
                entries.forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(content.toByteArray())
                    output.closeEntry()
                }
            }
        }
}
