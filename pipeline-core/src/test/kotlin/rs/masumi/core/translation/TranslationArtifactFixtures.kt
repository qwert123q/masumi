package rs.masumi.core.translation

internal object TranslationArtifactFixtures {
    val initialGlossary = emptyList<TranslationGlossaryEntry>()
    val dependencies = TranslationDependencies(
        ocrRunArtifactKey = "a".repeat(64),
        policy = TranslationPolicy(),
        prompt = TranslationPromptRef(),
        batching = TranslationBatchingConfig(),
        provider = TranslationProviderDependency(
            modelId = "model-safe",
            temperature = 0.2,
            maximumOutputTokens = 4_096,
            requestJsonObjectFormat = true,
            reference = TranslationProviderReference(
                profileId = "example",
                displayName = "Example Provider",
                endpointHost = "example.invalid",
                endpointSha256 = "d".repeat(64),
            ),
        ),
        initialGlossarySha256 = TranslationArtifactIdentity.glossarySha256(initialGlossary),
    )
    val input = TranslationFixtures.input("1".repeat(64), 0, roleHint = TranslationRoleHint.DIALOGUE)
    val window = TranslationFixtures.window(listOf(input)).copy(batching = dependencies.batching)
    val windowKey = TranslationArtifactIdentity.windowArtifactKey(
        window,
        dependencies.initialGlossarySha256,
        dependencies,
    )
    val pageKey = TranslationArtifactIdentity.pageArtifactKey("b".repeat(64), dependencies)
    val runKey = TranslationArtifactIdentity.runArtifactKey(listOf(0 to pageKey), dependencies)

    fun outcome(state: TranslationResultState = TranslationResultState.TRANSLATED) = ValidatedTranslationItem(
        translationRegionId = input.translationRegionId,
        ocrRegionId = input.ocrRegionId,
        role = TranslationRole.DIALOGUE,
        translatedText = if (state == TranslationResultState.TRANSLATED) "译文" else null,
        state = state,
        preserveReason = if (state == TranslationResultState.PRESERVED_SOURCE) {
            TranslationPreserveReason.PROVIDER_FAILURE
        } else null,
    )

    fun job(protectedOcrCount: Int = 0) = TranslationJobRecord(
        jobId = "translation-job-1",
        projectId = "project-1",
        runArtifactKey = runKey,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        windows = listOf(
            TranslationJobWindow(0, windowKey, listOf(input.translationRegionId)),
        ),
        pages = listOf(
            TranslationJobPage(
                pageId = "c".repeat(64),
                pageOrder = 0,
                ocrPageArtifactKey = "b".repeat(64),
                pageArtifactKey = pageKey,
                translationRegionIds = listOf(input.translationRegionId),
                protectedOcrRegionCount = protectedOcrCount,
            ),
        ),
    )
}
