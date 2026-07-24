package rs.masumi.core.typesetting

import java.security.MessageDigest
import rs.masumi.core.detection.PixelBox

object TypesettingFixtures {
    val dependencies = TypesettingDependencies(cleanupRunArtifactKey = "a".repeat(64))

    fun job(): TypesettingJobRecord = TypesettingJobRecord(
        jobId = "typesetting-job",
        projectId = "project-1",
        runArtifactKey = "b".repeat(64),
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        pages = listOf(
            TypesettingJobPage(
                pageId = "c".repeat(64),
                pageOrder = 0,
                sourceSha256 = "c".repeat(64),
                cleanupPageArtifactKey = "d".repeat(64),
                pageArtifactKey = "e".repeat(64),
            ),
        ),
    )

    fun artifact(png: ByteArray): PageTypesettingArtifact = PageTypesettingArtifact(
        pageId = "c".repeat(64),
        pageOrder = 0,
        sourceSha256 = "c".repeat(64),
        cleanupPageArtifactKey = "d".repeat(64),
        pageArtifactKey = "e".repeat(64),
        visibleWidth = 100,
        visibleHeight = 200,
        renderedImageSha256 = sha256(png),
        dependencies = dependencies,
        regions = listOf(
            TypesettingRegionArtifact(
                translationRegionId = "f".repeat(64),
                ocrRegionId = "1".repeat(64),
                targetBox = PixelBox(10.0, 20.0, 80.0, 160.0),
                layoutBox = PixelBox(15.0, 25.0, 75.0, 155.0),
                style = TypesettingStyle.BUBBLE,
                direction = TypesettingDirection.VERTICAL_RTL,
                fontSizePx = 18.0,
                lineOrColumnCount = 2,
                changedPixelCount = 50,
                state = TypesettingRegionState.TYPESET,
            ),
        ),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
