package rs.masumi.core.ocr

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import rs.masumi.core.serialization.OcrJson

class OcrJsonTest {
    @Test
    fun `OCR JSON is strict and round trips terminal region data`() {
        val artifact = OcrFixtures.pageArtifact(
            state = OcrRegionState.RECOGNIZED,
            rawText = "縦書きです",
            normalizedText = "縦書きです",
        )

        val encoded = OcrJson().encodePageArtifact(artifact)

        assertEquals(artifact, OcrJson().decodePageArtifact(encoded))
        assertFailsWith<SerializationException> {
            OcrJson().decodePageArtifact(encoded.dropLast(2) + ",\"unexpected\":true\n}")
        }
    }
}
