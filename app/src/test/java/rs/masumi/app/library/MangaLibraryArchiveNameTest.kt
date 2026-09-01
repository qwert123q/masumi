package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.core.model.PageRecord

class MangaLibraryArchiveNameTest {
    @Test
    fun `same-length different content cannot collide after path sanitization`() {
        val first = page(
            order = 0,
            sourceSha256 = "a".repeat(64),
            originalName = "chapter/page.jpg",
        )
        val second = page(
            order = 0,
            sourceSha256 = "b".repeat(64),
            originalName = "chapter\\page.jpg",
        )

        val firstName = mangaArchiveSourceFileName(first)
        val secondName = mangaArchiveSourceFileName(second)

        assertEquals("000001-${"a".repeat(64)}-chapter_page.jpg", firstName)
        assertEquals("000001-${"b".repeat(64)}-chapter_page.jpg", secondName)
        assertNotEquals(firstName, secondName)
        assertTrue(firstName.endsWith(".jpg"))
        assertTrue(secondName.endsWith(".jpg"))
    }

    @Test
    fun `long names remain distinct and keep their canonical extension`() {
        val sharedPrefix = "long-name-" + "x".repeat(240)
        val first = page(
            order = 0,
            sourceSha256 = "c".repeat(64),
            originalName = "${sharedPrefix}-first.jpeg",
        )
        val second = page(
            order = 1,
            sourceSha256 = "c".repeat(64),
            originalName = "${sharedPrefix}-second.jpeg",
        )

        val firstName = mangaArchiveSourceFileName(first)
        val secondName = mangaArchiveSourceFileName(second)

        assertNotEquals(firstName, secondName)
        assertTrue(firstName.length <= 180)
        assertTrue(secondName.length <= 180)
        assertTrue(firstName.endsWith(".jpg"))
        assertTrue(secondName.endsWith(".jpg"))
    }

    @Test
    fun `extensionless source keeps the sanitized stem`() {
        val name = mangaArchiveSourceFileName(
            page(
                order = 2,
                sourceSha256 = "d".repeat(64),
                originalName = "nested/path-without-extension",
            ),
        )

        assertEquals(
            "000003-${"d".repeat(64)}-nested_path-without-extension.jpg",
            name,
        )
    }

    private fun page(
        order: Int,
        sourceSha256: String,
        originalName: String,
    ): PageRecord = PageRecord(
        order = order,
        pageId = sourceSha256,
        sourceSha256 = sourceSha256,
        originalName = originalName,
        mediaType = "image/jpeg",
        byteLength = 128L,
        storedPath = "sources/$sourceSha256.jpg",
    )
}
