package rs.masumi.app.quality

import rs.masumi.core.quality.QualityJobStatus

object QualityResumePolicy {
    fun shouldResume(status: QualityJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && (status == QualityJobStatus.QUEUED || status == QualityJobStatus.RUNNING)
}
