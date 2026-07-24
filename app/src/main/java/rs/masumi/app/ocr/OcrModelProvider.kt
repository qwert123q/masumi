package rs.masumi.app.ocr

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import rs.masumi.app.library.MangaLibraryModelCache
import rs.masumi.app.library.PersistentModelFile
import rs.masumi.app.library.PersistentModelPackage
import rs.masumi.core.modelpackage.InstalledOcrModelPackage
import rs.masumi.core.modelpackage.OcrModelCapabilityValidator
import rs.masumi.core.modelpackage.OcrModelFileDescriptor
import rs.masumi.core.modelpackage.OcrModelPackageDescriptor
import rs.masumi.core.modelpackage.OcrModelPackageStore
import rs.masumi.core.modelpackage.OcrRangeResponse
import rs.masumi.core.modelpackage.OcrRangeSource
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.serialization.OcrJson

fun interface OcrModelProvider {
    fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): InstalledOcrModelPackage
}

class DefaultOcrModelProvider(
    workspaceRoot: Path,
    private val descriptor: OcrModelPackageDescriptor = PinnedPaddleOcrVl.descriptor,
    private val rangeSource: OcrRangeSource = HttpOcrRangeSource(),
    private val capabilityValidator: OcrModelCapabilityValidator,
    private val clock: Clock = Clock.systemUTC(),
    private val persistentCache: MangaLibraryModelCache? = null,
) : OcrModelProvider {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val store = OcrModelPackageStore(
        workspaceRoot = this.workspaceRoot,
        nowEpochMillis = clock::millis,
    )
    private val json = OcrJson()

    override fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): InstalledOcrModelPackage {
        val packageDirectory = packageDirectory()
        var installed = readTrustedPrivatePackage(packageDirectory)
        if (installed == null) {
            persistentCache?.restore(
                persistentPackage(),
                packageDirectory,
                progress,
            )
            installed = readTrustedPrivatePackage(packageDirectory)
        }
        if (installed == null) {
            installed = store.ensureInstalled(
                installId = descriptor.packageSha256,
                descriptor = descriptor,
                source = rangeSource,
                capabilityValidator = capabilityValidator,
                onProgress = progress,
            )
        }
        runCatching {
            persistentCache?.backup(persistentPackage(), packageDirectory, progress)
        }
        return installed
    }

    private fun readTrustedPrivatePackage(directory: Path): InstalledOcrModelPackage? = runCatching {
        val model = directory.resolve(descriptor.model.fileName)
        val projector = directory.resolve(descriptor.projector.fileName)
        val metadataPath = directory.resolve(PACKAGE_METADATA_FILE_NAME)
        require(Files.isRegularFile(model) && Files.size(model) == descriptor.model.byteLength)
        require(Files.isRegularFile(projector) && Files.size(projector) == descriptor.projector.byteLength)
        require(Files.isRegularFile(metadataPath))
        val metadataTime = Files.getLastModifiedTime(metadataPath).toMillis()
        require(Files.getLastModifiedTime(model).toMillis() <= metadataTime)
        require(Files.getLastModifiedTime(projector).toMillis() <= metadataTime)
        val metadata = Files.newBufferedReader(metadataPath).use { json.decodeModelPackageMetadata(it.readText()) }
        require(metadata.schemaVersion == 1)
        require(metadata.modelPackage == descriptor.toRef())
        require(metadata.runtime == descriptor.runtime.toRef())
        require(metadata.prompt == descriptor.prompt)
        require(
            metadata.capabilities.vision &&
                metadata.capabilities.embeddedChatTemplate &&
                metadata.capabilities.projectorType.isNotBlank(),
        )
        InstalledOcrModelPackage(model, projector, metadata)
    }.getOrNull()

    private fun packageDirectory(): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)
        .resolve(descriptor.packageSha256)

    private fun persistentPackage(): PersistentModelPackage = PersistentModelPackage(
        cacheKey = "文字识别",
        version = descriptor.packageSha256,
        files = listOf(
            PersistentModelFile(
                descriptor.model.fileName,
                descriptor.model.byteLength,
                descriptor.model.installedSha256,
            ),
            PersistentModelFile(
                descriptor.projector.fileName,
                descriptor.projector.byteLength,
                descriptor.projector.installedSha256,
            ),
        ),
    )

    private companion object {
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
    }
}

class HttpOcrRangeSource : OcrRangeSource {
    override fun open(file: OcrModelFileDescriptor, offset: Long): OcrRangeResponse {
        require(offset >= 0L) { "range offset must not be negative" }
        val connection = (URL(file.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("OCR model request failed with an HTTP status")
            }
            val contentRange = connection.getHeaderField("Content-Range")?.let(::parseContentRange)
            val totalLength = contentRange?.total
                ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L }
            return OcrRangeResponse(
                statusCode = status,
                totalLength = totalLength,
                contentRangeStart = contentRange?.start,
                input = connection.inputStream,
                closeAction = connection::disconnect,
            )
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun parseContentRange(value: String): ParsedContentRange {
        val match = CONTENT_RANGE.matchEntire(value.trim())
            ?: throw IOException("OCR model range response was invalid")
        return ParsedContentRange(
            start = match.groupValues[1].toLong(),
            total = match.groupValues[3].toLong(),
        )
    }

    private data class ParsedContentRange(val start: Long, val total: Long)

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 120_000
    }
}
