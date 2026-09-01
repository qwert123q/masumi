package rs.masumi.app.importing

import java.util.concurrent.Executors
import rs.masumi.app.pipeline.PipelineQueueStore
import rs.masumi.app.pipeline.PipelineThreading

internal data class ImportedMangaPipelineRegistration(
    val hasTranslationSettings: Boolean,
    val enqueuedProjectCount: Int,
)

internal class ImportedMangaPipelineRegistrar(
    private val queueStore: PipelineQueueStore,
    private val hasTranslationSettings: () -> Boolean,
    private val wakeScheduler: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun register(projectIds: List<String>): ImportedMangaPipelineRegistration {
        val configured = hasTranslationSettings()
        if (!configured || projectIds.isEmpty()) {
            return ImportedMangaPipelineRegistration(configured, enqueuedProjectCount = 0)
        }
        val enqueued = queueStore.enqueueIfAbsent(projectIds, clock())
        if (enqueued.isNotEmpty()) runCatching(wakeScheduler)
        return ImportedMangaPipelineRegistration(configured, enqueued.size)
    }
}

internal object SelectedMangaImportProcessExecutor {
    private val executor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory("masumi-library-import"),
    )

    fun execute(task: Runnable) {
        executor.execute(task)
    }
}
