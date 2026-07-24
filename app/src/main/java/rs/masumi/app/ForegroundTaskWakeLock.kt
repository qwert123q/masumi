package rs.masumi.app

import android.content.Context
import android.os.PowerManager

internal class ForegroundTaskWakeLock(
    context: Context,
    taskName: String,
) {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:$taskName")
        .apply { setReferenceCounted(false) }

    fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire(MAX_TASK_MILLIS)
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        const val MAX_TASK_MILLIS = 12L * 60L * 60L * 1_000L
    }
}
