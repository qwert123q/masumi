package rs.masumi.app.translation

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import rs.masumi.core.translation.TranslationBatchItem
import rs.masumi.core.translation.TranslationBatchWindow
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationInputItem
import rs.masumi.core.translation.TranslationModelItem
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptMessages
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationResultState
import rs.masumi.core.translation.TranslationRole
import rs.masumi.core.translation.TranslationRoleHint
import rs.masumi.core.translation.TranslationWindowArtifact

class TranslationWindowFailFastTest {
    @Test
    fun `glossary provider failure stops before the translation request`() {
        val provider = ScriptedProvider(
            Failure(TranslationProviderErrorCode.NETWORK),
            Success(translatedResponse()),
        )

        val failure = captureFailure { executeWindow(provider) }

        assertEquals("NETWORK", failure.message)
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `main malformed response stops without batch salvage`() {
        val provider = ScriptedProvider(
            Success(TranslationModelResponse(items = emptyList())),
            Failure(TranslationProviderErrorCode.MALFORMED_RESPONSE),
            Success(translatedResponse()),
        )

        val failure = captureFailure { executeWindow(provider) }

        assertEquals("MALFORMED_RESPONSE", failure.message)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `missing item stops without an isolated protocol retry`() {
        val provider = ScriptedProvider(
            Success(TranslationModelResponse(items = emptyList())),
            Success(TranslationModelResponse(items = emptyList())),
            Success(translatedResponse()),
        )

        val failure = captureFailure { executeWindow(provider) }

        assertEquals("INCOMPLETE_TRANSLATION_RESPONSE", failure.message)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `protocol failure stops before repairing a different semantic item`() {
        val provider = ScriptedProvider(
            Success(TranslationModelResponse(items = emptyList())),
            Success(
                TranslationModelResponse(
                    items = listOf(
                        TranslationModelItem(ITEM_IDS[0], TranslationRole.DIALOGUE, SOURCE_TEXT),
                    ),
                ),
            ),
            Success(translatedResponse()),
        )

        val failure = captureFailure {
            executeWindow(provider, sourceTexts = listOf(SOURCE_TEXT, "大丈夫です"))
        }

        assertEquals("INCOMPLETE_TRANSLATION_RESPONSE", failure.message)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `provider failure during the one quality repair stops immediately`() {
        val provider = ScriptedProvider(
            Success(TranslationModelResponse(items = emptyList())),
            Success(translatedResponse(SOURCE_TEXT)),
            Failure(TranslationProviderErrorCode.TIMEOUT),
        )

        val failure = captureFailure { executeWindow(provider) }

        assertEquals("TIMEOUT", failure.message)
        assertEquals(3, provider.callCount)
    }

    @Test
    fun `source echo still gets exactly one isolated quality repair`() {
        val provider = ScriptedProvider(
            Success(TranslationModelResponse(items = emptyList())),
            Success(translatedResponse(SOURCE_TEXT)),
            Success(translatedResponse("今天")),
        )

        val artifact = executeWindow(provider)

        assertEquals(3, provider.callCount)
        assertEquals(TranslationResultState.TRANSLATED, artifact.items.single().state)
        assertEquals("今天", artifact.items.single().translatedText)
    }

    private fun executeWindow(
        provider: TranslationProvider,
        sourceTexts: List<String> = listOf(SOURCE_TEXT),
    ): TranslationWindowArtifact {
        val workspace = Files.createTempDirectory("masumi-window-fail-fast")
        return try {
            TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
            ).executeWindow(
                window = window(sourceTexts),
                windowKey = "d".repeat(64),
                inputGlossary = emptyList(),
                settings = TranslationProviderSettings(
                    apiUrl = "https://example.invalid/v1",
                    apiKey = "test-secret",
                    model = "test-model",
                ),
                cancellation = { false },
            )
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun window(sourceTexts: List<String>) = TranslationBatchWindow(
        windowIndex = 0,
        policy = TranslationPolicy(),
        prompt = TranslationPromptRef(),
        batching = TranslationBatchingConfig(),
        glossary = emptyList(),
        contextItems = emptyList(),
        items = sourceTexts.mapIndexed { index, sourceText ->
            TranslationBatchItem(
                pageId = "a".repeat(64),
                pageOrder = 0,
                ocrPageArtifactKey = "b".repeat(64),
                input = TranslationInputItem(
                    translationRegionId = ITEM_IDS[index],
                    ocrRegionId = index.toString(16).repeat(64),
                    readingOrderRank = index,
                    sourceText = sourceText,
                    roleHint = TranslationRoleHint.DIALOGUE,
                ),
            )
        },
        estimatedInputTokens = 1,
        exceedsBudget = false,
    )

    private fun translatedResponse(text: String = "今天") = TranslationModelResponse(
        items = listOf(TranslationModelItem(ITEM_IDS[0], TranslationRole.DIALOGUE, text)),
    )

    private fun captureFailure(block: () -> Unit): RuntimeException = try {
        block()
        fail("Expected translation window failure")
        throw AssertionError("unreachable")
    } catch (failure: RuntimeException) {
        failure
    }

    private sealed interface Outcome

    private data class Success(val response: TranslationModelResponse) : Outcome

    private data class Failure(val code: TranslationProviderErrorCode) : Outcome

    private class ScriptedProvider(vararg outcomes: Outcome) : TranslationProvider {
        private val remaining = ArrayDeque(outcomes.toList())
        var callCount: Int = 0
            private set

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                callCount += 1
                return when (val outcome = remaining.removeFirst()) {
                    is Failure -> throw TranslationProviderException(
                        code = outcome.code,
                        attemptCount = 1,
                    )
                    is Success -> TranslationProviderResult(
                        response = outcome.response,
                        usage = null,
                        modelId = "test-model",
                        attemptCount = 1,
                        durationMillis = 1L,
                    )
                }
            }

            override fun cancel() = Unit
        }
    }

    private companion object {
        const val SOURCE_TEXT = "今日は"
        val ITEM_IDS = listOf("e".repeat(64), "f".repeat(64))
    }
}
