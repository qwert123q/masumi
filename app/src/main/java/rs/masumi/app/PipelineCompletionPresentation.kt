package rs.masumi.app

import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.typesetting.TypesettingJobStatus

/**
 * Successful runs remain diagnostically distinct in artifacts, but the user
 * sees one ordinary completion state instead of a warning badge for regions
 * the accepted best-effort pipeline deliberately carried forward.
 */
internal fun OcrJobStatus.forUserPresentation(): OcrJobStatus = when (this) {
    OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> OcrJobStatus.SUCCEEDED
    else -> this
}

internal fun DetectionJobStatus.forUserPresentation(): DetectionJobStatus = when (this) {
    DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES -> DetectionJobStatus.SUCCEEDED
    else -> this
}

internal fun TranslationJobStatus.forUserPresentation(): TranslationJobStatus = when (this) {
    TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS -> TranslationJobStatus.SUCCEEDED
    else -> this
}

internal fun CleanupJobStatus.forUserPresentation(): CleanupJobStatus = when (this) {
    CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> CleanupJobStatus.SUCCEEDED
    else -> this
}

internal fun TypesettingJobStatus.forUserPresentation(): TypesettingJobStatus = when (this) {
    TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS -> TypesettingJobStatus.SUCCEEDED
    else -> this
}

internal fun shouldShowPreviewUnavailable(previewAvailable: Boolean): Boolean = !previewAvailable

internal fun OcrRegionState.isUserVisibleDetail(): Boolean = when (this) {
    OcrRegionState.RECOGNIZED,
    OcrRegionState.NO_TEXT_CONFIRMED,
    -> true
    OcrRegionState.PENDING,
    OcrRegionState.RUNNING,
    OcrRegionState.NEEDS_FALLBACK,
    OcrRegionState.PRESERVED_SOURCE,
    -> false
}
