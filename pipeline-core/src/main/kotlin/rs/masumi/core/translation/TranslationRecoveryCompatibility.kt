package rs.masumi.core.translation

import java.net.URI

/**
 * Upgrades the last pre-opaque-metadata translation journal in memory. The old
 * journal retained provider host evidence and all translated window identities,
 * but it could not retain the exact endpoint, initial glossary entries, or
 * context lineage without their removed content summaries.
 */
object TranslationRecoveryCompatibility {
    fun upgradeLegacyJob(
        candidate: TranslationJobRecord,
        currentDependencies: TranslationDependencies,
        plannedWindows: List<TranslationBatchWindow>,
    ): TranslationJobRecord? {
        val legacyEndpoint = candidate.dependencies.provider.reference.endpoint
        if (!legacyEndpoint.startsWith(LEGACY_HOST_PREFIX)) return null
        val legacyHost = legacyEndpoint.removePrefix(LEGACY_HOST_PREFIX).normalizedHost() ?: return null
        val currentHost = currentDependencies.provider.reference.endpoint.endpointHost() ?: return null
        if (legacyHost != currentHost) return null
        if (
            candidate.dependencies.provider.copy(reference = currentDependencies.provider.reference) !=
            currentDependencies.provider
        ) {
            return null
        }

        val upgradedDependencies = candidate.dependencies.copy(
            provider = currentDependencies.provider,
            initialGlossary = currentDependencies.initialGlossary,
        )
        if (upgradedDependencies != currentDependencies) return null
        if (candidate.windows.size != plannedWindows.size) return null

        val plannedByIndex = plannedWindows.associateBy(TranslationBatchWindow::windowIndex)
        if (plannedByIndex.size != plannedWindows.size) return null
        val upgradedWindows = candidate.windows.map { checkpoint ->
            val planned = plannedByIndex[checkpoint.windowIndex] ?: return null
            val plannedRegionIds = planned.items.map { item -> item.input.translationRegionId }
            if (checkpoint.translationRegionIds != plannedRegionIds) return null
            checkpoint.copy(
                contextTranslationRegionIds = planned.contextItems.map { item ->
                    item.input.translationRegionId
                },
            )
        }

        return candidate.copy(
            dependencies = currentDependencies,
            windows = upgradedWindows,
            pages = candidate.pages.map { page ->
                page.copy(
                    state = TranslationPageState.PENDING,
                    artifactPath = null,
                    error = null,
                )
            },
        )
    }

    private fun String.endpointHost(): String? = runCatching { URI(this).host }
        .getOrNull()
        .normalizedHost()

    private fun String?.normalizedHost(): String? = this
        ?.trim()
        ?.trimEnd('.')
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

    const val LEGACY_HOST_PREFIX = "legacy-host:"
}
