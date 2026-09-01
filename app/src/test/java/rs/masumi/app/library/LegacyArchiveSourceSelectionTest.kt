package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.masumi.core.model.PageRecord

class LegacyArchiveSourceSelectionTest {
    @Test
    fun `unique unclaimed legacy file with the expected length is reused`() {
        assertEquals(
            LegacyArchiveSourceAction.REUSE_LEGACY,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                legacyByteLength = 128L,
                legacyAlreadyClaimed = false,
            ),
        )
    }

    @Test
    fun `legacy content with another length writes the stable name`() {
        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                legacyByteLength = 127L,
                legacyAlreadyClaimed = false,
            ),
        )
    }

    @Test
    fun `legacy content already claimed by another page writes the stable name`() {
        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                legacyByteLength = 128L,
                legacyAlreadyClaimed = true,
            ),
        )
    }

    @Test
    fun `legacy name reserved for another page stable name is never reused`() {
        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                legacyByteLength = 128L,
                legacyAlreadyClaimed = false,
                legacyNameReservedForStablePage = true,
            ),
        )
    }

    @Test
    fun `occupied stable name has a deterministic alternate`() {
        val page = PageRecord(
            order = 0,
            pageId = "page-a",
            originalName = "page.jpg",
            mediaType = "image/jpeg",
            byteLength = 128L,
            storedPath = "sources/page-a.jpg",
        )
        val alternate = mangaArchiveAlternativeSourceFileName(page, 2)

        assertEquals("000001-page-a-page~2.jpg", alternate)
    }

    @Test
    fun `same-length stable candidate is reusable without reading content`() {
        val reusable = isReusableArchiveSourceCandidate(
            expectedByteLength = 128L,
            candidateByteLength = 128L,
            candidateAlreadyClaimed = false,
            candidateNameReservedForAnotherPage = false,
        )

        assertEquals(true, reusable)
    }

    @Test
    fun `different-length stable candidate is rejected`() {
        val reusable = isReusableArchiveSourceCandidate(
            expectedByteLength = 128L,
            candidateByteLength = 127L,
            candidateAlreadyClaimed = false,
            candidateNameReservedForAnotherPage = false,
        )

        assertEquals(false, reusable)
    }

    @Test
    fun `legacy candidate name matches the previous archive format`() {
        val page = PageRecord(
            order = 0,
            pageId = "a".repeat(64),
            originalName = "chapter/page.jpeg",
            mediaType = "image/jpeg",
            byteLength = 128L,
            storedPath = "sources/${"a".repeat(64)}.jpg",
        )

        assertEquals("chapter_page.jpeg", legacyMangaArchiveSourceFileName(page))
    }
}
