package rs.masumi.core.modelpackage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem
import rs.masumi.core.serialization.OcrJson

class OcrModelPackageStore(
    workspaceRoot: Path,
    private val json: OcrJson = OcrJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun ensureInstalled(
        installId: String,
        descriptor: OcrModelPackageDescriptor,
        source: OcrRangeSource,
        capabilityValidator: OcrModelCapabilityValidator,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): InstalledOcrModelPackage {
        validateDescriptor(descriptor)
        require(SAFE_ID.matches(installId)) { "installId contains unsafe characters" }
        readInstalled(descriptor, capabilityValidator)?.let { return it }

        val packageDirectory = packageDirectory(descriptor)
        if (fileSystem.exists(packageDirectory)) fileSystem.deleteRecursively(packageDirectory)
        val staging = workspaceRoot.resolve("models/.staging/$installId").normalize()
        fileSystem.createDirectories(staging)
        val totalLength = descriptor.model.byteLength + descriptor.projector.byteLength

        try {
            val modelPart = staging.resolve("${descriptor.model.fileName}.part")
            downloadVerifiedFile(
                descriptor.model,
                modelPart,
                source,
                completedBeforeFile = 0L,
                packageTotal = totalLength,
                onProgress = onProgress,
            )
            val projectorPart = staging.resolve("${descriptor.projector.fileName}.part")
            downloadVerifiedFile(
                descriptor.projector,
                projectorPart,
                source,
                completedBeforeFile = descriptor.model.byteLength,
                packageTotal = totalLength,
                onProgress = onProgress,
            )
            val capabilities = validateCapabilities(modelPart, projectorPart, capabilityValidator)
            val model = staging.resolve(descriptor.model.fileName)
            val projector = staging.resolve(descriptor.projector.fileName)
            fileSystem.replaceFile(modelPart, model)
            fileSystem.replaceFile(projectorPart, projector)
            val metadata = OcrModelPackageMetadata(
                modelPackage = descriptor.toRef(),
                runtime = descriptor.runtime.toRef(),
                prompt = descriptor.prompt,
                capabilities = capabilities,
                acquiredAtEpochMillis = nowEpochMillis(),
            )
            fileSystem.replaceUtf8(staging.resolve("package.json"), json.encodeModelPackageMetadata(metadata))
            fileSystem.createDirectories(packageDirectory.parent)
            fileSystem.publishDirectory(staging, packageDirectory)
            val publishedModel = packageDirectory.resolve(descriptor.model.fileName)
            val publishedProjector = packageDirectory.resolve(descriptor.projector.fileName)
            require(Files.exists(publishedModel) && Files.exists(publishedProjector)) {
                "published OCR model package is incomplete"
            }
            return InstalledOcrModelPackage(publishedModel, publishedProjector, metadata)
        } catch (failure: Throwable) {
            if (failure is OcrModelPackageException) throw failure
            throw OcrModelPackageException(OcrModelPackageErrorCode.INSTALL_IO, failure)
        }
    }

    fun readInstalled(
        descriptor: OcrModelPackageDescriptor,
        capabilityValidator: OcrModelCapabilityValidator,
    ): InstalledOcrModelPackage? {
        validateDescriptor(descriptor)
        val directory = packageDirectory(descriptor)
        val model = directory.resolve(descriptor.model.fileName)
        val projector = directory.resolve(descriptor.projector.fileName)
        val metadataPath = directory.resolve("package.json")
        if (!Files.exists(model) || !Files.exists(projector) || !Files.exists(metadataPath)) return null
        val metadata = runCatching {
            require(Files.size(model) == descriptor.model.byteLength)
            require(Files.size(projector) == descriptor.projector.byteLength)
            require(sha256(model) == descriptor.model.sha256)
            require(sha256(projector) == descriptor.projector.sha256)
            json.decodeModelPackageMetadata(Files.readString(metadataPath)).also { metadata ->
                requireCapabilities(metadata.capabilities)
            }
        }.getOrNull() ?: return null
        if (
            metadata.schemaVersion != 1 ||
            metadata.modelPackage != descriptor.toRef() ||
            metadata.runtime != descriptor.runtime.toRef() ||
            metadata.prompt != descriptor.prompt
        ) {
            return null
        }
        val actualCapabilities = try {
            capabilityValidator.validate(model, projector).also(::requireCapabilities)
        } catch (failure: Throwable) {
            if (failure is OcrModelPackageException) throw failure
            throw OcrModelPackageException(OcrModelPackageErrorCode.CAPABILITY_MISMATCH, failure)
        }
        if (actualCapabilities != metadata.capabilities) {
            throw OcrModelPackageException(OcrModelPackageErrorCode.CAPABILITY_MISMATCH)
        }
        return InstalledOcrModelPackage(model, projector, metadata)
    }

    private fun downloadVerifiedFile(
        descriptor: OcrModelFileDescriptor,
        part: Path,
        source: OcrRangeSource,
        completedBeforeFile: Long,
        packageTotal: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        repeat(MAX_CLEAN_ATTEMPTS) { cleanAttempt ->
            var offset = if (Files.exists(part)) Files.size(part) else 0L
            if (offset > descriptor.byteLength) {
                Files.deleteIfExists(part)
                offset = 0L
            }
            onProgress(completedBeforeFile + offset, packageTotal)
            if (offset < descriptor.byteLength) {
                val response = source.open(descriptor, offset)
                response.use {
                    val append = validateRangeResponse(response, offset, descriptor.byteLength)
                    if (!append) {
                        Files.deleteIfExists(part)
                        offset = 0L
                    }
                    copyResponse(
                        response,
                        part,
                        append = append,
                        startingLength = offset,
                        expectedLength = descriptor.byteLength,
                    ) { fileLength ->
                        onProgress(completedBeforeFile + fileLength, packageTotal)
                    }
                }
            }
            val actualLength = if (Files.exists(part)) Files.size(part) else 0L
            val error = when {
                actualLength != descriptor.byteLength -> OcrModelPackageErrorCode.LENGTH_MISMATCH
                sha256(part) != descriptor.sha256 -> OcrModelPackageErrorCode.HASH_MISMATCH
                else -> null
            }
            if (error == null) return
            Files.deleteIfExists(part)
            if (cleanAttempt == MAX_CLEAN_ATTEMPTS - 1) throw OcrModelPackageException(error)
        }
    }

    private fun validateRangeResponse(
        response: OcrRangeResponse,
        requestedOffset: Long,
        expectedLength: Long,
    ): Boolean {
        if (response.totalLength != null && response.totalLength != expectedLength) {
            throw OcrModelPackageException(OcrModelPackageErrorCode.LENGTH_MISMATCH)
        }
        return when {
            requestedOffset == 0L && response.statusCode == 200 -> false
            requestedOffset == 0L && response.statusCode == 206 && response.contentRangeStart == 0L -> false
            requestedOffset > 0L && response.statusCode == 206 &&
                response.contentRangeStart == requestedOffset -> true
            requestedOffset > 0L && response.statusCode == 200 -> false
            else -> throw OcrModelPackageException(OcrModelPackageErrorCode.RANGE_MISMATCH)
        }
    }

    private fun copyResponse(
        response: OcrRangeResponse,
        target: Path,
        append: Boolean,
        startingLength: Long,
        expectedLength: Long,
        onProgress: (Long) -> Unit,
    ) {
        val options = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }
        var fileLength = startingLength
        BufferedInputStream(response.input).use { input ->
            BufferedOutputStream(Files.newOutputStream(target, *options)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    fileLength += read
                    if (fileLength > expectedLength) break
                    onProgress(fileLength)
                }
                output.flush()
            }
        }
    }

    private fun validateCapabilities(
        model: Path,
        projector: Path,
        validator: OcrModelCapabilityValidator,
    ): OcrModelCapabilities = try {
        validator.validate(model, projector).also(::requireCapabilities)
    } catch (failure: Throwable) {
        runCatching { Files.deleteIfExists(model) }
        runCatching { Files.deleteIfExists(projector) }
        if (failure is OcrModelPackageException) throw failure
        throw OcrModelPackageException(OcrModelPackageErrorCode.CAPABILITY_MISMATCH, failure)
    }

    private fun requireCapabilities(capabilities: OcrModelCapabilities) {
        if (!capabilities.vision || !capabilities.embeddedChatTemplate || capabilities.projectorType.isBlank()) {
            throw OcrModelPackageException(OcrModelPackageErrorCode.CAPABILITY_MISMATCH)
        }
    }

    private fun packageDirectory(descriptor: OcrModelPackageDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)
        .resolve(descriptor.packageSha256)

    private fun validateDescriptor(descriptor: OcrModelPackageDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) { "model storageKey contains unsafe characters" }
        require(SHA256.matches(descriptor.packageSha256)) { "package SHA-256 is invalid" }
        listOf(descriptor.model, descriptor.projector).forEach { file ->
            require(SAFE_FILE_NAME.matches(file.fileName)) { "model fileName is unsafe" }
            require(file.byteLength > 0L) { "model byteLength must be positive" }
            require(SHA256.matches(file.sha256)) { "model SHA-256 is invalid" }
            require(file.downloadUrl.startsWith("https://")) { "model URL must use HTTPS" }
        }
        require(descriptor.model.fileName != descriptor.projector.fileName) {
            "model and projector file names must differ"
        }
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
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CLEAN_ATTEMPTS = 2
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
