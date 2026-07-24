package rs.masumi.core.cleanup

import java.security.MessageDigest
import rs.masumi.core.detection.PixelBox

object CleanupFixtures {
    val dependencies = CleanupDependencies(translationRunArtifactKey = "a".repeat(64))
    val pageKey = CleanupIdentity.pageArtifactKey(0, "b".repeat(64), "c".repeat(64), dependencies)

    fun job(): CleanupJobRecord = CleanupJobRecord(
        jobId = "cleanup-job",
        projectId = "project-1",
        runArtifactKey = CleanupIdentity.runArtifactKey(listOf(0 to pageKey), dependencies),
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        pages = listOf(
            CleanupJobPage(
                pageId = "b".repeat(64),
                pageOrder = 0,
                sourceSha256 = "b".repeat(64),
                translationPageArtifactKey = "c".repeat(64),
                pageArtifactKey = pageKey,
            ),
        ),
    )

    fun artifact(png: ByteArray): PageCleanupArtifact = PageCleanupArtifact(
        pageId = "b".repeat(64),
        pageOrder = 0,
        sourceSha256 = "b".repeat(64),
        translationPageArtifactKey = "c".repeat(64),
        pageArtifactKey = pageKey,
        visibleWidth = 20,
        visibleHeight = 20,
        cleanedImageSha256 = sha256(png),
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
            ),
        ),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
