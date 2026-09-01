package rs.masumi.core.modelpackage

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.DetectionJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

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
        findExistingPackage(descriptor, signatureValidator)?.let { return it }
        val packageDirectory = packageDirectory(descriptor)

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
            val copiedLength = copy(
                input = openStream(),
                target = partFile,
                expectedLength = descriptor.byteLength,
                onProgress = onProgress,
            )
            if (copiedLength != descriptor.byteLength) {
                throw ModelPackageException(ModelPackageErrorCode.LENGTH_MISMATCH)
            }

            val signature = validateSignature(partFile, signatureValidator)
            val modelFile = stagingDirectory.resolve("model.onnx")
            fileSystem.moveFile(partFile, modelFile)
            val metadata = DetectorModelPackageMetadata(
                storageRevision = descriptor.storageRevision,
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

    private fun findExistingPackage(
        descriptor: DetectorModelDescriptor,
        signatureValidator: ModelSignatureValidator,
    ): Path? {
        val root = packageRoot(descriptor)
        if (!Files.isDirectory(root)) return null
        val preferred = packageDirectory(descriptor)
        val candidates = Files.list(root).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isDirectory)
                .sortedWith(compareBy<Path> { if (it == preferred) 0 else 1 }.thenBy { it.fileName.toString() })
                .toList()
        }
        return candidates.firstNotNullOfOrNull { directory ->
            validateExistingPackage(directory, descriptor, signatureValidator)
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
            require(Files.getLastModifiedTime(modelFile) <= Files.getLastModifiedTime(metadataFile))
            require(signatureValidator.validate(modelFile) == metadata.signature)
            if (metadata.storageRevision != descriptor.storageRevision) {
                runCatching {
                    fileSystem.replaceUtf8(
                        metadataFile,
                        json.encodeModelPackageMetadata(
                            metadata.copy(storageRevision = descriptor.storageRevision),
                        ),
                    )
                }
            }
            modelFile
        }.getOrNull()
    }

    private fun copy(
        input: InputStream,
        target: Path,
        expectedLength: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var byteLength = 0L
        onProgress(0, expectedLength)
        BufferedInputStream(input).use { source ->
            BufferedOutputStream(fileSystem.newOutputStream(target)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    byteLength += read
                    onProgress(byteLength, expectedLength)
                }
                output.flush()
            }
        }
        return byteLength
    }

    private fun validateSignature(
        modelFile: Path,
        signatureValidator: ModelSignatureValidator,
    ): DetectorModelSignature = try {
        signatureValidator.validate(modelFile)
    } catch (failure: Throwable) {
        throw ModelPackageException(ModelPackageErrorCode.SIGNATURE_MISMATCH, failure)
    }

    private fun packageRoot(descriptor: DetectorModelDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)

    private fun packageDirectory(descriptor: DetectorModelDescriptor): Path = packageRoot(descriptor)
        .resolve(descriptor.storageRevision)

    private fun validateDescriptor(descriptor: DetectorModelDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) { "model storageKey contains unsafe characters" }
        require(SAFE_ID.matches(descriptor.storageRevision)) { "model storageRevision contains unsafe characters" }
        require(descriptor.byteLength > 0) { "model byteLength must be positive" }
        require(descriptor.downloadUrl.startsWith("https://")) { "model URL must use HTTPS" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
