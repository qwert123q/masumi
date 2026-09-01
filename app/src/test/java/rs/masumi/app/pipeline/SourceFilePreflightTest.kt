package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import rs.masumi.core.model.PageRecord

class SourceFilePreflightTest {
    @Test
    fun `resolves safe regular source using recorded length without rereading content`() {
        withProjectDirectory { projectDirectory ->
            val source = projectDirectory.resolve("sources/page.bin")
            Files.createDirectories(source.parent)
            Files.write(source, byteArrayOf(9, 8, 7, 6))
            val page = page(
                storedPath = "sources/page.bin",
                byteLength = 4L,
            )

            val resolved = SourceFilePreflight.resolve(projectDirectory, page)

            assertEquals(source, resolved)
        }
    }

    @Test
    fun `rejects absolute or escaping source paths`() {
        withProjectDirectory { projectDirectory ->
            val outside = Files.createTempFile(projectDirectory.parent, "outside-", ".bin")
            try {
                listOf(outside.toString(), "../${outside.fileName}").forEach { storedPath ->
                    assertThrows(IllegalArgumentException::class.java) {
                        SourceFilePreflight.resolve(
                            projectDirectory,
                            page(storedPath = storedPath, byteLength = Files.size(outside)),
                        )
                    }
                }
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun `rejects missing paths and directories as non regular sources`() {
        withProjectDirectory { projectDirectory ->
            val directory = projectDirectory.resolve("sources")
            Files.createDirectories(directory)

            listOf("sources/missing.bin", "sources").forEach { storedPath ->
                val failure = assertThrows(SourceFilePreflightException::class.java) {
                    SourceFilePreflight.resolve(
                        projectDirectory,
                        page(storedPath = storedPath, byteLength = 0L),
                    )
                }

                assertEquals("SOURCE_MISSING", failure.code)
            }
        }
    }

    @Test
    fun `rejects a source whose recorded byte length differs`() {
        withProjectDirectory { projectDirectory ->
            val source = projectDirectory.resolve("sources/page.bin")
            Files.createDirectories(source.parent)
            Files.write(source, byteArrayOf(1, 2, 3, 4))

            val failure = assertThrows(SourceFilePreflightException::class.java) {
                SourceFilePreflight.resolve(
                    projectDirectory,
                    page(storedPath = "sources/page.bin", byteLength = 3L),
                )
            }

            assertEquals("SOURCE_LENGTH_MISMATCH", failure.code)
        }
    }

    private fun page(
        storedPath: String,
        byteLength: Long,
    ): PageRecord = PageRecord(
        order = 0,
        pageId = "page-1",
        originalName = "page.bin",
        mediaType = "application/octet-stream",
        byteLength = byteLength,
        storedPath = storedPath,
    )

    private fun withProjectDirectory(block: (Path) -> Unit) {
        val projectDirectory = Files.createTempDirectory("source-preflight-")
        try {
            block(projectDirectory)
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }
}
