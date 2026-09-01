package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.masumi.core.model.PageRecord

class LegacyArchiveSourceSelectionTest {
    @Test
    fun `exact unclaimed legacy content is reused`() {
        assertEquals(
            LegacyArchiveSourceAction.REUSE_LEGACY,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                expectedSha256 = "a".repeat(64),
                legacyByteLength = 128L,
                legacySha256 = "a".repeat(64),
                legacyAlreadyClaimed = false,
            ),
        )
    }

    @Test
    fun `same-length legacy content with another digest writes the stable name`() {
        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                expectedSha256 = "a".repeat(64),
                legacyByteLength = 128L,
                legacySha256 = "b".repeat(64),
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
                expectedSha256 = "a".repeat(64),
                legacyByteLength = 127L,
                legacySha256 = "a".repeat(64),
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
                expectedSha256 = "a".repeat(64),
                legacyByteLength = 128L,
                legacySha256 = "a".repeat(64),
                legacyAlreadyClaimed = true,
            ),
        )
    }

    @Test
    fun `legacy content with an unavailable digest writes the stable name`() {
        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                expectedSha256 = "a".repeat(64),
                legacyByteLength = 128L,
                legacySha256 = null,
                legacyAlreadyClaimed = false,
            ),
        )
    }

    @Test
    fun `legacy name reserved for another page stable name is never reused`() {
        val digest = "a".repeat(64)

        assertEquals(
            LegacyArchiveSourceAction.WRITE_STABLE,
            selectLegacyArchiveSourceAction(
                expectedByteLength = 128L,
                expectedSha256 = digest,
                legacyByteLength = 128L,
                legacySha256 = digest,
                legacyAlreadyClaimed = false,
                legacyNameReservedForStablePage = true,
            ),
        )
    }

    @Test
    fun `occupied stable name has a deterministic alternate`() {
        val digest = "a".repeat(64)
        val page = PageRecord(
            order = 0,
            pageId = digest,
            sourceSha256 = digest,
            originalName = "page.jpg",
            mediaType = "image/jpeg",
            byteLength = 128L,
            storedPath = "sources/$digest.jpg",
        )
        val alternate = mangaArchiveAlternativeSourceFileName(page, 2)

        assertEquals("000001-$digest-page~2.jpg", alternate)
    }

    @Test
    fun `same-length stable candidate with different content is rejected`() {
        val reusable = isReusableArchiveSourceCandidate(
            expectedByteLength = 128L,
            expectedSha256 = "a".repeat(64),
            candidateByteLength = 128L,
            candidateSha256 = "b".repeat(64),
            candidateAlreadyClaimed = false,
            candidateNameReservedForAnotherPage = false,
        )

        assertEquals(false, reusable)
    }

    @Test
    fun `different-length stable candidate is rejected`() {
        val digest = "a".repeat(64)
        val reusable = isReusableArchiveSourceCandidate(
            expectedByteLength = 128L,
            expectedSha256 = digest,
            candidateByteLength = 127L,
            candidateSha256 = digest,
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
            sourceSha256 = "a".repeat(64),
            originalName = "chapter/page.jpeg",
            mediaType = "image/jpeg",
            byteLength = 128L,
            storedPath = "sources/${"a".repeat(64)}.jpg",
        )

        assertEquals("chapter_page.jpeg", legacyMangaArchiveSourceFileName(page))
    }
}
