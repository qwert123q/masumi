package rs.masumi.core.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectionIdentityTest {
    @Test
    fun `page and region identities are structural children of a persisted run`() {
        val page = DetectionIdentity.pageArtifactKey("run-1", 2)

        assertEquals("run-1.page.0002", page)
        assertEquals("run-1.page.0002.region.0007", DetectionIdentity.regionId(page, 7))
    }

    @Test
    fun `structural identities accept legacy opaque parents but reject unsafe values`() {
        assertEquals(
            "${"a".repeat(64)}.page.0000",
            DetectionIdentity.pageArtifactKey("a".repeat(64), 0),
        )
        assertFailsWith<IllegalArgumentException> {
            DetectionIdentity.pageArtifactKey("../run", 0)
        }
    }
}
