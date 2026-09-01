package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrIdentityTest {
    @Test
    fun `page and consolidated region identities use persisted structural order`() {
        val page = OcrIdentity.pageArtifactKey("ocr-run", 4)

        assertEquals("ocr-run.page.0004", page)
        assertEquals("ocr-run.page.0004.region.0003", OcrIdentity.regionId(page, 3))
    }
}
