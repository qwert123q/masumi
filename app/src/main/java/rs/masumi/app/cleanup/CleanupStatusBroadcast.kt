package rs.masumi.app.cleanup

import android.content.Intent
import rs.masumi.core.cleanup.CleanupJobStatus

object CleanupStatusBroadcast {
    const val ACTION = "rs.masumi.app.action.CLEANUP_STATUS"
    const val EXTRA_PROJECT_ID = "project_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_RUN_ARTIFACT_KEY = "run_artifact_key"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TERMINAL_PAGE_COUNT = "terminal_page_count"
    const val EXTRA_TOTAL_PAGE_COUNT = "total_page_count"
    const val EXTRA_CLEANED_REGION_COUNT = "cleaned_region_count"
    const val EXTRA_PRESERVED_REGION_COUNT = "preserved_region_count"
    const val EXTRA_CURRENT_PAGE_ORDER = "current_page_order"
    const val EXTRA_ERROR_CODE = "error_code"

    val SAFE_EXTRA_KEYS = setOf(
        EXTRA_PROJECT_ID,
        EXTRA_JOB_ID,
        EXTRA_RUN_ARTIFACT_KEY,
        EXTRA_STATUS,
        EXTRA_TERMINAL_PAGE_COUNT,
        EXTRA_TOTAL_PAGE_COUNT,
        EXTRA_CLEANED_REGION_COUNT,
        EXTRA_PRESERVED_REGION_COUNT,
        EXTRA_CURRENT_PAGE_ORDER,
        EXTRA_ERROR_CODE,
    )

    fun create(packageName: String, progress: CleanupProgress): Intent {
        require(PACKAGE_NAME.matches(packageName))
        require(SAFE_ID.matches(progress.projectId) && SAFE_ID.matches(progress.jobId))
        require(SHA256.matches(progress.runArtifactKey))
        require(progress.errorCode == null || ERROR_CODE.matches(progress.errorCode))
        return Intent(ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROJECT_ID, progress.projectId)
            putExtra(EXTRA_JOB_ID, progress.jobId)
            putExtra(EXTRA_RUN_ARTIFACT_KEY, progress.runArtifactKey)
            putExtra(EXTRA_STATUS, progress.status.name)
            putExtra(EXTRA_TERMINAL_PAGE_COUNT, progress.terminalPageCount)
            putExtra(EXTRA_TOTAL_PAGE_COUNT, progress.totalPageCount)
            putExtra(EXTRA_CLEANED_REGION_COUNT, progress.cleanedRegionCount)
            putExtra(EXTRA_PRESERVED_REGION_COUNT, progress.preservedRegionCount)
            putExtra(EXTRA_CURRENT_PAGE_ORDER, progress.currentPageOrder ?: NO_CURRENT_PAGE)
            putExtra(EXTRA_ERROR_CODE, progress.errorCode)
        }
    }

    fun parse(intent: Intent): CleanupProgress? = runCatching {
        require(intent.action == ACTION)
        val extras = requireNotNull(intent.extras)
        require(extras.keySet() == SAFE_EXTRA_KEYS)
        val projectId = requireNotNull(intent.getStringExtra(EXTRA_PROJECT_ID))
        val jobId = requireNotNull(intent.getStringExtra(EXTRA_JOB_ID))
        val runKey = requireNotNull(intent.getStringExtra(EXTRA_RUN_ARTIFACT_KEY))
        val errorCode = intent.getStringExtra(EXTRA_ERROR_CODE)
        val currentOrder = intent.getIntExtra(EXTRA_CURRENT_PAGE_ORDER, INVALID_INT)
        require(SAFE_ID.matches(projectId) && SAFE_ID.matches(jobId) && SHA256.matches(runKey))
        require(errorCode == null || ERROR_CODE.matches(errorCode))
        require(currentOrder >= NO_CURRENT_PAGE)
        CleanupProgress(
            projectId = projectId,
            jobId = jobId,
            runArtifactKey = runKey,
            status = CleanupJobStatus.valueOf(requireNotNull(intent.getStringExtra(EXTRA_STATUS))),
            terminalPageCount = intent.nonNegativeInt(EXTRA_TERMINAL_PAGE_COUNT),
            totalPageCount = intent.nonNegativeInt(EXTRA_TOTAL_PAGE_COUNT),
            cleanedRegionCount = intent.nonNegativeInt(EXTRA_CLEANED_REGION_COUNT),
            preservedRegionCount = intent.nonNegativeInt(EXTRA_PRESERVED_REGION_COUNT),
            currentPageOrder = currentOrder.takeUnless { it == NO_CURRENT_PAGE },
            errorCode = errorCode,
        )
    }.getOrNull()

    private fun Intent.nonNegativeInt(key: String): Int =
        getIntExtra(key, INVALID_INT).also { require(it >= 0) }

    private const val NO_CURRENT_PAGE = -1
    private const val INVALID_INT = Int.MIN_VALUE
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,127}")
}
