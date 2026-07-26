package rs.masumi.app

internal enum class AutomaticPipelineAction {
    WAIT_FOR_IMPORT,
    CONFIGURE_TRANSLATION,
    WAIT_FOR_ACTIVE_STAGE,
    START_DETECTION,
    START_OCR,
    START_TRANSLATION,
    START_CLEANUP,
    START_TYPESETTING,
    COMPLETE,
}

internal data class AutomaticPipelineSnapshot(
    val hasProject: Boolean,
    val hasTranslationSettings: Boolean,
    val hasActiveWork: Boolean,
    val detectionReady: Boolean,
    val ocrReady: Boolean,
    val translationReady: Boolean,
    val cleanupReady: Boolean,
    val typesettingReady: Boolean,
)

internal object AutomaticPipelinePlanner {
    const val STAGE_COUNT = 5

    fun next(snapshot: AutomaticPipelineSnapshot): AutomaticPipelineAction = when {
        !snapshot.hasProject -> AutomaticPipelineAction.WAIT_FOR_IMPORT
        !snapshot.hasTranslationSettings -> AutomaticPipelineAction.CONFIGURE_TRANSLATION
        snapshot.hasActiveWork -> AutomaticPipelineAction.WAIT_FOR_ACTIVE_STAGE
        snapshot.typesettingReady -> AutomaticPipelineAction.COMPLETE
        !snapshot.detectionReady -> AutomaticPipelineAction.START_DETECTION
        !snapshot.ocrReady -> AutomaticPipelineAction.START_OCR
        !snapshot.translationReady -> AutomaticPipelineAction.START_TRANSLATION
        !snapshot.cleanupReady -> AutomaticPipelineAction.START_CLEANUP
        else -> AutomaticPipelineAction.START_TYPESETTING
    }

    fun completedStages(snapshot: AutomaticPipelineSnapshot): Int = when {
        snapshot.typesettingReady -> 5
        snapshot.cleanupReady -> 4
        snapshot.translationReady -> 3
        snapshot.ocrReady -> 2
        snapshot.detectionReady -> 1
        else -> 0
    }
}
