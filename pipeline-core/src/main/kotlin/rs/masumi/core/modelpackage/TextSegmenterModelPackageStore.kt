package rs.masumi.core.modelpackage

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.CleanupJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TextSegmenterModelPackageStore(
    workspaceRoot: Path,
    private val json: CleanupJson = CleanupJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun ensureInstalled(
        installId: String,
        descriptor: TextSegmenterModelDescriptor,
        acquiredAtEpochMillis: Long,
        openStream: () -> InputStream,
        signatureValidator: TextSegmenterSignatureValidator,
    ): Path {
        validateDescriptor(descriptor)
        require(SAFE_ID.matches(installId)) { "installId contains unsafe characters" }
        val packageDirectory = packageDirectory(descriptor)
        validateExistingPackage(packageDirectory, descriptor, signatureValidator)?.let { return it }

        if (fileSystem.exists(packageDirectory)) {
            fileSystem.deleteRecursively(packageDirectory)
        }
        val stagingDirectory = workspaceRoot.resolve("models/.staging").resolve(installId)
        if (fileSystem.exists(stagingDirectory)) {
            fileSystem.deleteRecursively(stagingDirectory)
        }

        try {
            fileSystem.createDirectories(stagingDirectory.parent)
            fileSystem.createDirectory(stagingDirectory)
            val partFile = stagingDirectory.resolve("model.onnx.part")
            val copied = copyAndHash(openStream(), partFile)
            if (copied.byteLength != descriptor.byteLength) {
                throw TextSegmenterModelPackageException(
                    TextSegmenterModelPackageErrorCode.LENGTH_MISMATCH,
                )
            }
            if (copied.sha256 != descriptor.sha256) {
                throw TextSegmenterModelPackageException(
                    TextSegmenterModelPackageErrorCode.HASH_MISMATCH,
                )
            }
            val signature = validateSignature(partFile, signatureValidator)
            val modelFile = stagingDirectory.resolve(MODEL_FILE_NAME)
            fileSystem.moveFile(partFile, modelFile)
            val metadata = TextSegmenterModelPackageMetadata(
                model = descriptor.toModelRef(),
                signature = signature,
                acquiredAtEpochMillis = acquiredAtEpochMillis,
            )
            fileSystem.writeUtf8(
                stagingDirectory.resolve(PACKAGE_METADATA_FILE_NAME),
                json.encodeTextSegmenterModelPackageMetadata(metadata),
            )
            fileSystem.createDirectories(packageDirectory.parent)
            fileSystem.publishDirectory(stagingDirectory, packageDirectory)
            return packageDirectory.resolve(MODEL_FILE_NAME)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteRecursively(stagingDirectory) }
            if (failure is TextSegmenterModelPackageException) throw failure
            throw TextSegmenterModelPackageException(
                TextSegmenterModelPackageErrorCode.INSTALL_IO,
                failure,
            )
        }
    }

    private fun validateExistingPackage(
        packageDirectory: Path,
        descriptor: TextSegmenterModelDescriptor,
        signatureValidator: TextSegmenterSignatureValidator,
    ): Path? {
        val modelFile = packageDirectory.resolve(MODEL_FILE_NAME)
        val metadataFile = packageDirectory.resolve(PACKAGE_METADATA_FILE_NAME)
        if (!fileSystem.exists(modelFile) || !fileSystem.exists(metadataFile)) return null
        return runCatching {
            val metadata = json.decodeTextSegmenterModelPackageMetadata(
                fileSystem.readUtf8(metadataFile),
            )
            require(metadata.schemaVersion == 1 && metadata.model == descriptor.toModelRef())
            require(Files.size(modelFile) == descriptor.byteLength)
            require(sha256(modelFile) == descriptor.sha256)
            require(signatureValidator.validate(modelFile) == metadata.signature)
            modelFile
        }.getOrNull()
    }

    private fun copyAndHash(input: InputStream, target: Path): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        BufferedInputStream(input).use { source ->
            BufferedOutputStream(fileSystem.newOutputStream(target)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    byteLength += read
                }
                output.flush()
            }
        }
        return CopyResult(byteLength, digest.digest().toHex())
    }

    private fun validateSignature(
        modelFile: Path,
        signatureValidator: TextSegmenterSignatureValidator,
    ): TextSegmenterModelSignature = try {
        signatureValidator.validate(modelFile)
    } catch (failure: Throwable) {
        throw TextSegmenterModelPackageException(
            TextSegmenterModelPackageErrorCode.SIGNATURE_MISMATCH,
            failure,
        )
    }

    private fun packageDirectory(descriptor: TextSegmenterModelDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)
        .resolve(descriptor.sha256)

    private fun validateDescriptor(descriptor: TextSegmenterModelDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) {
            "text segmenter storageKey contains unsafe characters"
        }
        require(SHA256.matches(descriptor.sha256)) {
            "text segmenter sha256 must be lowercase hexadecimal"
        }
        require(descriptor.assetPath.isNotBlank() && !descriptor.assetPath.startsWith('/'))
        require(descriptor.byteLength > 0)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class CopyResult(val byteLength: Long, val sha256: String)

    private companion object {
        const val MODEL_FILE_NAME = "model.onnx"
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
