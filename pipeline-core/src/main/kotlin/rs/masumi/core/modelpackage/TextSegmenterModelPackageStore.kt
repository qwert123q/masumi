package rs.masumi.core.modelpackage

import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.CleanupJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

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
            val copiedLength = copy(openStream(), partFile)
            if (copiedLength != descriptor.byteLength) {
                throw TextSegmenterModelPackageException(
                    TextSegmenterModelPackageErrorCode.LENGTH_MISMATCH,
                )
            }
            val signature = validateSignature(partFile, signatureValidator)
            val modelFile = stagingDirectory.resolve(MODEL_FILE_NAME)
            fileSystem.moveFile(partFile, modelFile)
            val metadata = TextSegmenterModelPackageMetadata(
                storageRevision = descriptor.storageRevision,
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

    private fun findExistingPackage(
        descriptor: TextSegmenterModelDescriptor,
        signatureValidator: TextSegmenterSignatureValidator,
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
            require(Files.getLastModifiedTime(modelFile) <= Files.getLastModifiedTime(metadataFile))
            require(signatureValidator.validate(modelFile) == metadata.signature)
            if (metadata.storageRevision != descriptor.storageRevision) {
                runCatching {
                    fileSystem.replaceUtf8(
                        metadataFile,
                        json.encodeTextSegmenterModelPackageMetadata(
                            metadata.copy(storageRevision = descriptor.storageRevision),
                        ),
                    )
                }
            }
            modelFile
        }.getOrNull()
    }

    private fun copy(input: InputStream, target: Path): Long {
        var byteLength = 0L
        BufferedInputStream(input).use { source ->
            BufferedOutputStream(fileSystem.newOutputStream(target)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    byteLength += read
                }
                output.flush()
            }
        }
        return byteLength
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

    private fun packageRoot(descriptor: TextSegmenterModelDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)

    private fun packageDirectory(descriptor: TextSegmenterModelDescriptor): Path = packageRoot(descriptor)
        .resolve(descriptor.storageRevision)

    private fun validateDescriptor(descriptor: TextSegmenterModelDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) {
            "text segmenter storageKey contains unsafe characters"
        }
        require(SAFE_ID.matches(descriptor.storageRevision)) {
            "text segmenter storageRevision contains unsafe characters"
        }
        require(descriptor.assetPath.isNotBlank() && !descriptor.assetPath.startsWith('/'))
        require(descriptor.byteLength > 0)
    }

    private companion object {
        const val MODEL_FILE_NAME = "model.onnx"
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
