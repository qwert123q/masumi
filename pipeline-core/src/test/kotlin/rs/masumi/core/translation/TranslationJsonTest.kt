package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import rs.masumi.core.serialization.TranslationJson

class TranslationJsonTest {
    @Test
    fun `translation input JSON ignores legacy fields and round trips`() {
        val input = PageTranslationInput(
            pageId = "page-1",
            pageOrder = 0,
            ocrPageArtifactKey = "ocr-page-key",
            policy = TranslationPolicy(),
            prompt = TranslationPromptRef(),
            items = listOf(
                TranslationInputItem(
                    translationRegionId = "translation-region-1",
                    ocrRegionId = "ocr-region-1",
                    readingOrderRank = 0,
                    sourceText = "原文",
                    roleHint = TranslationRoleHint.DIALOGUE,
                ),
            ),
            protectedRegions = emptyList(),
        )
        val encoded = TranslationJson().encodePageInput(input)

        assertEquals(input, TranslationJson().decodePageInput(encoded))
        assertEquals(
            input,
            TranslationJson().decodePageInput(encoded.dropLast(2) + ",\"inputGlossarySha256\":\"legacy\"\n}"),
        )
    }

    @Test
    fun `legacy provider host is retained as migration evidence when endpoint is absent`() {
        val codec = TranslationJson()
        val job = TranslationArtifactFixtures.job()
        val encoded = codec.encodeJob(job).replace(
            "\"endpoint\": \"https://example.invalid/v1\"",
            "\"endpointHost\": \"example.invalid\"",
        )

        val decoded = codec.decodeJob(encoded)

        assertEquals(
            "legacy-host:example.invalid",
            decoded.dependencies.provider.reference.endpoint,
        )
    }

    @Test
    fun `translation region identity inherits OCR lineage`() {
        assertEquals("ocr-run.page.0000.region.0001", TranslationIdentity.regionId("ocr-run.page.0000.region.0001"))
    }

    @Test
    fun `batch windows and validated responses round trip strictly`() {
        val window = TranslationFixtures.window(listOf(TranslationFixtures.input("id", 0)))
        val validation = TranslationResponseValidation(
            items = listOf(
                ValidatedTranslationItem(
                    translationRegionId = "id",
                    ocrRegionId = "ocr-id",
                    role = TranslationRole.DIALOGUE,
                    translatedText = "译文",
                    state = TranslationResultState.TRANSLATED,
                ),
            ),
            ignoredResponseIds = emptyList(),
            glossaryUpdates = emptyList(),
            discardedGlossaryEntryCount = 0,
        )
        val codec = TranslationJson()

        assertEquals(window, codec.decodeBatchWindow(codec.encodeBatchWindow(window)))
        assertEquals(validation, codec.decodeResponseValidation(codec.encodeResponseValidation(validation)))
    }

    @Test
    fun `mandatory dialogue narration and automatic approval cannot be disabled`() {
        assertFailsWith<IllegalArgumentException> { TranslationPolicy(translateDialogue = false) }
        assertFailsWith<IllegalArgumentException> { TranslationPolicy(translateNarration = false) }
        assertFailsWith<IllegalArgumentException> { TranslationPolicy(automaticApproval = false) }
    }
}
