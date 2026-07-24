package rs.masumi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticPipelinePlannerTest {
    @Test
    fun asksForImportBeforeAnyTechnicalStage() {
        assertEquals(
            AutomaticPipelineAction.WAIT_FOR_IMPORT,
            AutomaticPipelinePlanner.next(snapshot(hasProject = false)),
        )
    }

    @Test
    fun asksForTranslationSettingsBeforeStartingWork() {
        assertEquals(
            AutomaticPipelineAction.CONFIGURE_TRANSLATION,
            AutomaticPipelinePlanner.next(snapshot(hasTranslationSettings = false)),
        )
    }

    @Test
    fun advancesThroughEveryMissingStageInOrder() {
        val expected = listOf(
            AutomaticPipelineAction.START_DETECTION,
            AutomaticPipelineAction.START_OCR,
            AutomaticPipelineAction.START_TRANSLATION,
            AutomaticPipelineAction.START_CLEANUP,
            AutomaticPipelineAction.START_TYPESETTING,
            AutomaticPipelineAction.START_QUALITY,
            AutomaticPipelineAction.COMPLETE,
        )

        expected.forEachIndexed { completed, action ->
            val state = snapshot(
                detectionReady = completed >= 1,
                ocrReady = completed >= 2,
                translationReady = completed >= 3,
                cleanupReady = completed >= 4,
                typesettingReady = completed >= 5,
                qualityReady = completed >= 6,
            )
            assertEquals(action, AutomaticPipelinePlanner.next(state))
            assertEquals(completed.coerceAtMost(6), AutomaticPipelinePlanner.completedStages(state))
        }
    }

    @Test
    fun neverStartsAnotherStageWhileWorkIsActive() {
        assertEquals(
            AutomaticPipelineAction.WAIT_FOR_ACTIVE_STAGE,
            AutomaticPipelinePlanner.next(snapshot(hasActiveWork = true)),
        )
    }

    @Test
    fun routesBlockedQualityToReviewInsteadOfLooping() {
        val state = snapshot(
            detectionReady = true,
            ocrReady = true,
            translationReady = true,
            cleanupReady = true,
            typesettingReady = true,
            qualityBlocked = true,
        )
        assertEquals(AutomaticPipelineAction.REVIEW_QUALITY, AutomaticPipelinePlanner.next(state))
        assertEquals(5, AutomaticPipelinePlanner.completedStages(state))
    }

    private fun snapshot(
        hasProject: Boolean = true,
        hasTranslationSettings: Boolean = true,
        hasActiveWork: Boolean = false,
        detectionReady: Boolean = false,
        ocrReady: Boolean = false,
        translationReady: Boolean = false,
        cleanupReady: Boolean = false,
        typesettingReady: Boolean = false,
        qualityReady: Boolean = false,
        qualityBlocked: Boolean = false,
    ) = AutomaticPipelineSnapshot(
        hasProject = hasProject,
        hasTranslationSettings = hasTranslationSettings,
        hasActiveWork = hasActiveWork,
        detectionReady = detectionReady,
        ocrReady = ocrReady,
        translationReady = translationReady,
        cleanupReady = cleanupReady,
        typesettingReady = typesettingReady,
        qualityReady = qualityReady,
        qualityBlocked = qualityBlocked,
    )
}
