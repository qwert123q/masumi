package rs.masumi.core.exporting

import java.nio.file.Path
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
        requireSafeId(jobId)
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

    fun findRecoverableJob(): ExportJobRecord? = fileSystem.list(jobsDirectory)
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
        .maxWithOrNull(compareBy<ExportJobRecord> { it.updatedAtEpochMillis }.thenBy { it.jobId })

    fun writeReport(report: ExportReport) {
        requireValidReport(report)
        requireReportMatchesJob(report, requireNotNull(readJob(report.jobId)))
        fileSystem.createDirectories(reportsDirectory)
        fileSystem.replaceUtf8(reportsDirectory.resolve("${report.jobId}.json"), json.encodeReport(report))
    }

    fun readReport(jobId: String): ExportReport? {
        requireSafeId(jobId)
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
        requireSafeId(job.jobId)
        requireSafeId(job.projectId)
        requireSha256(job.exportKey)
        requireSha256(job.destinationKey)
        requireSha256(job.dependencies.typesettingRunArtifactKey)
        require(job.destinationUri.isNotBlank())
        require(job.destinationKey == ExportIdentity.destinationKey(job.destinationUri))
        require(job.exportKey == ExportIdentity.exportKey(job.destinationKey, job.dependencies))
        require(job.pages.isNotEmpty())
        require(job.pages.map { it.pageOrder } == job.pages.indices.toList())
        require(job.pages.map { it.outputName }.distinct().size == job.pages.size)
        job.pages.forEach { page ->
            requireSha256(page.pageId)
            requireSha256(page.sourceSha256)
            requireSha256(page.typesettingPageArtifactKey)
            require(OUTPUT_NAME.matches(page.outputName))
            require(page.outputName == ExportIdentity.outputName(page.pageOrder, job.pages.size, job.dependencies.policy))
            require(page.attemptCount >= 0 && page.byteLength >= 0L)
            if (page.state == ExportPageState.COMMITTED) {
                requireSha256(requireNotNull(page.outputSha256))
                require(page.byteLength > 0L && page.error == null)
            } else {
                require(page.outputSha256 == null && page.byteLength == 0L)
            }
        }
    }

    private fun requireValidReport(report: ExportReport) {
        require(report.schemaVersion == EXPORT_SCHEMA_VERSION)
        requireSafeId(report.jobId)
        requireSafeId(report.projectId)
        requireSha256(report.exportKey)
        requireSha256(report.destinationKey)
        requireSha256(report.typesettingRunArtifactKey)
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
        require(report.totalPageCount == job.pages.size)
        require(report.exportedPageCount == job.pages.count { it.state == ExportPageState.COMMITTED })
        require(report.flattenedPageCount == job.pages.count { it.source == ExportPageSource.FLATTENED })
        require(report.cleanedFallbackPageCount == job.pages.count { it.source == ExportPageSource.CLEANED_FALLBACK })
        require(report.sourceFallbackPageCount == job.pages.count { it.source == ExportPageSource.SOURCE_FALLBACK })
        require(report.reusedPageCount == job.pages.count { it.reusedExisting })
        require(report.totalByteCount == job.pages.sumOf { it.byteLength })
    }

    private fun requireSafeId(value: String) = require(SAFE_ID.matches(value))
    private fun requireSha256(value: String) = require(SHA256.matches(value))

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val OUTPUT_NAME = Regex("[0-9]{1,12}\\.png")
    }
}
