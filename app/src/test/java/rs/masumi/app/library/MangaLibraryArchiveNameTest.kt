package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.core.model.PageRecord

class MangaLibraryArchiveNameTest {
    @Test
    fun `different page ids cannot collide after path sanitization`() {
        val first = page(
            order = 0,
            pageId = "page-a",
            originalName = "chapter/page.jpg",
        )
        val second = page(
            order = 0,
            pageId = "page-b",
            originalName = "chapter\\page.jpg",
        )

        val firstName = mangaArchiveSourceFileName(first)
        val secondName = mangaArchiveSourceFileName(second)

        assertEquals("000001-page-a-chapter_page.jpg", firstName)
        assertEquals("000001-page-b-chapter_page.jpg", secondName)
        assertNotEquals(firstName, secondName)
        assertTrue(firstName.endsWith(".jpg"))
        assertTrue(secondName.endsWith(".jpg"))
    }

    @Test
    fun `long names remain distinct and keep their canonical extension`() {
        val sharedPrefix = "long-name-" + "x".repeat(240)
        val first = page(
            order = 0,
            pageId = "page-c",
            originalName = "${sharedPrefix}-first.jpeg",
        )
        val second = page(
            order = 1,
            pageId = "page-c",
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
                pageId = "page-d",
                originalName = "nested/path-without-extension",
            ),
        )

        assertEquals(
            "000003-page-d-nested_path-without-extension.jpg",
            name,
        )
    }

    private fun page(
        order: Int,
        pageId: String,
        originalName: String,
    ): PageRecord = PageRecord(
        order = order,
        pageId = pageId,
        originalName = originalName,
        mediaType = "image/jpeg",
        byteLength = 128L,
        storedPath = "sources/$pageId.jpg",
    )
}
