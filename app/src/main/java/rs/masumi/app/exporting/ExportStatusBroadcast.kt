package rs.masumi.app.exporting

import android.content.Intent
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.identity.SafeOpaqueId

object ExportStatusBroadcast {
    const val ACTION = "rs.masumi.app.action.EXPORT_STATUS"
    const val EXTRA_PROJECT_ID = "project_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_EXPORT_KEY = "export_key"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TERMINAL_PAGE_COUNT = "terminal_page_count"
    const val EXTRA_TOTAL_PAGE_COUNT = "total_page_count"
    const val EXTRA_FLATTENED_PAGE_COUNT = "flattened_page_count"
    const val EXTRA_CLEANED_FALLBACK_PAGE_COUNT = "cleaned_fallback_page_count"
    const val EXTRA_SOURCE_FALLBACK_PAGE_COUNT = "source_fallback_page_count"
    const val EXTRA_REUSED_PAGE_COUNT = "reused_page_count"
    const val EXTRA_CURRENT_PAGE_ORDER = "current_page_order"
    const val EXTRA_ERROR_CODE = "error_code"

    val SAFE_EXTRA_KEYS = setOf(
        EXTRA_PROJECT_ID,
        EXTRA_JOB_ID,
        EXTRA_EXPORT_KEY,
        EXTRA_STATUS,
        EXTRA_TERMINAL_PAGE_COUNT,
        EXTRA_TOTAL_PAGE_COUNT,
        EXTRA_FLATTENED_PAGE_COUNT,
        EXTRA_CLEANED_FALLBACK_PAGE_COUNT,
        EXTRA_SOURCE_FALLBACK_PAGE_COUNT,
        EXTRA_REUSED_PAGE_COUNT,
        EXTRA_CURRENT_PAGE_ORDER,
        EXTRA_ERROR_CODE,
    )

    fun create(packageName: String, progress: ExportProgress): Intent {
        require(PACKAGE_NAME.matches(packageName))
        require(SafeOpaqueId.isValid(progress.projectId) && SafeOpaqueId.isValid(progress.jobId))
        require(SafeOpaqueId.isValid(progress.exportKey))
        require(progress.errorCode == null || ERROR_CODE.matches(progress.errorCode))
        return Intent(ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROJECT_ID, progress.projectId)
            putExtra(EXTRA_JOB_ID, progress.jobId)
            putExtra(EXTRA_EXPORT_KEY, progress.exportKey)
            putExtra(EXTRA_STATUS, progress.status.name)
            putExtra(EXTRA_TERMINAL_PAGE_COUNT, progress.terminalPageCount)
            putExtra(EXTRA_TOTAL_PAGE_COUNT, progress.totalPageCount)
            putExtra(EXTRA_FLATTENED_PAGE_COUNT, progress.flattenedPageCount)
            putExtra(EXTRA_CLEANED_FALLBACK_PAGE_COUNT, progress.cleanedFallbackPageCount)
            putExtra(EXTRA_SOURCE_FALLBACK_PAGE_COUNT, progress.sourceFallbackPageCount)
            putExtra(EXTRA_REUSED_PAGE_COUNT, progress.reusedPageCount)
            putExtra(EXTRA_CURRENT_PAGE_ORDER, progress.currentPageOrder ?: NO_CURRENT_PAGE)
            putExtra(EXTRA_ERROR_CODE, progress.errorCode)
        }
    }

    fun parse(intent: Intent): ExportProgress? = runCatching {
        require(intent.action == ACTION)
        val extras = requireNotNull(intent.extras)
        require(extras.keySet() == SAFE_EXTRA_KEYS)
        val projectId = requireNotNull(intent.getStringExtra(EXTRA_PROJECT_ID))
        val jobId = requireNotNull(intent.getStringExtra(EXTRA_JOB_ID))
        val exportKey = requireNotNull(intent.getStringExtra(EXTRA_EXPORT_KEY))
        val errorCode = intent.getStringExtra(EXTRA_ERROR_CODE)
        val currentOrder = intent.getIntExtra(EXTRA_CURRENT_PAGE_ORDER, INVALID_INT)
        require(SafeOpaqueId.isValid(projectId) && SafeOpaqueId.isValid(jobId) && SafeOpaqueId.isValid(exportKey))
        require(errorCode == null || ERROR_CODE.matches(errorCode))
        require(currentOrder >= NO_CURRENT_PAGE)
        ExportProgress(
            projectId = projectId,
            jobId = jobId,
            exportKey = exportKey,
            status = ExportJobStatus.valueOf(requireNotNull(intent.getStringExtra(EXTRA_STATUS))),
            terminalPageCount = intent.nonNegativeInt(EXTRA_TERMINAL_PAGE_COUNT),
            totalPageCount = intent.nonNegativeInt(EXTRA_TOTAL_PAGE_COUNT),
            flattenedPageCount = intent.nonNegativeInt(EXTRA_FLATTENED_PAGE_COUNT),
            cleanedFallbackPageCount = intent.nonNegativeInt(EXTRA_CLEANED_FALLBACK_PAGE_COUNT),
            sourceFallbackPageCount = intent.nonNegativeInt(EXTRA_SOURCE_FALLBACK_PAGE_COUNT),
            reusedPageCount = intent.nonNegativeInt(EXTRA_REUSED_PAGE_COUNT),
            currentPageOrder = currentOrder.takeUnless { it == NO_CURRENT_PAGE },
            errorCode = errorCode,
        )
    }.getOrNull()

    private fun Intent.nonNegativeInt(key: String): Int =
        getIntExtra(key, INVALID_INT).also { require(it >= 0) }

    private const val NO_CURRENT_PAGE = -1
    private const val INVALID_INT = Int.MIN_VALUE
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,127}")
}
