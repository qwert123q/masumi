package rs.masumi.app.ocr

import android.content.Intent
import rs.masumi.core.ocr.OcrJobStatus

object OcrStatusBroadcast {
    const val ACTION = "rs.masumi.app.action.OCR_STATUS"
    const val EXTRA_PROJECT_ID = "project_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_RUN_ARTIFACT_KEY = "run_artifact_key"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TERMINAL_REGION_COUNT = "terminal_region_count"
    const val EXTRA_TOTAL_REGION_COUNT = "total_region_count"
    const val EXTRA_COMMITTED_PAGE_COUNT = "committed_page_count"
    const val EXTRA_TOTAL_PAGE_COUNT = "total_page_count"
    const val EXTRA_CURRENT_ORDER = "current_order"
    const val EXTRA_CURRENT_PAGE_ID = "current_page_id"
    const val EXTRA_CURRENT_REGION_ID = "current_region_id"
    const val EXTRA_DOWNLOADED_BYTES = "downloaded_bytes"
    const val EXTRA_TOTAL_DOWNLOAD_BYTES = "total_download_bytes"
    const val EXTRA_ERROR_CODE = "error_code"

    val SAFE_EXTRA_KEYS = setOf(
        EXTRA_PROJECT_ID,
        EXTRA_JOB_ID,
        EXTRA_RUN_ARTIFACT_KEY,
        EXTRA_STATUS,
        EXTRA_TERMINAL_REGION_COUNT,
        EXTRA_TOTAL_REGION_COUNT,
        EXTRA_COMMITTED_PAGE_COUNT,
        EXTRA_TOTAL_PAGE_COUNT,
        EXTRA_CURRENT_ORDER,
        EXTRA_CURRENT_PAGE_ID,
        EXTRA_CURRENT_REGION_ID,
        EXTRA_DOWNLOADED_BYTES,
        EXTRA_TOTAL_DOWNLOAD_BYTES,
        EXTRA_ERROR_CODE,
    )

    fun create(packageName: String, progress: OcrProgress): Intent {
        require(PACKAGE_NAME.matches(packageName))
        require(SAFE_ID.matches(progress.projectId))
        require(SAFE_ID.matches(progress.jobId))
        require(SHA256.matches(progress.runArtifactKey))
        require(progress.currentPageId == null || SHA256.matches(progress.currentPageId))
        require(progress.currentRegionId == null || SHA256.matches(progress.currentRegionId))
        require(progress.errorCode == null || ERROR_CODE.matches(progress.errorCode))
        return Intent(ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROJECT_ID, progress.projectId)
            putExtra(EXTRA_JOB_ID, progress.jobId)
            putExtra(EXTRA_RUN_ARTIFACT_KEY, progress.runArtifactKey)
            putExtra(EXTRA_STATUS, progress.status.name)
            putExtra(EXTRA_TERMINAL_REGION_COUNT, progress.terminalRegionCount)
            putExtra(EXTRA_TOTAL_REGION_COUNT, progress.totalRegionCount)
            putExtra(EXTRA_COMMITTED_PAGE_COUNT, progress.committedPageCount)
            putExtra(EXTRA_TOTAL_PAGE_COUNT, progress.totalPageCount)
            putExtra(EXTRA_CURRENT_ORDER, progress.currentOrder ?: NO_CURRENT_ORDER)
            putExtra(EXTRA_CURRENT_PAGE_ID, progress.currentPageId)
            putExtra(EXTRA_CURRENT_REGION_ID, progress.currentRegionId)
            putExtra(EXTRA_DOWNLOADED_BYTES, progress.downloadedBytes)
            putExtra(EXTRA_TOTAL_DOWNLOAD_BYTES, progress.totalDownloadBytes)
            putExtra(EXTRA_ERROR_CODE, progress.errorCode)
        }
    }

    fun parse(intent: Intent): OcrProgress? = runCatching {
        require(intent.action == ACTION)
        val extras = requireNotNull(intent.extras)
        require(extras.keySet() == SAFE_EXTRA_KEYS)
        val projectId = requireNotNull(intent.getStringExtra(EXTRA_PROJECT_ID))
        val jobId = requireNotNull(intent.getStringExtra(EXTRA_JOB_ID))
        val runKey = requireNotNull(intent.getStringExtra(EXTRA_RUN_ARTIFACT_KEY))
        val status = OcrJobStatus.valueOf(requireNotNull(intent.getStringExtra(EXTRA_STATUS)))
        val currentOrder = intent.getIntExtra(EXTRA_CURRENT_ORDER, INVALID_INT)
        val pageId = intent.getStringExtra(EXTRA_CURRENT_PAGE_ID)
        val regionId = intent.getStringExtra(EXTRA_CURRENT_REGION_ID)
        val errorCode = intent.getStringExtra(EXTRA_ERROR_CODE)
        require(SAFE_ID.matches(projectId) && SAFE_ID.matches(jobId) && SHA256.matches(runKey))
        require(currentOrder >= NO_CURRENT_ORDER)
        require(pageId == null || SHA256.matches(pageId))
        require(regionId == null || SHA256.matches(regionId))
        require(errorCode == null || ERROR_CODE.matches(errorCode))
        OcrProgress(
            projectId = projectId,
            jobId = jobId,
            runArtifactKey = runKey,
            status = status,
            terminalRegionCount = intent.requireNonNegativeInt(EXTRA_TERMINAL_REGION_COUNT),
            totalRegionCount = intent.requireNonNegativeInt(EXTRA_TOTAL_REGION_COUNT),
            committedPageCount = intent.requireNonNegativeInt(EXTRA_COMMITTED_PAGE_COUNT),
            totalPageCount = intent.requireNonNegativeInt(EXTRA_TOTAL_PAGE_COUNT),
            currentOrder = currentOrder.takeUnless { it == NO_CURRENT_ORDER },
            currentPageId = pageId,
            currentRegionId = regionId,
            downloadedBytes = intent.requireNonNegativeLong(EXTRA_DOWNLOADED_BYTES),
            totalDownloadBytes = intent.requireNonNegativeLong(EXTRA_TOTAL_DOWNLOAD_BYTES),
            errorCode = errorCode,
        )
    }.getOrNull()

    private fun Intent.requireNonNegativeInt(key: String): Int =
        getIntExtra(key, INVALID_INT).also { require(it >= 0) }

    private fun Intent.requireNonNegativeLong(key: String): Long =
        getLongExtra(key, INVALID_LONG).also { require(it >= 0L) }

    private const val NO_CURRENT_ORDER = -1
    private const val INVALID_INT = Int.MIN_VALUE
    private const val INVALID_LONG = Long.MIN_VALUE
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,127}")
}
