package rs.masumi.core.exporting

import java.nio.file.Path
import rs.masumi.core.identity.SafeOpaqueId
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.ExportJson

class ExportArtifactStore(
    projectDirectory: Path,
    private val json: ExportJson = ExportJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val projectDirectory = projectDirectory.toAbsolutePath().normalize()
    private val jobsDirectory = this.projectDirectory.resolve("jobs/export")
    private val reportsDirectory = this.projectDirectory.resolve("reports/export")

    fun writeJob(job: ExportJobRecord) {
        requireValidJob(job)
        fileSystem.createDirectories(jobsDirectory)
        fileSystem.replaceUtf8(jobsDirectory.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readJob(jobId: String): ExportJobRecord? {
        SafeOpaqueId.require(jobId, "jobId")
        val path = jobsDirectory.resolve("$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeJob(fileSystem.readUtf8(path)).also {
                require(it.jobId == jobId)
                requireValidJob(it)
            }
        }.getOrNull()
    }

    fun findLatestJob(): ExportJobRecord? = fileSystem.list(jobsDirectory)
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter { runCatching { requireValidJob(it) }.isSuccess }
        .maxWithOrNull(compareBy<ExportJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun findRecoveryCandidates(): List<ExportJobRecord> = fileSystem.list(jobsDirectory)
        .asSequence()
        .filter { it.fileName.toString().endsWith(".json") }
        .mapNotNull { runCatching { json.decodeJob(fileSystem.readUtf8(it)) }.getOrNull() }
        .filter {
            it.status in setOf(
                ExportJobStatus.QUEUED,
                ExportJobStatus.RUNNING,
                ExportJobStatus.CANCELLED,
                ExportJobStatus.FAILED,
            )
        }
        .filter { runCatching { requireValidJob(it) }.isSuccess }
        .sortedWith(compareByDescending<ExportJobRecord> { it.updatedAtEpochMillis }.thenByDescending { it.jobId })
        .toList()

    fun findRecoverableJob(): ExportJobRecord? = findRecoveryCandidates().firstOrNull()

    fun writeReport(report: ExportReport) {
        requireValidReport(report)
        requireReportMatchesJob(report, requireNotNull(readJob(report.jobId)))
        fileSystem.createDirectories(reportsDirectory)
        fileSystem.replaceUtf8(reportsDirectory.resolve("${report.jobId}.json"), json.encodeReport(report))
    }

    /**
     * Commits completion with the report first and the successful job as the
     * final marker. A crash between the writes leaves the older job recoverable;
     * observing SUCCEEDED therefore guarantees that its matching report exists.
     */
    fun commitSuccessfulExport(job: ExportJobRecord, report: ExportReport) {
        requireValidJob(job)
        require(job.status == ExportJobStatus.SUCCEEDED)
        requireValidReport(report)
        requireReportMatchesJob(report, job)
        fileSystem.createDirectories(reportsDirectory)
        fileSystem.createDirectories(jobsDirectory)
        fileSystem.replaceUtf8(reportsDirectory.resolve("${report.jobId}.json"), json.encodeReport(report))
        fileSystem.replaceUtf8(jobsDirectory.resolve("${job.jobId}.json"), json.encodeJob(job))
    }

    fun readReport(jobId: String): ExportReport? {
        SafeOpaqueId.require(jobId, "jobId")
        val path = reportsDirectory.resolve("$jobId.json")
        if (!fileSystem.exists(path)) return null
        return runCatching {
            json.decodeReport(fileSystem.readUtf8(path)).also {
                require(it.jobId == jobId)
                requireValidReport(it)
                requireReportMatchesJob(it, requireNotNull(readJob(jobId)))
            }
        }.getOrNull()
    }

    private fun requireValidJob(job: ExportJobRecord) {
        require(job.schemaVersion == EXPORT_SCHEMA_VERSION)
        SafeOpaqueId.require(job.jobId, "jobId")
        SafeOpaqueId.require(job.projectId, "projectId")
        SafeOpaqueId.require(job.exportKey, "exportKey")
        SafeOpaqueId.require(job.destinationKey, "destinationKey")
        SafeOpaqueId.require(job.dependencies.typesettingRunArtifactKey, "typesettingRunArtifactKey")
        if (job.dependencies.qualityRunArtifactKey.isNotEmpty()) {
            SafeOpaqueId.require(job.dependencies.qualityRunArtifactKey, "qualityRunArtifactKey")
        }
        require(job.destinationUri.isNotBlank())
        require(job.pages.isNotEmpty())
        require(job.pages.map { it.pageOrder } == job.pages.indices.toList())
        require(job.pages.map { it.outputName }.distinct().size == job.pages.size)
        job.pages.forEach { page ->
            SafeOpaqueId.require(page.pageId, "pageId")
            SafeOpaqueId.require(page.typesettingPageArtifactKey, "typesettingPageArtifactKey")
            require(OUTPUT_NAME.matches(page.outputName))
            // The extension follows the artifact encoding of the page's source,
            // so identity is validated for the extension the page carries.
            require(
                page.outputName == ExportIdentity.outputName(
                    page.pageOrder,
                    job.pages.size,
                    job.dependencies.policy,
                    page.outputName.substringAfterLast('.'),
                ),
            )
            require(page.attemptCount >= 0 && page.byteLength >= 0L)
            if (page.state == ExportPageState.COMMITTED) {
                require(page.byteLength > 0L && page.error == null)
            } else {
                require(page.byteLength == 0L)
            }
        }
    }

    private fun requireValidReport(report: ExportReport) {
        require(report.schemaVersion == EXPORT_SCHEMA_VERSION)
        SafeOpaqueId.require(report.jobId, "jobId")
        SafeOpaqueId.require(report.projectId, "projectId")
        SafeOpaqueId.require(report.exportKey, "exportKey")
        SafeOpaqueId.require(report.destinationKey, "destinationKey")
        SafeOpaqueId.require(report.typesettingRunArtifactKey, "typesettingRunArtifactKey")
        if (report.qualityRunArtifactKey.isNotEmpty()) {
            SafeOpaqueId.require(report.qualityRunArtifactKey, "qualityRunArtifactKey")
        }
        require(report.status == ExportJobStatus.SUCCEEDED && report.error == null)
        require(report.totalPageCount > 0 && report.exportedPageCount == report.totalPageCount)
        require(
            report.flattenedPageCount + report.cleanedFallbackPageCount + report.sourceFallbackPageCount ==
                report.totalPageCount,
        )
        require(report.reusedPageCount in 0..report.totalPageCount)
        require(report.totalByteCount > 0L && report.retryCount >= 0)
    }

    private fun requireReportMatchesJob(report: ExportReport, job: ExportJobRecord) {
        require(job.status == ExportJobStatus.SUCCEEDED)
        require(report.projectId == job.projectId)
        require(report.exportKey == job.exportKey)
        require(report.destinationKey == job.destinationKey)
        require(report.typesettingRunArtifactKey == job.dependencies.typesettingRunArtifactKey)
        require(report.qualityRunArtifactKey == job.dependencies.qualityRunArtifactKey)
        require(report.totalPageCount == job.pages.size)
        require(report.exportedPageCount == job.pages.count { it.state == ExportPageState.COMMITTED })
        require(report.flattenedPageCount == job.pages.count { it.source == ExportPageSource.FLATTENED })
        require(report.cleanedFallbackPageCount == job.pages.count { it.source == ExportPageSource.CLEANED_FALLBACK })
        require(report.sourceFallbackPageCount == job.pages.count { it.source == ExportPageSource.SOURCE_FALLBACK })
        require(report.reusedPageCount == job.pages.count { it.reusedExisting })
        require(report.totalByteCount == job.pages.sumOf { it.byteLength })
    }

    private companion object {
        val OUTPUT_NAME = Regex("[0-9]{1,12}\\.(png|webp)")
    }
}
