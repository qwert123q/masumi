package rs.masumi.app.library

import rs.masumi.app.pipeline.PipelineQueueStatus

internal fun isFinishedLibraryProject(
    outputPageCount: Int,
    queueStatus: PipelineQueueStatus?,
): Boolean = outputPageCount > 0 && queueStatus == null
