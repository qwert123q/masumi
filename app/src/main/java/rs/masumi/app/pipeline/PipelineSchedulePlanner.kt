package rs.masumi.app.pipeline

internal enum class PipelineStage {
    DETECTION,
    OCR,
    TRANSLATION,
    CLEANUP,
    TYPESETTING,
    EXPORT,
}

internal enum class PipelineLane {
    LOCAL,
    NETWORK,
}

internal data class ScheduledProject(
    val projectId: String,
    val queueOrder: Long,
    val pageCount: Int,
    val nextStage: PipelineStage?,
    val waitingForSettings: Boolean = false,
    val blocked: Boolean = false,
)

internal data class RunningPipelineTask(
    val projectId: String,
    val stage: PipelineStage,
)

internal data class PipelineLaunch(
    val projectId: String,
    val stage: PipelineStage,
)

internal object PipelineTrackedTaskPolicy {
    fun shouldRelease(
        queueActive: Boolean,
        state: ProjectPipelineState?,
        expectedStage: PipelineStage,
    ): Boolean = !queueActive ||
        state == null ||
        state.complete ||
        state.blocked ||
        state.nextStage != expectedStage
}

internal object PipelineSchedulePlanner {
    fun lane(stage: PipelineStage): PipelineLane = when (stage) {
        PipelineStage.TRANSLATION -> PipelineLane.NETWORK
        PipelineStage.DETECTION,
        PipelineStage.OCR,
        PipelineStage.CLEANUP,
        PipelineStage.TYPESETTING,
        PipelineStage.EXPORT,
        -> PipelineLane.LOCAL
    }

    fun plan(
        projects: List<ScheduledProject>,
        running: Set<RunningPipelineTask>,
        translationCapacity: Int,
        readerForeground: Boolean = false,
    ): List<PipelineLaunch> {
        require(translationCapacity >= 1)
        val runnable = projects
            .filter { it.nextStage != null && !it.waitingForSettings && !it.blocked }
            .filterNot { project -> running.any { it.projectId == project.projectId } }
            .filterNot { project ->
                readerForeground && project.nextStage in setOf(PipelineStage.TRANSLATION, PipelineStage.CLEANUP)
            }
        val launches = mutableListOf<PipelineLaunch>()

        val networkInUse = running.count { lane(it.stage) == PipelineLane.NETWORK }
        runnable.asSequence()
            .filter { lane(requireNotNull(it.nextStage)) == PipelineLane.NETWORK }
            .sortedWith(compareBy<ScheduledProject>(ScheduledProject::pageCount).then(projectOrder()))
            .take((translationCapacity - networkInUse).coerceAtLeast(0))
            .forEach { launches += PipelineLaunch(it.projectId, requireNotNull(it.nextStage)) }

        if (running.none { lane(it.stage) == PipelineLane.LOCAL }) {
            runnable.asSequence()
                .filter { lane(requireNotNull(it.nextStage)) == PipelineLane.LOCAL }
                .sortedWith(localPriority(networkInUse + launches.size, translationCapacity))
                .firstOrNull()
                ?.let { launches += PipelineLaunch(it.projectId, requireNotNull(it.nextStage)) }
        }
        return launches
    }

    private fun localPriority(
        translationsAfterLaunch: Int,
        translationCapacity: Int,
    ): Comparator<ScheduledProject> = compareBy<ScheduledProject> { project ->
        val stage = requireNotNull(project.nextStage)
        when {
            translationsAfterLaunch < translationCapacity && stage == PipelineStage.OCR -> 0
            translationsAfterLaunch < translationCapacity && stage == PipelineStage.DETECTION -> 1
            stage == PipelineStage.CLEANUP -> 2
            stage == PipelineStage.TYPESETTING -> 3
            stage == PipelineStage.EXPORT -> 4
            stage == PipelineStage.OCR -> 5
            else -> 6
        }
    }.thenBy { project ->
        if (project.nextStage == PipelineStage.DETECTION || project.nextStage == PipelineStage.OCR) {
            project.pageCount
        } else {
            0
        }
    }.then(projectOrder())

    private fun projectOrder(): Comparator<ScheduledProject> =
        compareBy<ScheduledProject>(ScheduledProject::queueOrder)
            .thenBy(ScheduledProject::pageCount)
            .thenBy(ScheduledProject::projectId)
}
