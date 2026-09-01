package rs.masumi.core.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.exporting.ExportReport
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingReport

class StageJsonCompatibilityTest {
    @Test
    fun `cleanup JSON ignores removed legacy fields`() {
        val report = CleanupReport(
            jobId = "job",
            projectId = "project",
            runArtifactKey = "run",
            startedAtEpochMillis = 1,
            finishedAtEpochMillis = 2,
            status = CleanupJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            cleanedRegionCount = 1,
            preservedRegionCount = 0,
            changedPixelCount = 1,
            retryCount = 0,
        )
        val codec = CleanupJson()

        assertEquals(report, codec.decodeReport(withLegacyField(codec.encodeReport(report), "cleanedImageSha256")))
    }

    @Test
    fun `typesetting JSON ignores removed legacy fields`() {
        val report = TypesettingReport(
            jobId = "job",
            projectId = "project",
            runArtifactKey = "run",
            startedAtEpochMillis = 1,
            finishedAtEpochMillis = 2,
            status = TypesettingJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            typesetRegionCount = 1,
            preservedRegionCount = 0,
            changedPixelCount = 1,
            retryCount = 0,
        )
        val codec = TypesettingJson()

        assertEquals(report, codec.decodeReport(withLegacyField(codec.encodeReport(report), "renderedImageSha256")))
    }

    @Test
    fun `export JSON ignores removed legacy fields`() {
        val report = ExportReport(
            jobId = "job",
            projectId = "project",
            exportKey = "export",
            destinationKey = "destination",
            typesettingRunArtifactKey = "typesetting-run",
            startedAtEpochMillis = 1,
            finishedAtEpochMillis = 2,
            status = ExportJobStatus.SUCCEEDED,
            totalPageCount = 1,
            exportedPageCount = 1,
            flattenedPageCount = 1,
            cleanedFallbackPageCount = 0,
            sourceFallbackPageCount = 0,
            reusedPageCount = 0,
            totalByteCount = 1,
            retryCount = 0,
        )
        val codec = ExportJson()

        assertEquals(report, codec.decodeReport(withLegacyField(codec.encodeReport(report), "outputSha256")))
    }

    private fun withLegacyField(encoded: String, name: String): String =
        encoded.replaceFirst("{", "{\"$name\":\"legacy\",")
}
