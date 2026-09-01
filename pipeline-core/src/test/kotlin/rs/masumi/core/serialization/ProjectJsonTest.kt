package rs.masumi.core.serialization

import rs.masumi.core.model.ImportError
import rs.masumi.core.model.ImportErrorCode
import rs.masumi.core.model.ImportReport
import rs.masumi.core.model.ImportStatus
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `legacy source digest is ignored without changing its page identity or stored path`() {
        val manifest = codec.decodeManifest(
            """
            {
              "schemaVersion": 1,
              "projectId": "project-legacy",
              "createdAtEpochMillis": 1000,
              "pages": [
                {
                  "order": 0,
                  "pageId": "legacy-page-id",
                  "sourceSha256": "${"a".repeat(64)}",
                  "originalName": "001.jpg",
                  "mediaType": "image/jpeg",
                  "byteLength": 3,
                  "storedPath": "sources/legacy-content-name.jpg"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("legacy-page-id", manifest.pages.single().pageId)
        assertEquals("sources/legacy-content-name.jpg", manifest.pages.single().storedPath)
        assertFalse(codec.encodeManifest(manifest).contains("sourceSha256"))
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
