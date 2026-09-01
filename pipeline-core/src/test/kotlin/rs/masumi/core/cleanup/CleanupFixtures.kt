package rs.masumi.core.cleanup

import rs.masumi.core.detection.PixelBox

object CleanupFixtures {
    val dependencies = CleanupDependencies(translationRunArtifactKey = "a".repeat(64))
    const val runKey = "cleanup-run"
    val pageKey = CleanupIdentity.pageArtifactKey(runKey, 0)

    fun job(): CleanupJobRecord = CleanupJobRecord(
        jobId = "cleanup-job",
        projectId = "project-1",
        runArtifactKey = runKey,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        pages = listOf(
            CleanupJobPage(
                pageId = "b".repeat(64),
                pageOrder = 0,
                translationPageArtifactKey = "c".repeat(64),
                pageArtifactKey = pageKey,
            ),
        ),
    )

    fun artifact(png: ByteArray): PageCleanupArtifact = PageCleanupArtifact(
        pageId = "b".repeat(64),
        pageOrder = 0,
        translationPageArtifactKey = "c".repeat(64),
        pageArtifactKey = pageKey,
        visibleWidth = 20,
        visibleHeight = 20,
        cleanedImageByteLength = png.size.toLong(),
        dependencies = dependencies,
        regions = listOf(
            CleanupRegionArtifact(
                translationRegionId = "d".repeat(64),
                ocrRegionId = "e".repeat(64),
                box = PixelBox(1.0, 1.0, 10.0, 10.0),
                strategy = CleanupStrategy.FLAT_LOCAL_FILL,
                state = CleanupRegionState.CLEANED,
                roiPixelCount = 100,
                maskPixelCount = 20,
                changedPixelCount = 20,
                maskSource = CleanupMaskSource.FLAT_COLOR,
                cleanupAttemptCount = 1,
            ),
        ),
    )
}
