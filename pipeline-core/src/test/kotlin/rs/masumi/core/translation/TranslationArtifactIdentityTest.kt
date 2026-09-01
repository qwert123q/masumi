package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationArtifactIdentityTest {
    @Test
    fun `page and window identities are structural children and OCR region lineage is inherited`() {
        assertEquals("translation-run.page.0002", TranslationArtifactIdentity.pageArtifactKey("translation-run", 2))
        assertEquals("translation-run.window.0005", TranslationArtifactIdentity.windowArtifactKey("translation-run", 5))
        assertEquals("ocr-run.page.0002.region.0001", TranslationIdentity.regionId("ocr-run.page.0002.region.0001"))
    }
}
