package rs.masumi.app.detection

import rs.masumi.core.detection.DetectionJobStatus

object DetectionResumePolicy {
    fun shouldResume(status: DetectionJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && when (status) {
            DetectionJobStatus.QUEUED,
            DetectionJobStatus.DOWNLOADING_MODEL,
            DetectionJobStatus.RUNNING,
            -> true

            DetectionJobStatus.SUCCEEDED,
            DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES,
            DetectionJobStatus.CANCELLED,
            DetectionJobStatus.FAILED,
            -> false
        }
}
