package rs.masumi.app.ocr

import android.os.SystemClock
import rs.masumi.core.ocr.OcrJobStatus

class OcrProgressThrottle(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val minimumIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private var lastPublishedAtMillis: Long? = null
    private var lastStatus: OcrJobStatus? = null

    init {
        require(minimumIntervalMillis >= 0L)
    }

    @Synchronized
    fun shouldPublish(progress: OcrProgress): Boolean {
        val now = nowMillis()
        val statusChanged = progress.status != lastStatus
        val downloadCompleted = progress.status == OcrJobStatus.DOWNLOADING_MODEL &&
            progress.totalDownloadBytes > 0L &&
            progress.downloadedBytes >= progress.totalDownloadBytes
        val intervalElapsed = lastPublishedAtMillis?.let { previous ->
            now - previous >= minimumIntervalMillis
        } ?: true
        if (!statusChanged && !downloadCompleted && !intervalElapsed) return false
        lastStatus = progress.status
        lastPublishedAtMillis = now
        return true
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 500L
    }
}
