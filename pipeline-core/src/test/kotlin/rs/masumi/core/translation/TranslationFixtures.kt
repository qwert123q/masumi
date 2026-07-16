package rs.masumi.core.translation

internal object TranslationFixtures {
    fun input(
        id: String,
        rank: Int,
        source: String = "原文-$id",
        roleHint: TranslationRoleHint = TranslationRoleHint.CLASSIFY_FREE_TEXT,
    ) = TranslationInputItem(
        translationRegionId = id,
        ocrRegionId = "ocr-$id",
        readingOrderRank = rank,
        sourceText = source,
        roleHint = roleHint,
    )

    fun page(
        order: Int,
        items: List<TranslationInputItem>,
        policy: TranslationPolicy = TranslationPolicy(),
    ) = PageTranslationInput(
        pageId = "page-$order",
        pageOrder = order,
        ocrPageArtifactKey = "ocr-page-$order",
        policy = policy,
        prompt = TranslationPromptRef(),
        items = items,
        protectedRegions = emptyList(),
    )

    fun batchItem(input: TranslationInputItem, pageOrder: Int = 0) = TranslationBatchItem(
        pageId = "page-$pageOrder",
        pageOrder = pageOrder,
        ocrPageArtifactKey = "ocr-page-$pageOrder",
        input = input,
    )

    fun window(
        items: List<TranslationInputItem>,
        policy: TranslationPolicy = TranslationPolicy(),
    ) = TranslationBatchWindow(
        windowIndex = 0,
        policy = policy,
        prompt = TranslationPromptRef(),
        batching = TranslationBatchingConfig(),
        glossary = emptyList(),
        contextItems = emptyList(),
        items = items.map(::batchItem),
        estimatedInputTokens = 0,
        exceedsBudget = false,
    )
}
