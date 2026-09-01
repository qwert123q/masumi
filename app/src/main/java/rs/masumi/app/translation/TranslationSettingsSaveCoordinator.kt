package rs.masumi.app.translation

import rs.masumi.app.pipeline.PipelineQueueStore

internal class TranslationSettingsSaveCoordinator(
    private val pipelineQueueStore: PipelineQueueStore,
) {
    fun onSettingsSaved(
        projectId: String?,
        refreshDurableState: () -> Unit,
        wakeScheduler: () -> Unit,
    ): Boolean {
        refreshDurableState()
        wakeScheduler()
        return projectId?.let(pipelineQueueStore::isActive) ?: false
    }
}
