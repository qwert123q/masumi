package rs.masumi.core.translation

class TranslationBatchPlanner(
    private val config: TranslationBatchingConfig = TranslationBatchingConfig(),
    private val promptBuilder: TranslationPromptBuilder = TranslationPromptBuilder(),
) {
    fun plan(
        pages: List<PageTranslationInput>,
        glossarySnapshot: Map<String, String> = emptyMap(),
    ): List<TranslationBatchWindow> {
        if (pages.isEmpty()) return emptyList()

        val orderedPages = pages.sortedBy(PageTranslationInput::pageOrder)
        require(orderedPages.map(PageTranslationInput::pageOrder).distinct().size == orderedPages.size) {
            "pageOrder values must be unique"
        }
        val policy = orderedPages.first().policy
        val prompt = orderedPages.first().prompt
        require(orderedPages.all { it.policy == policy }) { "translation policy must match across a chapter" }
        require(orderedPages.all { it.prompt == prompt }) { "translation prompt must match across a chapter" }

        val allItems = orderedPages.flatMap { page ->
            require(page.items.all { it.readingOrderRank >= 0 }) {
                "readingOrderRank must not be negative"
            }
            require(page.items.map(TranslationInputItem::readingOrderRank).distinct().size == page.items.size) {
                "readingOrderRank values must be unique within a page"
            }
            page.items.sortedBy(TranslationInputItem::readingOrderRank).map { item ->
                TranslationBatchItem(
                    pageId = page.pageId,
                    pageOrder = page.pageOrder,
                    ocrPageArtifactKey = page.ocrPageArtifactKey,
                    input = item,
                )
            }
        }
        require(allItems.map { it.input.translationRegionId }.distinct().size == allItems.size) {
            "translationRegionId values must be unique"
        }
        if (allItems.isEmpty()) return emptyList()

        val glossary = normalizeGlossary(glossarySnapshot)
        val windows = mutableListOf<TranslationBatchWindow>()
        var start = 0
        while (start < allItems.size) {
            var context = allItems.subList(maxOf(0, start - config.maximumContextItems), start).toList()
            var selected = listOf(allItems[start])
            var draft = draft(windows.size, policy, prompt, glossary, context, selected)
            var estimate = promptBuilder.estimateInputTokens(draft)
            while (estimate > config.maximumEstimatedInputTokens && context.isNotEmpty()) {
                context = context.drop(1)
                draft = draft(windows.size, policy, prompt, glossary, context, selected)
                estimate = promptBuilder.estimateInputTokens(draft)
            }

            var cursor = start + 1
            while (cursor < allItems.size) {
                val expanded = selected + allItems[cursor]
                val expandedDraft = draft(windows.size, policy, prompt, glossary, context, expanded)
                val expandedEstimate = promptBuilder.estimateInputTokens(expandedDraft)
                if (expandedEstimate > config.maximumEstimatedInputTokens) break
                selected = expanded
                draft = expandedDraft
                estimate = expandedEstimate
                cursor += 1
            }
            windows += draft.copy(
                estimatedInputTokens = estimate,
                exceedsBudget = estimate > config.maximumEstimatedInputTokens,
            )
            start += selected.size
        }
        return windows
    }

    private fun draft(
        index: Int,
        policy: TranslationPolicy,
        prompt: TranslationPromptRef,
        glossary: List<TranslationGlossaryEntry>,
        context: List<TranslationBatchItem>,
        items: List<TranslationBatchItem>,
    ) = TranslationBatchWindow(
        windowIndex = index,
        policy = policy,
        prompt = prompt,
        batching = config,
        glossary = glossary,
        contextItems = context,
        items = items,
        estimatedInputTokens = 0,
        exceedsBudget = false,
    )

    private fun normalizeGlossary(snapshot: Map<String, String>): List<TranslationGlossaryEntry> {
        val entries = snapshot.map { (source, translation) ->
            TranslationGlossaryEntry(source.trim(), translation.trim())
        }
        require(entries.none { it.source.isBlank() || it.translation.isBlank() }) {
            "glossary entries must not be blank"
        }
        require(entries.map(TranslationGlossaryEntry::source).distinct().size == entries.size) {
            "glossary sources must be unique after trimming"
        }
        return entries.sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
    }
}
