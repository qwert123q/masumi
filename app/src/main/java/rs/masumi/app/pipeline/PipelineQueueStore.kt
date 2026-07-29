package rs.masumi.app.pipeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class PipelineQueueStatus {
    ACTIVE,
    PAUSED,
}

internal data class PipelineQueueEntry(
    val projectId: String,
    val enqueuedAtEpochMillis: Long,
    val status: PipelineQueueStatus = PipelineQueueStatus.ACTIVE,
    val errorCode: String? = null,
    val consecutiveFailureCount: Int = 0,
    val retryNotBeforeEpochMillis: Long = 0L,
)

internal class PipelineQueueStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun entries(): List<PipelineQueueEntry> = synchronized(GLOBAL_LOCK) {
        decode(preferences.getString(KEY_ENTRIES, null))
    }

    fun enqueue(projectId: String, now: Long = System.currentTimeMillis()): PipelineQueueEntry =
        synchronized(GLOBAL_LOCK) {
            require(SAFE_ID.matches(projectId))
            val entries = entries().toMutableList()
            val existingIndex = entries.indexOfFirst { it.projectId == projectId }
            val entry = if (existingIndex >= 0) {
                entries[existingIndex].copy(
                    status = PipelineQueueStatus.ACTIVE,
                    errorCode = null,
                    consecutiveFailureCount = 0,
                    retryNotBeforeEpochMillis = 0L,
                )
                    .also { entries[existingIndex] = it }
            } else {
                PipelineQueueEntry(projectId, now.coerceAtLeast(0L)).also(entries::add)
            }
            persist(entries)
            entry
        }

    fun pause(projectId: String, errorCode: String? = null) = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        update(projectId) {
            it.copy(
                status = PipelineQueueStatus.PAUSED,
                errorCode = errorCode?.takeIf(ERROR_CODE::matches),
                retryNotBeforeEpochMillis = 0L,
            )
        }
    }

    fun scheduleRetry(
        projectId: String,
        errorCode: String,
        now: Long = System.currentTimeMillis(),
    ): PipelineQueueEntry? = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        require(ERROR_CODE.matches(errorCode))
        require(now >= 0L)
        val entries = entries().toMutableList()
        val index = entries.indexOfFirst { it.projectId == projectId }
        if (index < 0 || entries[index].status != PipelineQueueStatus.ACTIVE) return@synchronized null
        val failureCount = (entries[index].consecutiveFailureCount + 1)
            .coerceAtMost(MAXIMUM_RECORDED_FAILURE_COUNT)
        val retryAt = now + PipelineRetryPolicy.delayMillis(failureCount)
        val updated = entries[index].copy(
            errorCode = errorCode,
            consecutiveFailureCount = failureCount,
            retryNotBeforeEpochMillis = retryAt.coerceAtLeast(now),
        )
        entries[index] = updated
        persist(entries)
        updated
    }

    fun clearRetry(projectId: String) = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        val entries = entries().toMutableList()
        val index = entries.indexOfFirst { it.projectId == projectId }
        if (index < 0) return@synchronized
        val current = entries[index]
        if (
            current.errorCode == null &&
            current.consecutiveFailureCount == 0 &&
            current.retryNotBeforeEpochMillis == 0L
        ) {
            return@synchronized
        }
        entries[index] = current.copy(
            errorCode = null,
            consecutiveFailureCount = 0,
            retryNotBeforeEpochMillis = 0L,
        )
        persist(entries)
    }

    fun remove(projectId: String) = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        persist(entries().filterNot { it.projectId == projectId })
    }

    fun contains(projectId: String): Boolean = synchronized(GLOBAL_LOCK) {
        SAFE_ID.matches(projectId) && entries().any { it.projectId == projectId }
    }

    fun isActive(projectId: String): Boolean = synchronized(GLOBAL_LOCK) {
        SAFE_ID.matches(projectId) && entries().any {
            it.projectId == projectId && it.status == PipelineQueueStatus.ACTIVE
        }
    }

    private fun update(projectId: String, transform: (PipelineQueueEntry) -> PipelineQueueEntry) {
        val entries = entries().toMutableList()
        val index = entries.indexOfFirst { it.projectId == projectId }
        if (index < 0) return
        entries[index] = transform(entries[index])
        persist(entries)
    }

    private fun persist(entries: List<PipelineQueueEntry>) {
        val encoded = JSONArray().apply {
            entries.sortedWith(
                compareBy(PipelineQueueEntry::enqueuedAtEpochMillis)
                    .thenBy(PipelineQueueEntry::projectId),
            ).forEach { entry ->
                put(
                    JSONObject()
                        .put("projectId", entry.projectId)
                        .put("enqueuedAtEpochMillis", entry.enqueuedAtEpochMillis)
                        .put("status", entry.status.name)
                        .put("errorCode", entry.errorCode)
                        .put("consecutiveFailureCount", entry.consecutiveFailureCount)
                        .put("retryNotBeforeEpochMillis", entry.retryNotBeforeEpochMillis),
                )
            }
        }.toString()
        check(preferences.edit().putString(KEY_ENTRIES, encoded).commit())
    }

    private fun decode(raw: String?): List<PipelineQueueEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val values = JSONArray(raw)
            buildList {
                repeat(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val projectId = value.getString("projectId")
                    val timestamp = value.getLong("enqueuedAtEpochMillis")
                    val status = PipelineQueueStatus.valueOf(value.getString("status"))
                    val error = value.optString("errorCode").takeIf(String::isNotBlank)
                    val failureCount = value.optInt("consecutiveFailureCount", 0)
                    val retryAt = value.optLong("retryNotBeforeEpochMillis", 0L)
                    if (
                        SAFE_ID.matches(projectId) &&
                        timestamp >= 0L &&
                        failureCount in 0..MAXIMUM_RECORDED_FAILURE_COUNT &&
                        retryAt >= 0L &&
                        (error == null || ERROR_CODE.matches(error))
                    ) {
                        add(
                            PipelineQueueEntry(
                                projectId = projectId,
                                enqueuedAtEpochMillis = timestamp,
                                status = status,
                                errorCode = error,
                                consecutiveFailureCount = failureCount,
                                retryNotBeforeEpochMillis = retryAt,
                            ),
                        )
                    }
                }
            }.distinctBy(PipelineQueueEntry::projectId)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFERENCES_NAME = "pipeline_queue"
        const val KEY_ENTRIES = "entries"
        val GLOBAL_LOCK = Any()
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,127}")
        const val MAXIMUM_RECORDED_FAILURE_COUNT = 1_000_000
    }
}
