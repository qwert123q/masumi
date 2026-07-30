package rs.masumi.app.cleanup

import android.content.res.AssetManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.modelpackage.TextSegmenterModelDescriptor
import rs.masumi.core.modelpackage.TextSegmenterModelPackageStore
import rs.masumi.core.modelpackage.TextSegmenterSignatureValidator
import rs.masumi.core.serialization.CleanupJson

/**
 * Installs the pinned model from the APK into the app-private workspace so
 * ONNX Runtime can memory-map it by path. The package store verifies length,
 * SHA-256, and tensor signature before publishing it atomically.
 */
class BundledTextSegmenterModelProvider(
    private val assets: AssetManager,
    workspaceRoot: Path,
    private val signatureValidator: TextSegmenterSignatureValidator,
    private val descriptor: TextSegmenterModelDescriptor = PinnedComicTextSegmenter.descriptor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    private val store = TextSegmenterModelPackageStore(this.workspaceRoot)
    private val json = CleanupJson()

    fun acquire(installId: String): Path {
        val packageDirectory = packageDirectory()
        return readTrustedPrivatePackage(packageDirectory)
            ?: store.ensureInstalled(
                installId = installId,
                descriptor = descriptor,
                acquiredAtEpochMillis = clock.millis(),
                openStream = { assets.open(descriptor.assetPath, AssetManager.ACCESS_STREAMING) },
                signatureValidator = signatureValidator,
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
            json.decodeTextSegmenterModelPackageMetadata(it.readText())
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
