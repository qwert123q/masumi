package rs.masumi.app.translation

import rs.masumi.core.translation.TranslationJobStatus

object TranslationResumePolicy {
    fun shouldResume(status: TranslationJobStatus, alreadyRequested: Boolean): Boolean =
        !alreadyRequested && when (status) {
            TranslationJobStatus.QUEUED,
            TranslationJobStatus.RUNNING,
            -> true
            TranslationJobStatus.SUCCEEDED,
            TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS,
            TranslationJobStatus.CANCELLED,
            TranslationJobStatus.FAILED,
            -> false
        }
}
