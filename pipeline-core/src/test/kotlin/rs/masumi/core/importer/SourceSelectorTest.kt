package rs.masumi.core.importer

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceSelectorTest {
    @Test
    fun `selects supported direct files in natural order`() {
        val result = SourceSelector.select(
            listOf(
                candidate("10.jpg", "image/jpeg"),
                candidate("2.PNG", "image/png"),
                candidate(".hidden.webp", "image/webp"),
                candidate("notes.txt", "text/plain"),
                candidate("chapter", null, isDirectory = true),
                candidate("01.webp", "application/octet-stream"),
            ),
        )

        assertEquals(
            listOf("01.webp", "2.PNG", "10.jpg"),
            result.accepted.map { it.source.displayName },
        )
        assertEquals(3, result.skippedCount)
    }

    private fun candidate(
        name: String,
        mediaType: String?,
        isDirectory: Boolean = false,
    ): SourceCandidate = object : SourceCandidate {
        override val displayName: String = name
        override val mediaType: String? = mediaType
        override val isDirectory: Boolean = isDirectory

        override fun openStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    }
}
