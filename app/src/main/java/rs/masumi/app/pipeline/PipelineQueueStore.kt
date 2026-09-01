package rs.masumi.app.pipeline

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class PipelineQueueStatus {
    ACTIVE,
    PAUSED,
}

internal data class PipelineQueueEntry(
    val projectId: String,
    val enqueuedAtEpochMillis: Long,
    val status: PipelineQueueStatus = PipelineQueueStatus.ACTIVE,
    val errorCode: String? = null,
)

internal interface PipelineQueuePersistence {
    fun read(): String?

    fun write(value: String): Boolean
}

internal class PipelineQueueStore internal constructor(
    private val persistence: PipelineQueuePersistence,
) {
    constructor(context: Context) : this(
        object : PipelineQueuePersistence {
            private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

            override fun read(): String? = preferences.getString(KEY_ENTRIES, null)

            override fun write(value: String): Boolean = preferences.edit()
                .putString(KEY_ENTRIES, value)
                .commit()
        },
    )

    fun entries(): List<PipelineQueueEntry> = synchronized(GLOBAL_LOCK) {
        decode(persistence.read())
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
                )
                    .also { entries[existingIndex] = it }
            } else {
                PipelineQueueEntry(projectId, now.coerceAtLeast(0L)).also(entries::add)
            }
            persist(entries)
            entry
        }

    fun enqueueIfAbsent(
        projectIds: List<String>,
        firstEnqueuedAtEpochMillis: Long = System.currentTimeMillis(),
    ): List<PipelineQueueEntry> = synchronized(GLOBAL_LOCK) {
        val distinctProjectIds = projectIds.distinct()
        require(distinctProjectIds.all(SAFE_ID::matches))
        require(firstEnqueuedAtEpochMillis >= 0L)
        val entries = entries().toMutableList()
        val existingIds = entries.mapTo(mutableSetOf(), PipelineQueueEntry::projectId)
        val added = distinctProjectIds.mapIndexedNotNull { index, projectId ->
            if (!existingIds.add(projectId)) return@mapIndexedNotNull null
            PipelineQueueEntry(
                projectId = projectId,
                enqueuedAtEpochMillis = firstEnqueuedAtEpochMillis
                    .coerceAtMost(Long.MAX_VALUE - index) + index,
            ).also(entries::add)
        }
        if (added.isNotEmpty()) persist(entries)
        added
    }

    fun pause(projectId: String, errorCode: String? = null) = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        update(projectId) {
            it.copy(
                status = PipelineQueueStatus.PAUSED,
                errorCode = errorCode?.takeIf(ERROR_CODE::matches),
            )
        }
    }

    /** Records a stage failure without racing an explicit user pause. */
    fun fail(projectId: String, errorCode: String): Boolean = synchronized(GLOBAL_LOCK) {
        require(SAFE_ID.matches(projectId))
        require(ERROR_CODE.matches(errorCode))
        val entries = entries().toMutableList()
        val index = entries.indexOfFirst {
            it.projectId == projectId && it.status == PipelineQueueStatus.ACTIVE
        }
        if (index < 0) return@synchronized false
        entries[index] = entries[index].copy(
            status = PipelineQueueStatus.PAUSED,
            errorCode = errorCode,
        )
        persist(entries)
        true
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
        val encoded = buildJsonArray {
            entries.sortedWith(
                compareBy(PipelineQueueEntry::enqueuedAtEpochMillis)
                    .thenBy(PipelineQueueEntry::projectId),
            ).forEach { entry ->
                add(
                    buildJsonObject {
                        put("projectId", entry.projectId)
                        put("enqueuedAtEpochMillis", entry.enqueuedAtEpochMillis)
                        put("status", entry.status.name)
                        entry.errorCode?.let { errorCode -> put("errorCode", errorCode) }
                    },
                )
            }
        }.toString()
        check(persistence.write(encoded))
    }

    private fun decode(raw: String?): List<PipelineQueueEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val values = Json.parseToJsonElement(raw).jsonArray
            buildList(values.size) {
                values.forEach { element ->
                    val value = element.jsonObject
                    val projectId = requireNotNull(
                        value.getValue("projectId").jsonPrimitive.contentOrNull,
                    )
                    val timestamp = requireNotNull(
                        value.getValue("enqueuedAtEpochMillis").jsonPrimitive.longOrNull,
                    )
                    val status = PipelineQueueStatus.valueOf(
                        requireNotNull(value.getValue("status").jsonPrimitive.contentOrNull),
                    )
                    val error = value["errorCode"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                    val legacyFailureCount = value["consecutiveFailureCount"]?.jsonPrimitive?.intOrNull
                    val legacyRetryAt = value["retryNotBeforeEpochMillis"]?.jsonPrimitive?.longOrNull
                    if (
                        SAFE_ID.matches(projectId) &&
                        timestamp >= 0L &&
                        (legacyFailureCount == null || legacyFailureCount in 0..MAXIMUM_RECORDED_FAILURE_COUNT) &&
                        (legacyRetryAt == null || legacyRetryAt >= 0L) &&
                        (error == null || ERROR_CODE.matches(error))
                    ) {
                        val legacyFailurePending = status == PipelineQueueStatus.ACTIVE &&
                            (error != null || (legacyFailureCount ?: 0) > 0 || (legacyRetryAt ?: 0L) > 0L)
                        add(
                            PipelineQueueEntry(
                                projectId = projectId,
                                enqueuedAtEpochMillis = timestamp,
                                status = if (legacyFailurePending) PipelineQueueStatus.PAUSED else status,
                                errorCode = if (legacyFailurePending) error ?: "STAGE_FAILED" else error,
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
