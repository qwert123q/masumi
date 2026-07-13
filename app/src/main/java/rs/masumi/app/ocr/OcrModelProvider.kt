package rs.masumi.app.ocr

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.time.Clock
import rs.masumi.core.modelpackage.InstalledOcrModelPackage
import rs.masumi.core.modelpackage.OcrModelCapabilityValidator
import rs.masumi.core.modelpackage.OcrModelFileDescriptor
import rs.masumi.core.modelpackage.OcrModelPackageDescriptor
import rs.masumi.core.modelpackage.OcrModelPackageStore
import rs.masumi.core.modelpackage.OcrRangeResponse
import rs.masumi.core.modelpackage.OcrRangeSource
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl

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
) : OcrModelProvider {
    private val store = OcrModelPackageStore(
        workspaceRoot = workspaceRoot,
        nowEpochMillis = clock::millis,
    )

    override fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): InstalledOcrModelPackage = store.ensureInstalled(
        installId = installId,
        descriptor = descriptor,
        source = rangeSource,
        capabilityValidator = capabilityValidator,
        onProgress = progress,
    )
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
