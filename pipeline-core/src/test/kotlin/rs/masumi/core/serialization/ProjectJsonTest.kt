package rs.masumi.core.serialization

import rs.masumi.core.model.ImportError
import rs.masumi.core.model.ImportErrorCode
import rs.masumi.core.model.ImportReport
import rs.masumi.core.model.ImportStatus
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectJsonTest {
    private val codec = ProjectJson()

    @Test
    fun `manifest round trip preserves stable page identity`() {
        val manifest = ProjectManifest(
            projectId = "project-1",
            createdAtEpochMillis = 1_000,
            pages = listOf(
                PageRecord(
                    order = 0,
                    pageId = "abc",
                    sourceSha256 = "abc",
                    originalName = "001.jpg",
                    mediaType = "image/jpeg",
                    byteLength = 3,
                    storedPath = "sources/abc.jpg",
                ),
            ),
        )

        assertEquals(manifest, codec.decodeManifest(codec.encodeManifest(manifest)))
    }

    @Test
    fun `report round trip preserves structured failure`() {
        val report = ImportReport(
            jobId = "job-1",
            projectId = "project-1",
            startedAtEpochMillis = 1_000,
            finishedAtEpochMillis = 2_000,
            status = ImportStatus.FAILED,
            discoveredCount = 2,
            acceptedCount = 1,
            importedCount = 0,
            skippedCount = 1,
            byteCount = 0,
            warnings = emptyList(),
            error = ImportError(
                code = ImportErrorCode.IMPORT_IO_FAILED,
                message = "A source page could not be imported.",
            ),
        )

        assertEquals(report, codec.decodeReport(codec.encodeReport(report)))
    }
}
