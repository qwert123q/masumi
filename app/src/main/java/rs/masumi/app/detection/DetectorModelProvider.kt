package rs.masumi.app.detection

import rs.masumi.core.modelpackage.DetectorModelDescriptor
import rs.masumi.core.modelpackage.DetectorModelPackageStore
import rs.masumi.core.modelpackage.ModelSignatureValidator
import rs.masumi.core.modelpackage.PinnedComicDetector
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.time.Clock

fun interface ModelStreamSource {
    fun open(descriptor: DetectorModelDescriptor): InputStream
}

class DefaultDetectorModelProvider(
    workspaceRoot: Path,
    private val descriptor: DetectorModelDescriptor = PinnedComicDetector.descriptor,
    private val streamSource: ModelStreamSource = HttpModelStreamSource(),
    private val signatureValidator: ModelSignatureValidator,
    private val clock: Clock = Clock.systemUTC(),
) : DetectorModelProvider {
    private val store = DetectorModelPackageStore(workspaceRoot)

    override fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): Path = store.ensureInstalled(
        installId = installId,
        descriptor = descriptor,
        acquiredAtEpochMillis = clock.millis(),
        openStream = { streamSource.open(descriptor) },
        signatureValidator = signatureValidator,
        onProgress = progress,
    )
}

class HttpModelStreamSource : ModelStreamSource {
    override fun open(descriptor: DetectorModelDescriptor): InputStream {
        val connection = (URL(descriptor.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Model request failed with an HTTP status")
            }
            return object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 120_000
    }
}
