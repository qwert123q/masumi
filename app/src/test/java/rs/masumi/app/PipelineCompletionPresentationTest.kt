package rs.masumi.app

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.typesetting.TypesettingJobStatus

class PipelineCompletionPresentationTest {
    @Test
    fun `successful terminal variants are presented as ordinary success`() {
        assertEquals(
            DetectionJobStatus.SUCCEEDED,
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES.forUserPresentation(),
        )
        assertEquals(
            OcrJobStatus.SUCCEEDED,
            OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS.forUserPresentation(),
        )
        assertEquals(
            TranslationJobStatus.SUCCEEDED,
            TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS.forUserPresentation(),
        )
        assertEquals(
            CleanupJobStatus.SUCCEEDED,
            CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS.forUserPresentation(),
        )
        assertEquals(
            TypesettingJobStatus.SUCCEEDED,
            TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS.forUserPresentation(),
        )
    }

    @Test
    fun `real failures and cancellation remain visible`() {
        assertEquals(OcrJobStatus.FAILED, OcrJobStatus.FAILED.forUserPresentation())
        assertEquals(
            TranslationJobStatus.CANCELLED,
            TranslationJobStatus.CANCELLED.forUserPresentation(),
        )
    }

    @Test
    fun `successful page previews hide region level preservation details`() {
        assertEquals(false, shouldShowPreviewUnavailable(previewAvailable = true))
        assertEquals(true, shouldShowPreviewUnavailable(previewAvailable = false))
        assertEquals(true, OcrRegionState.RECOGNIZED.isUserVisibleDetail())
        assertEquals(true, OcrRegionState.NO_TEXT_CONFIRMED.isUserVisibleDetail())
        assertEquals(false, OcrRegionState.NEEDS_FALLBACK.isUserVisibleDetail())
        assertEquals(false, OcrRegionState.PRESERVED_SOURCE.isUserVisibleDetail())
    }
}
