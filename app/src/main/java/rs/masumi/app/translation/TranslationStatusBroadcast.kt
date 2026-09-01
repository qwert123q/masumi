package rs.masumi.app.translation

import android.content.Intent
import rs.masumi.core.identity.SafeOpaqueId
import rs.masumi.core.translation.TranslationJobStatus

object TranslationStatusBroadcast {
    const val ACTION = "rs.masumi.app.action.TRANSLATION_STATUS"
    const val EXTRA_PROJECT_ID = "project_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_RUN_ARTIFACT_KEY = "run_artifact_key"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TERMINAL_WINDOW_COUNT = "terminal_window_count"
    const val EXTRA_TOTAL_WINDOW_COUNT = "total_window_count"
    const val EXTRA_COMMITTED_PAGE_COUNT = "committed_page_count"
    const val EXTRA_TOTAL_PAGE_COUNT = "total_page_count"
    const val EXTRA_TRANSLATED_ITEM_COUNT = "translated_item_count"
    const val EXTRA_PRESERVED_ITEM_COUNT = "preserved_item_count"
    const val EXTRA_PROTECTED_OCR_COUNT = "protected_ocr_count"
    const val EXTRA_CURRENT_WINDOW_INDEX = "current_window_index"
    const val EXTRA_ERROR_CODE = "error_code"

    val SAFE_EXTRA_KEYS = setOf(
        EXTRA_PROJECT_ID,
        EXTRA_JOB_ID,
        EXTRA_RUN_ARTIFACT_KEY,
        EXTRA_STATUS,
        EXTRA_TERMINAL_WINDOW_COUNT,
        EXTRA_TOTAL_WINDOW_COUNT,
        EXTRA_COMMITTED_PAGE_COUNT,
        EXTRA_TOTAL_PAGE_COUNT,
        EXTRA_TRANSLATED_ITEM_COUNT,
        EXTRA_PRESERVED_ITEM_COUNT,
        EXTRA_PROTECTED_OCR_COUNT,
        EXTRA_CURRENT_WINDOW_INDEX,
        EXTRA_ERROR_CODE,
    )

    fun create(packageName: String, progress: TranslationProgress): Intent {
        require(PACKAGE_NAME.matches(packageName))
        require(SafeOpaqueId.isValid(progress.projectId) && SafeOpaqueId.isValid(progress.jobId))
        require(SafeOpaqueId.isValid(progress.runArtifactKey))
        require(progress.errorCode == null || ERROR_CODE.matches(progress.errorCode))
        return Intent(ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROJECT_ID, progress.projectId)
            putExtra(EXTRA_JOB_ID, progress.jobId)
            putExtra(EXTRA_RUN_ARTIFACT_KEY, progress.runArtifactKey)
            putExtra(EXTRA_STATUS, progress.status.name)
            putExtra(EXTRA_TERMINAL_WINDOW_COUNT, progress.terminalWindowCount)
            putExtra(EXTRA_TOTAL_WINDOW_COUNT, progress.totalWindowCount)
            putExtra(EXTRA_COMMITTED_PAGE_COUNT, progress.committedPageCount)
            putExtra(EXTRA_TOTAL_PAGE_COUNT, progress.totalPageCount)
            putExtra(EXTRA_TRANSLATED_ITEM_COUNT, progress.translatedItemCount)
            putExtra(EXTRA_PRESERVED_ITEM_COUNT, progress.preservedItemCount)
            putExtra(EXTRA_PROTECTED_OCR_COUNT, progress.protectedOcrCount)
            putExtra(EXTRA_CURRENT_WINDOW_INDEX, progress.currentWindowIndex ?: NO_CURRENT_WINDOW)
            putExtra(EXTRA_ERROR_CODE, progress.errorCode)
        }
    }

    fun parse(intent: Intent): TranslationProgress? = runCatching {
        require(intent.action == ACTION)
        val extras = requireNotNull(intent.extras)
        require(extras.keySet() == SAFE_EXTRA_KEYS)
        val projectId = requireNotNull(intent.getStringExtra(EXTRA_PROJECT_ID))
        val jobId = requireNotNull(intent.getStringExtra(EXTRA_JOB_ID))
        val runKey = requireNotNull(intent.getStringExtra(EXTRA_RUN_ARTIFACT_KEY))
        val errorCode = intent.getStringExtra(EXTRA_ERROR_CODE)
        val currentWindow = intent.getIntExtra(EXTRA_CURRENT_WINDOW_INDEX, INVALID_INT)
        require(SafeOpaqueId.isValid(projectId) && SafeOpaqueId.isValid(jobId) && SafeOpaqueId.isValid(runKey))
        require(errorCode == null || ERROR_CODE.matches(errorCode))
        require(currentWindow >= NO_CURRENT_WINDOW)
        TranslationProgress(
            projectId = projectId,
            jobId = jobId,
            runArtifactKey = runKey,
            status = TranslationJobStatus.valueOf(requireNotNull(intent.getStringExtra(EXTRA_STATUS))),
            terminalWindowCount = intent.nonNegativeInt(EXTRA_TERMINAL_WINDOW_COUNT),
            totalWindowCount = intent.nonNegativeInt(EXTRA_TOTAL_WINDOW_COUNT),
            committedPageCount = intent.nonNegativeInt(EXTRA_COMMITTED_PAGE_COUNT),
            totalPageCount = intent.nonNegativeInt(EXTRA_TOTAL_PAGE_COUNT),
            translatedItemCount = intent.nonNegativeInt(EXTRA_TRANSLATED_ITEM_COUNT),
            preservedItemCount = intent.nonNegativeInt(EXTRA_PRESERVED_ITEM_COUNT),
            protectedOcrCount = intent.nonNegativeInt(EXTRA_PROTECTED_OCR_COUNT),
            currentWindowIndex = currentWindow.takeUnless { it == NO_CURRENT_WINDOW },
            errorCode = errorCode,
        )
    }.getOrNull()

    private fun Intent.nonNegativeInt(key: String): Int =
        getIntExtra(key, INVALID_INT).also { require(it >= 0) }

    private const val NO_CURRENT_WINDOW = -1
    private const val INVALID_INT = Int.MIN_VALUE
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,127}")
}
