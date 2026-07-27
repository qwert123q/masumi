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
import java.nio.file.Files
import java.time.Clock
import rs.masumi.core.serialization.DetectionJson

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
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val store = DetectorModelPackageStore(this.workspaceRoot)
    private val json = DetectionJson()

    override fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): Path {
        val packageDirectory = packageDirectory()
        return readTrustedPrivatePackage(packageDirectory)
            ?: store.ensureInstalled(
                installId = installId,
                descriptor = descriptor,
                acquiredAtEpochMillis = clock.millis(),
                openStream = { streamSource.open(descriptor) },
                signatureValidator = signatureValidator,
                onProgress = progress,
            )
    }

    private fun readTrustedPrivatePackage(directory: Path): Path? = runCatching {
        val model = directory.resolve(MODEL_FILE_NAME)
        val metadataPath = directory.resolve(PACKAGE_METADATA_FILE_NAME)
        require(Files.isRegularFile(model) && Files.size(model) == descriptor.byteLength)
        require(Files.isRegularFile(metadataPath))
        require(
            Files.getLastModifiedTime(model).toMillis() <=
                Files.getLastModifiedTime(metadataPath).toMillis(),
        )
        val metadata = Files.newBufferedReader(metadataPath).use {
            json.decodeModelPackageMetadata(it.readText())
        }
        require(metadata.schemaVersion == 1 && metadata.model == descriptor.toModelRef())
        model
    }.getOrNull()

    private fun packageDirectory(): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)
        .resolve(descriptor.sha256)

    private companion object {
        const val MODEL_FILE_NAME = "model.onnx"
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
    }
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
