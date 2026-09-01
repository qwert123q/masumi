package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationRecoveryCompatibilityTest {
    @Test
    fun `legacy dependency metadata and window context upgrade without changing persisted identities`() {
        val currentGlossary = listOf(TranslationGlossaryEntry("名前", "名字"))
        val current = TranslationArtifactFixtures.dependencies.copy(
            initialGlossary = currentGlossary,
            provider = TranslationArtifactFixtures.dependencies.provider.copy(
                reference = TranslationArtifactFixtures.dependencies.provider.reference.copy(
                    endpoint = "https://proxy.example/v1/chat/completions",
                ),
            ),
        )
        val legacy = TranslationArtifactFixtures.job().copy(
            status = TranslationJobStatus.RUNNING,
            dependencies = current.copy(
                initialGlossary = emptyList(),
                provider = current.provider.copy(
                    reference = current.provider.reference.copy(endpoint = "legacy-host:proxy.example"),
                ),
            ),
            windows = TranslationArtifactFixtures.job().windows.map {
                it.copy(
                    contextTranslationRegionIds = emptyList(),
                    state = TranslationWindowState.COMMITTED,
                )
            },
            pages = TranslationArtifactFixtures.job().pages.map {
                it.copy(state = TranslationPageState.COMMITTED, artifactPath = "pages/legacy/translation.json")
            },
        )
        val planned = listOf(
            TranslationArtifactFixtures.window.copy(
                contextItems = listOf(TranslationArtifactFixtures.window.items.single()),
            ),
        )

        val upgraded = TranslationRecoveryCompatibility.upgradeLegacyJob(legacy, current, planned)

        requireNotNull(upgraded)
        assertEquals(legacy.jobId, upgraded.jobId)
        assertEquals(legacy.runArtifactKey, upgraded.runArtifactKey)
        assertEquals(legacy.windows.single().windowArtifactKey, upgraded.windows.single().windowArtifactKey)
        assertEquals(current, upgraded.dependencies)
        assertEquals(
            planned.single().contextItems.map { it.input.translationRegionId },
            upgraded.windows.single().contextTranslationRegionIds,
        )
        assertEquals(TranslationPageState.PENDING, upgraded.pages.single().state)
        assertNull(upgraded.pages.single().artifactPath)
    }

    @Test
    fun `legacy job from another endpoint host is not upgraded`() {
        val current = TranslationArtifactFixtures.dependencies.copy(
            provider = TranslationArtifactFixtures.dependencies.provider.copy(
                reference = TranslationArtifactFixtures.dependencies.provider.reference.copy(
                    endpoint = "https://current.example/v1/chat/completions",
                ),
            ),
        )
        val legacy = TranslationArtifactFixtures.job().copy(
            dependencies = current.copy(
                provider = current.provider.copy(
                    reference = current.provider.reference.copy(endpoint = "legacy-host:old.example"),
                ),
            ),
        )

        assertNull(
            TranslationRecoveryCompatibility.upgradeLegacyJob(
                legacy,
                current,
                listOf(TranslationArtifactFixtures.window),
            ),
        )
    }

    @Test
    fun `legacy job using another model is not upgraded`() {
        val current = TranslationArtifactFixtures.dependencies.copy(
            provider = TranslationArtifactFixtures.dependencies.provider.copy(
                modelId = "current-model",
                reference = TranslationArtifactFixtures.dependencies.provider.reference.copy(
                    endpoint = "https://same.example/v1/chat/completions",
                ),
            ),
        )
        val legacy = TranslationArtifactFixtures.job().copy(
            dependencies = current.copy(
                provider = current.provider.copy(
                    modelId = "old-model",
                    reference = current.provider.reference.copy(endpoint = "legacy-host:same.example"),
                ),
            ),
        )

        assertNull(
            TranslationRecoveryCompatibility.upgradeLegacyJob(
                legacy,
                current,
                listOf(TranslationArtifactFixtures.window),
            ),
        )
    }
}
