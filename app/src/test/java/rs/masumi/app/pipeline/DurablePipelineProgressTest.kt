package rs.masumi.app.pipeline

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.cleanup.CleanupProgress
import rs.masumi.app.detection.ProjectRef
import rs.masumi.core.cleanup.CleanupArtifactStore
import rs.masumi.core.cleanup.CleanupJobRecord
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.model.ProjectManifest

class DurablePipelineProgressTest {
    @Test
    fun `cleanup progress rejects a job from an older neural model identity`() {
        val directory = Files.createTempDirectory("masumi-durable-cleanup")
        try {
            val translationRun = "translation-run-current"
            val currentDependencies = currentCleanupDependencies(translationRun)
            val staleDependencies = currentDependencies.copy(neuralModel = null)
            val staleRunId = "cleanup-run-stale-model"
            val currentRunId = "cleanup-run-current-model"
            val store = CleanupArtifactStore(directory)
            store.writeJob(
                CleanupJobRecord(
                    jobId = "stale-cleanup-job",
                    projectId = "project",
                    runArtifactKey = staleRunId,
                    startedAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    status = CleanupJobStatus.QUEUED,
                    dependencies = staleDependencies,
                    pages = emptyList(),
                ),
            )
            store.writeJob(
                CleanupJobRecord(
                    jobId = "current-cleanup-job",
                    projectId = "project",
                    runArtifactKey = currentRunId,
                    startedAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    status = CleanupJobStatus.QUEUED,
                    dependencies = currentDependencies,
                    pages = emptyList(),
                ),
            )
            val project = ProjectRef(
                directory,
                ProjectManifest(
                    projectId = "project",
                    createdAtEpochMillis = 0L,
                    pages = emptyList(),
                ),
            )

            assertFalse(
                DurablePipelineProgress.cleanupMatchesTranslation(
                    project,
                    progress("stale-cleanup-job", staleRunId),
                    translationRun,
                ),
            )
            assertTrue(
                DurablePipelineProgress.cleanupMatchesTranslation(
                    project,
                    progress("current-cleanup-job", currentRunId),
                    translationRun,
                ),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun progress(jobId: String, runArtifactKey: String) = CleanupProgress(
        projectId = "project",
        jobId = jobId,
        runArtifactKey = runArtifactKey,
        status = CleanupJobStatus.QUEUED,
        terminalPageCount = 0,
        totalPageCount = 0,
        cleanedRegionCount = 0,
        preservedRegionCount = 0,
    )
}
