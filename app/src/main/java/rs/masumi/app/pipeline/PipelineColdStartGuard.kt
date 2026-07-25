package rs.masumi.app.pipeline

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts queue entries that were left ACTIVE by a killed process into
 * paused entries on the first UI entry of a fresh process. Removing the app
 * from recents must stop all pipeline work; when the swipe also kills the
 * process before [PipelineSchedulerService.onTaskRemoved] can run, the queue
 * would otherwise auto-continue on the next launch. Within a living process
 * the guard trips once and never interferes with ongoing background work.
 */
internal object PipelineColdStartGuard {
    const val APP_CLOSED_ERROR_CODE = "APP_CLOSED"

    private val reconciled = AtomicBoolean(false)

    fun reconcile(queueStore: PipelineQueueStore) {
        if (!reconciled.compareAndSet(false, true)) return
        queueStore.entries()
            .filter { it.status == PipelineQueueStatus.ACTIVE }
            .forEach { queueStore.pause(it.projectId, APP_CLOSED_ERROR_CODE) }
    }
}
