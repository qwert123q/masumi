package rs.masumi.core.modelpackage

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.DetectionJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class DetectorModelPackageStore(
    workspaceRoot: Path,
    private val json: DetectionJson = DetectionJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun ensureInstalled(
        installId: String,
        descriptor: DetectorModelDescriptor,
        acquiredAtEpochMillis: Long,
        openStream: () -> InputStream,
        signatureValidator: ModelSignatureValidator,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
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
            val copied = copyAndHash(
                input = openStream(),
                target = partFile,
                expectedLength = descriptor.byteLength,
                onProgress = onProgress,
            )
            if (copied.byteLength != descriptor.byteLength) {
                throw ModelPackageException(ModelPackageErrorCode.LENGTH_MISMATCH)
            }
            if (copied.sha256 != descriptor.sha256) {
                throw ModelPackageException(ModelPackageErrorCode.HASH_MISMATCH)
            }

            val signature = validateSignature(partFile, signatureValidator)
            val modelFile = stagingDirectory.resolve("model.onnx")
            fileSystem.moveFile(partFile, modelFile)
            val metadata = DetectorModelPackageMetadata(
                model = descriptor.toModelRef(),
                signature = signature,
                acquiredAtEpochMillis = acquiredAtEpochMillis,
            )
            fileSystem.writeUtf8(
                stagingDirectory.resolve("package.json"),
                json.encodeModelPackageMetadata(metadata),
            )
            fileSystem.createDirectories(packageDirectory.parent)
            fileSystem.publishDirectory(stagingDirectory, packageDirectory)
            return packageDirectory.resolve("model.onnx")
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteRecursively(stagingDirectory) }
            if (failure is ModelPackageException) throw failure
            throw ModelPackageException(ModelPackageErrorCode.INSTALL_IO, failure)
        }
    }

    private fun validateExistingPackage(
        packageDirectory: Path,
        descriptor: DetectorModelDescriptor,
        signatureValidator: ModelSignatureValidator,
    ): Path? {
        val modelFile = packageDirectory.resolve("model.onnx")
        val metadataFile = packageDirectory.resolve("package.json")
        if (!fileSystem.exists(modelFile) || !fileSystem.exists(metadataFile)) return null

        return runCatching {
            val metadata = json.decodeModelPackageMetadata(fileSystem.readUtf8(metadataFile))
            require(metadata.schemaVersion == 1)
            require(metadata.model == descriptor.toModelRef())
            require(Files.size(modelFile) == descriptor.byteLength)
            require(sha256(modelFile) == descriptor.sha256)
            require(signatureValidator.validate(modelFile) == metadata.signature)
            modelFile
        }.getOrNull()
    }

    private fun copyAndHash(
        input: InputStream,
        target: Path,
        expectedLength: Long,
        onProgress: (Long, Long) -> Unit,
    ): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        onProgress(0, expectedLength)
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
                    onProgress(byteLength, expectedLength)
                }
                output.flush()
            }
        }
        return CopyResult(byteLength, digest.digest().toHex())
    }

    private fun validateSignature(
        modelFile: Path,
        signatureValidator: ModelSignatureValidator,
    ): DetectorModelSignature = try {
        signatureValidator.validate(modelFile)
    } catch (failure: Throwable) {
        throw ModelPackageException(ModelPackageErrorCode.SIGNATURE_MISMATCH, failure)
    }

    private fun packageDirectory(descriptor: DetectorModelDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)
        .resolve(descriptor.sha256)

    private fun validateDescriptor(descriptor: DetectorModelDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) { "model storageKey contains unsafe characters" }
        require(SHA256.matches(descriptor.sha256)) { "model sha256 must be lowercase hexadecimal" }
        require(descriptor.byteLength > 0) { "model byteLength must be positive" }
        require(descriptor.downloadUrl.startsWith("https://")) { "model URL must use HTTPS" }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private data class CopyResult(val byteLength: Long, val sha256: String)

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
