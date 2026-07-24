package rs.masumi.app.typesetting

import rs.masumi.core.typesetting.TypesettingJobStatus

object TypesettingResumePolicy {
    fun shouldResume(status: TypesettingJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && (status == TypesettingJobStatus.QUEUED || status == TypesettingJobStatus.RUNNING)
}
