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
 * ONNX Runtime can memory-map it by path. The package store verifies length
 * and tensor signature before publishing it atomically.
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
        return findTrustedPrivatePackage()
            ?: store.ensureInstalled(
                installId = installId,
                descriptor = descriptor,
                acquiredAtEpochMillis = clock.millis(),
                openStream = { assets.open(descriptor.assetPath, AssetManager.ACCESS_STREAMING) },
                signatureValidator = signatureValidator,
            )
    }

    private fun findTrustedPrivatePackage(): Path? {
        val root = packageRoot()
        if (!Files.isDirectory(root)) return null
        val preferred = packageDirectory()
        val candidates = Files.list(root).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isDirectory)
                .sortedWith(compareBy<Path> { if (it == preferred) 0 else 1 }.thenBy { it.fileName.toString() })
                .toList()
        }
        return candidates.firstNotNullOfOrNull(::readTrustedPrivatePackage)
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
        require(metadata.storageRevision == descriptor.storageRevision)
        require(metadata.signature.inputName.isNotBlank() && metadata.signature.outputName.isNotBlank())
        require(metadata.signature.inputShape.isNotEmpty() && metadata.signature.outputShape.isNotEmpty())
        model
    }.getOrNull()

    private fun packageRoot(): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)

    private fun packageDirectory(): Path = packageRoot().resolve(descriptor.storageRevision)

    private companion object {
        const val MODEL_FILE_NAME = "model.onnx"
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
    }
}
