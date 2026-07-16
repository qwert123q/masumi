package rs.masumi.app.exporting

import rs.masumi.core.exporting.ExportJobStatus

object ExportResumePolicy {
    fun shouldResume(status: ExportJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && (status == ExportJobStatus.QUEUED || status == ExportJobStatus.RUNNING)
}
