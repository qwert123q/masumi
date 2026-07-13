package rs.masumi.core.importer

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.model.ImportReport
import rs.masumi.core.model.ImportStatus
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.serialization.ProjectJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock

data class ImportOutcome(
    val projectDirectory: Path,
    val manifest: ProjectManifest,
    val report: ImportReport,
)

class ProjectImporter(
    private val workspaceRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
    private val json: ProjectJson = ProjectJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    fun importProject(sources: List<SourceCandidate>): ImportOutcome {
        val projectId = idSource.nextId()
        val jobId = idSource.nextId()
        val startedAt = clock.millis()
        val selection = SourceSelector.select(sources)
        val stagingDirectory = workspaceRoot.resolve("staging").resolve(projectId)
        val projectDirectory = workspaceRoot.resolve("projects").resolve(projectId)
        val stagingSources = stagingDirectory.resolve("sources")
        val stagingReports = stagingDirectory.resolve("reports")
        val stagingTemporary = stagingDirectory.resolve("tmp")

        check(!fileSystem.exists(stagingDirectory)) { "Staging project already exists" }
        check(!fileSystem.exists(projectDirectory)) { "Published project already exists" }

        fileSystem.createDirectories(stagingSources)
        fileSystem.createDirectories(stagingReports)
        fileSystem.createDirectories(stagingTemporary)

        var byteCount = 0L
        val pages = selection.accepted.mapIndexed { order, selected ->
            val temporaryFile = stagingTemporary.resolve("$order.part")
            val copied = copyAndHash(selected.source, temporaryFile)
            byteCount += copied.byteCount
            val storedPath = "sources/${copied.sha256}.${selected.mediaType.extension}"
            val storedFile = stagingDirectory.resolve(storedPath)

            if (fileSystem.exists(storedFile)) {
                fileSystem.deleteIfExists(temporaryFile)
            } else {
                fileSystem.moveFile(temporaryFile, storedFile)
            }

            PageRecord(
                order = order,
                pageId = copied.sha256,
                sourceSha256 = copied.sha256,
                originalName = selected.source.displayName,
                mediaType = selected.mediaType.mimeType,
                byteLength = copied.byteCount,
                storedPath = storedPath,
            )
        }

        fileSystem.deleteIfExists(stagingTemporary)

        val manifest = ProjectManifest(
            projectId = projectId,
            createdAtEpochMillis = startedAt,
            pages = pages,
        )
        val report = ImportReport(
            jobId = jobId,
            projectId = projectId,
            startedAtEpochMillis = startedAt,
            finishedAtEpochMillis = clock.millis(),
            status = ImportStatus.SUCCEEDED,
            discoveredCount = sources.size,
            acceptedCount = selection.accepted.size,
            importedCount = pages.size,
            skippedCount = selection.skippedCount,
            byteCount = byteCount,
        )

        fileSystem.writeUtf8(stagingDirectory.resolve("manifest.json"), json.encodeManifest(manifest))
        fileSystem.writeUtf8(stagingReports.resolve("$jobId.json"), json.encodeReport(report))
        fileSystem.writeUtf8(stagingReports.resolve("$jobId.txt"), report.toText())
        fileSystem.createDirectories(projectDirectory.parent)
        fileSystem.publishDirectory(stagingDirectory, projectDirectory)

        return ImportOutcome(
            projectDirectory = projectDirectory,
            manifest = manifest,
            report = report,
        )
    }

    private fun copyAndHash(source: SourceCandidate, target: Path): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L

        BufferedInputStream(source.openStream()).use { input ->
            BufferedOutputStream(fileSystem.newOutputStream(target)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    byteCount += read
                }
                output.flush()
            }
        }

        return CopyResult(
            sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
            byteCount = byteCount,
        )
    }

    private fun ImportReport.toText(): String = buildString {
        appendLine("Masumi import report")
        appendLine("Status: $status")
        appendLine("Project: $projectId")
        appendLine("Job: $jobId")
        appendLine("Imported pages: $importedCount")
        appendLine("Skipped entries: $skippedCount")
        appendLine("Bytes copied: $byteCount")
    }

    private data class CopyResult(
        val sha256: String,
        val byteCount: Long,
    )
}
