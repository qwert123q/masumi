package rs.masumi.core.modelpackage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedPaddleOcrVlTest {
    @Test
    fun `runtime contract uses model default dynamic image budget`() {
        val runtime = PinnedPaddleOcrVl.descriptor.runtime

        assertEquals("cpu", runtime.backend)
        assertEquals("mtmd-cpu-t6-image-default-v3", runtime.buildContract)
        assertTrue("image16" !in runtime.buildContract)
    }
}
