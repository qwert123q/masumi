package rs.masumi.core.translation

class TranslationResponseValidator {
    fun validate(
        window: TranslationBatchWindow,
        response: TranslationModelResponse,
    ): TranslationResponseValidation {
        val expectedIds = window.items.map { it.input.translationRegionId }.toSet()
        val responsesById = response.items.groupBy(TranslationModelItem::id)
        val items = window.items.map { requested ->
            validateItem(window.policy, requested.input, responsesById[requested.input.translationRegionId].orEmpty())
        }
        val ignoredIds = responsesById.keys.filterNot(expectedIds::contains).sorted()
        val validGlossaryCandidates = response.glossaryUpdates.mapNotNull { (source, translation) ->
            val normalizedSource = source.trim()
            val normalizedTranslation = translation.trim()
            if (normalizedSource.isBlank() || normalizedTranslation.isBlank()) {
                null
            } else {
                TranslationGlossaryEntry(normalizedSource, normalizedTranslation)
            }
        }
        val normalizedGlossary = validGlossaryCandidates.groupBy(TranslationGlossaryEntry::source)
            .values
            .filter { candidates -> candidates.size == 1 }
            .map(List<TranslationGlossaryEntry>::single)
            .sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
        return TranslationResponseValidation(
            items = items,
            ignoredResponseIds = ignoredIds,
            glossaryUpdates = normalizedGlossary,
            discardedGlossaryEntryCount = response.glossaryUpdates.size - normalizedGlossary.size,
        )
    }

    private fun validateItem(
        policy: TranslationPolicy,
        requested: TranslationInputItem,
        responses: List<TranslationModelItem>,
    ): ValidatedTranslationItem {
        if (responses.isEmpty()) return preserved(requested, null, TranslationPreserveReason.MISSING_RESPONSE)
        if (responses.size > 1) return preserved(requested, null, TranslationPreserveReason.DUPLICATE_RESPONSE)
        val response = responses.single()
        if (requested.roleHint == TranslationRoleHint.DIALOGUE && response.role != TranslationRole.DIALOGUE) {
            return preserved(requested, response.role, TranslationPreserveReason.INVALID_ROLE)
        }
        if (!shouldTranslate(policy, response.role)) {
            return preserved(requested, response.role, TranslationPreserveReason.POLICY_PRESERVED)
        }
        val translatedText = response.translation?.trim().orEmpty()
        if (translatedText.isBlank()) {
            return preserved(requested, response.role, TranslationPreserveReason.BLANK_TRANSLATION)
        }
        return ValidatedTranslationItem(
            translationRegionId = requested.translationRegionId,
            ocrRegionId = requested.ocrRegionId,
            role = response.role,
            translatedText = translatedText,
            state = TranslationResultState.TRANSLATED,
            preserveReason = null,
        )
    }

    private fun shouldTranslate(policy: TranslationPolicy, role: TranslationRole): Boolean = when (role) {
        TranslationRole.DIALOGUE -> policy.translateDialogue
        TranslationRole.NARRATION -> policy.translateNarration
        TranslationRole.SOUND_EFFECT -> policy.translateSoundEffects
        TranslationRole.OTHER_TEXT -> policy.translateOtherText
    }

    private fun preserved(
        requested: TranslationInputItem,
        role: TranslationRole?,
        reason: TranslationPreserveReason,
    ) = ValidatedTranslationItem(
        translationRegionId = requested.translationRegionId,
        ocrRegionId = requested.ocrRegionId,
        role = role,
        translatedText = null,
        state = TranslationResultState.PRESERVED_SOURCE,
        preserveReason = reason,
    )
}
