package rs.masumi.core.modelpackage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedPaddleOcrVlTest {
    @Test
    fun `runtime contract pins normalized Vulkan model and crop adaptive image budget`() {
        val descriptor = PinnedPaddleOcrVl.descriptor
        val runtime = descriptor.runtime

        assertEquals("vulkan-preferred-cpu-fallback", runtime.backend)
        assertEquals("mtmd-vulkan-safe-f16-t6-image-adaptive-v1", runtime.buildContract)
        assertTrue("image16" !in runtime.buildContract)
        assertEquals(OcrModelFileNormalization.GGUF_BF16_TO_F16, descriptor.model.normalization)
        assertEquals(OcrModelFileNormalization.GGUF_BF16_TO_F16, descriptor.projector.normalization)
        assertTrue(descriptor.model.sha256 != descriptor.model.installedSha256)
        assertTrue(descriptor.projector.sha256 != descriptor.projector.installedSha256)
    }
}
