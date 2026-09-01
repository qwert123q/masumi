package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.serialization.OcrJson

class OcrJsonTest {
    @Test
    fun `OCR JSON ignores legacy fields and round trips terminal region data`() {
        val artifact = OcrFixtures.pageArtifact(
            state = OcrRegionState.RECOGNIZED,
            rawText = "縦書きです",
            normalizedText = "縦書きです",
        )

        val encoded = OcrJson().encodePageArtifact(artifact)

        assertEquals(artifact, OcrJson().decodePageArtifact(encoded))
        assertEquals(true, encoded.contains("\"executionBackend\": \"VULKAN\""))
        assertEquals(
            artifact,
            OcrJson().decodePageArtifact(encoded.dropLast(2) + ",\"sourceSha256\":\"legacy\"\n}"),
        )
    }
}
