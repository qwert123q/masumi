package rs.masumi.app.ocr

import rs.masumi.core.ocr.OcrJobStatus

object OcrResumePolicy {
    fun shouldResume(status: OcrJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && when (status) {
            OcrJobStatus.QUEUED,
            OcrJobStatus.DOWNLOADING_MODEL,
            OcrJobStatus.LOADING_MODEL,
            OcrJobStatus.RUNNING,
            -> true
            OcrJobStatus.SUCCEEDED,
            OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS,
            OcrJobStatus.CANCELLED,
            OcrJobStatus.FAILED,
            -> false
        }
}
