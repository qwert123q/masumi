package rs.masumi.core.translation

class TranslationResponseValidator(
    private val outputValidation: TranslationOutputValidationConfig = TranslationOutputValidationConfig(),
) {
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
            } else if (invalidOutputReason(normalizedSource, normalizedTranslation) != null) {
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
        val effectiveRole = if (requested.roleHint == TranslationRoleHint.DIALOGUE) {
            TranslationRole.DIALOGUE
        } else {
            response.role
        }
        if (!shouldTranslate(policy, effectiveRole)) {
            return preserved(requested, effectiveRole, TranslationPreserveReason.POLICY_PRESERVED)
        }
        val translatedText = TranslationSourceText.normalizeForOutputValidation(response.translation.orEmpty())
        invalidOutputReason(requested.sourceText, translatedText)?.let { reason ->
            return preserved(requested, effectiveRole, reason)
        }
        return ValidatedTranslationItem(
            translationRegionId = requested.translationRegionId,
            ocrRegionId = requested.ocrRegionId,
            role = effectiveRole,
            translatedText = translatedText,
            state = TranslationResultState.TRANSLATED,
            preserveReason = null,
        )
    }

    /** Re-applies the wire-output guard after local typography/glossary normalization. */
    fun invalidOutputReason(sourceText: String, translatedText: String): TranslationPreserveReason? {
        val normalizedTarget = TranslationSourceText.normalizeForOutputValidation(translatedText)
        if (normalizedTarget.isBlank()) return TranslationPreserveReason.BLANK_TRANSLATION
        if (TranslationSourceText.containsJapanesePhoneticScript(normalizedTarget)) {
            return TranslationPreserveReason.INVALID_TARGET_SCRIPT
        }
        val normalizedSource = TranslationSourceText.normalizeForOutputValidation(sourceText)
        if (normalizedTarget == normalizedSource && !TranslationSourceText.isPureNumberOrSymbol(normalizedSource)) {
            return TranslationPreserveReason.SOURCE_TEXT_ECHO
        }
        return null
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
