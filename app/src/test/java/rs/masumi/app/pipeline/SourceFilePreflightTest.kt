package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import rs.masumi.core.model.PageRecord

class SourceFilePreflightTest {
    @Test
    fun `resolves safe regular source with recorded length without checking content hash`() {
        withProjectDirectory { projectDirectory ->
            val source = projectDirectory.resolve("sources/page.bin")
            Files.createDirectories(source.parent)
            Files.write(source, byteArrayOf(9, 8, 7, 6))
            val page = page(
                storedPath = "sources/page.bin",
                byteLength = 4L,
                // SHA-256 of the different, same-length byte sequence [1, 2, 3, 4].
                sourceSha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
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
        sourceSha256: String = "b".repeat(64),
    ): PageRecord = PageRecord(
        order = 0,
        pageId = sourceSha256,
        sourceSha256 = sourceSha256,
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
