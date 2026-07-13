package rs.masumi.core.importer

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.model.ImportError
import rs.masumi.core.model.ImportErrorCode
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
        var byteCount = 0L
        var importedCount = 0

        return try {
            if (selection.accepted.isEmpty()) {
                throw ProjectImportException(
                    code = ImportErrorCode.NO_SUPPORTED_PAGES,
                    reportRelativePath = failureReportPath(jobId),
                )
            }
            if (fileSystem.exists(stagingDirectory) || fileSystem.exists(projectDirectory)) {
                throw ProjectImportException(
                    code = ImportErrorCode.PROJECT_ALREADY_EXISTS,
                    reportRelativePath = failureReportPath(jobId),
                )
            }

            fileSystem.createDirectories(stagingSources)
            fileSystem.createDirectories(stagingReports)
            fileSystem.createDirectories(stagingTemporary)

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

                importedCount += 1
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

            ImportOutcome(
                projectDirectory = projectDirectory,
                manifest = manifest,
                report = report,
            )
        } catch (failure: Throwable) {
            val code = (failure as? ProjectImportException)?.code ?: ImportErrorCode.IMPORT_IO_FAILED
            runCatching { fileSystem.deleteRecursively(stagingDirectory) }
            writeFailureReports(
                jobId = jobId,
                projectId = projectId,
                startedAt = startedAt,
                sources = sources,
                selection = selection,
                importedCount = importedCount,
                byteCount = byteCount,
                code = code,
            )
            throw ProjectImportException(
                code = code,
                reportRelativePath = failureReportPath(jobId),
                cause = failure.takeUnless { it is ProjectImportException },
            )
        }
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
        error?.let { failure ->
            appendLine("Error code: ${failure.code}")
            appendLine("Error: ${failure.message}")
        }
    }

    private fun writeFailureReports(
        jobId: String,
        projectId: String,
        startedAt: Long,
        sources: List<SourceCandidate>,
        selection: SourceSelection,
        importedCount: Int,
        byteCount: Long,
        code: ImportErrorCode,
    ) {
        runCatching {
            val failureDirectory = workspaceRoot.resolve("failed-reports")
            val report = ImportReport(
                jobId = jobId,
                projectId = projectId,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = clock.millis(),
                status = ImportStatus.FAILED,
                discoveredCount = sources.size,
                acceptedCount = selection.accepted.size,
                importedCount = importedCount,
                skippedCount = selection.skippedCount,
                byteCount = byteCount,
                error = ImportError(code = code, message = code.safeMessage()),
            )
            fileSystem.createDirectories(failureDirectory)
            fileSystem.writeUtf8(failureDirectory.resolve("$jobId.json"), json.encodeReport(report))
            fileSystem.writeUtf8(failureDirectory.resolve("$jobId.txt"), report.toText())
        }
    }

    private fun failureReportPath(jobId: String): String = "failed-reports/$jobId.json"

    private data class CopyResult(
        val sha256: String,
        val byteCount: Long,
    )
}
