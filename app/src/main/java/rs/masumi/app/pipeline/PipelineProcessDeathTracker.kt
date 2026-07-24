package rs.masumi.app.pipeline

internal class PipelineProcessDeathTracker(
    private val retriesBeforePause: Int = 1,
) {
    private val failures = mutableMapOf<String, Int>()

    init {
        require(retriesBeforePause >= 0)
    }

    @Synchronized
    fun record(projectId: String): Boolean {
        val count = failures.getOrDefault(projectId, 0) + 1
        failures[projectId] = count
        return count > retriesBeforePause
    }

    @Synchronized
    fun clear(projectId: String) {
        failures.remove(projectId)
    }
}
