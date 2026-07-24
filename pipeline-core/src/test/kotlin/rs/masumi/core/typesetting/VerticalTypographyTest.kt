package rs.masumi.core.typesetting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VerticalTypographyTest {
    @Test
    fun `loose dots become exactly two compact vertical ellipsis glyphs`() {
        val normalized = VerticalTypography.normalize("露米莉亚小姐. . .！")

        assertEquals("露米莉亚小姐︙︙︕", normalized)
        assertEquals(2, normalized.count { it == '︙' })
        assertFalse(normalized.contains('.'))
        assertFalse(normalized.contains(' '))
    }

    @Test
    fun `vertical punctuation mapping is deterministic`() {
        assertEquals(
            "﹁你好︐回来了吗︖﹂",
            VerticalTypography.normalize("「你好，回来了吗？」"),
        )
    }
}
