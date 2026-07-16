package rs.masumi.app.cleanup

import rs.masumi.core.cleanup.CleanupJobStatus

object CleanupResumePolicy {
    fun shouldResume(status: CleanupJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && (status == CleanupJobStatus.QUEUED || status == CleanupJobStatus.RUNNING)
}
