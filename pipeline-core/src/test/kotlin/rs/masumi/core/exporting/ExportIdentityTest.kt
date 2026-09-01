package rs.masumi.core.exporting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExportIdentityTest {
    @Test
    fun `output naming remains deterministic and describes the actual image encoding`() {
        assertEquals("0001.png", ExportIdentity.outputName(0, 15, ExportPolicy()))
        assertEquals("10000.png", ExportIdentity.outputName(9_999, 10_000, ExportPolicy()))
        assertEquals("0001.webp", ExportIdentity.outputName(0, 15, ExportPolicy(), "webp"))
        assertFailsWith<IllegalArgumentException> {
            ExportIdentity.outputName(0, 15, ExportPolicy(), "gif")
        }
    }
}
