package rs.masumi.core.modelpackage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
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
        val stagingRoot = workspaceRoot.resolve("models/.staging").normalize()
        readInstalled(descriptor, capabilityValidator)?.let { installed ->
            runCatching {
                fileSystem.createDirectories(stagingRoot)
                cleanupDiscardedStaging(stagingRoot)
            }
            return installed
        }

        val packageDirectory = packageDirectory(descriptor)
        if (fileSystem.exists(packageDirectory)) fileSystem.deleteRecursively(packageDirectory)
        fileSystem.createDirectories(stagingRoot)
        cleanupDiscardedStaging(stagingRoot)
        val staging = stagingRoot.resolve(installId).normalize()
        quarantineInterruptedNormalization(staging, installId)
        fileSystem.createDirectories(staging)
        val totalLength = descriptor.model.byteLength + descriptor.projector.byteLength

        try {
            val modelPart = staging.resolve("${descriptor.model.fileName}.part")
            downloadSizedFile(
                descriptor.model,
                modelPart,
                source,
                completedBeforeFile = 0L,
                packageTotal = totalLength,
                onProgress = onProgress,
            )
            val projectorPart = staging.resolve("${descriptor.projector.fileName}.part")
            downloadSizedFile(
                descriptor.projector,
                projectorPart,
                source,
                completedBeforeFile = descriptor.model.byteLength,
                packageTotal = totalLength,
                onProgress = onProgress,
            )
            normalizeFile(descriptor.model, modelPart)
            normalizeFile(descriptor.projector, projectorPart)
            val capabilities = validateCapabilities(modelPart, projectorPart, capabilityValidator)
            val model = staging.resolve(descriptor.model.fileName)
            val projector = staging.resolve(descriptor.projector.fileName)
            fileSystem.replaceFile(modelPart, model)
            fileSystem.replaceFile(projectorPart, projector)
            val metadata = OcrModelPackageMetadata(
                storageRevision = descriptor.storageRevision,
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
            normalizationMarkers(descriptor, packageDirectory).forEach { marker ->
                runCatching { fileSystem.deleteIfExists(marker) }
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
        return packageDirectories(descriptor).firstNotNullOfOrNull { directory ->
            readInstalledFrom(directory, descriptor, capabilityValidator)
        }
    }

    private fun readInstalledFrom(
        directory: Path,
        descriptor: OcrModelPackageDescriptor,
        capabilityValidator: OcrModelCapabilityValidator,
    ): InstalledOcrModelPackage? {
        val model = directory.resolve(descriptor.model.fileName)
        val projector = directory.resolve(descriptor.projector.fileName)
        val metadataPath = directory.resolve("package.json")
        if (!Files.isRegularFile(model) || !Files.isRegularFile(projector) || !Files.isRegularFile(metadataPath)) {
            return null
        }
        val metadata = runCatching {
            require(Files.size(model) == descriptor.model.byteLength)
            require(Files.size(projector) == descriptor.projector.byteLength)
            val metadataTime = Files.getLastModifiedTime(metadataPath)
            require(Files.getLastModifiedTime(model) <= metadataTime)
            require(Files.getLastModifiedTime(projector) <= metadataTime)
            json.decodeModelPackageMetadata(fileSystem.readUtf8(metadataPath)).also { metadata ->
                requireCapabilities(metadata.capabilities)
            }
        }.getOrNull() ?: return null
        if (
            metadata.schemaVersion != 1 ||
            metadata.modelPackage != descriptor.toRef() ||
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
        val currentMetadata = if (
            metadata.runtime == descriptor.runtime.toRef() &&
            metadata.storageRevision == descriptor.storageRevision
        ) {
            metadata
        } else {
            metadata.copy(
                storageRevision = descriptor.storageRevision,
                runtime = descriptor.runtime.toRef(),
            ).also { upgraded ->
                runCatching {
                    fileSystem.replaceUtf8(metadataPath, json.encodeModelPackageMetadata(upgraded))
                }
            }
        }
        return InstalledOcrModelPackage(model, projector, currentMetadata)
    }

    private fun downloadSizedFile(
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
            if (actualLength == descriptor.byteLength) return
            Files.deleteIfExists(part)
            if (cleanAttempt == MAX_CLEAN_ATTEMPTS - 1) {
                throw OcrModelPackageException(OcrModelPackageErrorCode.LENGTH_MISMATCH)
            }
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

    private fun normalizeFile(descriptor: OcrModelFileDescriptor, part: Path) {
        if (descriptor.normalization == OcrModelFileNormalization.NONE) return
        Files.writeString(
            normalizationMarker(part),
            NORMALIZATION_IN_PROGRESS,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        )
        syncDirectory(part.parent)
        try {
            when (descriptor.normalization) {
                OcrModelFileNormalization.NONE -> Unit
                OcrModelFileNormalization.GGUF_BF16_TO_F16 ->
                    GgufBf16ToF16Converter.convertInPlace(part)
            }
            if (Files.size(part) != descriptor.byteLength) {
                throw OcrModelPackageException(OcrModelPackageErrorCode.LENGTH_MISMATCH)
            }
        } catch (failure: Throwable) {
            if (failure is OcrModelPackageException) throw failure
            throw OcrModelPackageException(OcrModelPackageErrorCode.INSTALL_IO, failure)
        }
    }

    private fun hasInterruptedNormalization(staging: Path): Boolean =
        fileSystem.list(staging).any { path ->
            path.fileName.toString().endsWith(NORMALIZATION_MARKER_SUFFIX)
        }

    private fun quarantineInterruptedNormalization(staging: Path, installId: String) {
        if (!hasInterruptedNormalization(staging)) return
        val quarantine = staging.resolveSibling(
            "$DISCARDING_STAGING_PREFIX$installId-${UUID.randomUUID()}",
        )
        try {
            Files.move(staging, quarantine, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(staging.parent)
            fileSystem.deleteRecursively(quarantine)
        } catch (failure: Throwable) {
            if (failure is OcrModelPackageException) throw failure
            throw OcrModelPackageException(OcrModelPackageErrorCode.INSTALL_IO, failure)
        }
    }

    private fun cleanupDiscardedStaging(stagingRoot: Path) {
        try {
            fileSystem.list(stagingRoot)
                .filter { path -> path.fileName.toString().startsWith(DISCARDING_STAGING_PREFIX) }
                .forEach(fileSystem::deleteRecursively)
        } catch (failure: Throwable) {
            if (failure is OcrModelPackageException) throw failure
            throw OcrModelPackageException(OcrModelPackageErrorCode.INSTALL_IO, failure)
        }
    }

    private fun syncDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    private fun normalizationMarkers(
        descriptor: OcrModelPackageDescriptor,
        directory: Path,
    ): List<Path> = listOf(descriptor.model, descriptor.projector)
        .filter { file -> file.normalization != OcrModelFileNormalization.NONE }
        .map { file -> normalizationMarker(directory.resolve("${file.fileName}.part")) }

    private fun normalizationMarker(part: Path): Path =
        part.resolveSibling("${part.fileName}$NORMALIZATION_MARKER_SUFFIX")

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

    private fun packageRoot(descriptor: OcrModelPackageDescriptor): Path = workspaceRoot
        .resolve("models")
        .resolve(descriptor.storageKey)

    private fun packageDirectory(descriptor: OcrModelPackageDescriptor): Path = packageRoot(descriptor)
        .resolve(descriptor.storageRevision)

    private fun packageDirectories(descriptor: OcrModelPackageDescriptor): List<Path> {
        val root = packageRoot(descriptor)
        if (!Files.isDirectory(root)) return emptyList()
        val preferred = packageDirectory(descriptor)
        return Files.list(root).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isDirectory)
                .sortedWith(compareBy<Path> { if (it == preferred) 0 else 1 }.thenBy { it.fileName.toString() })
                .toList()
        }
    }

    private fun validateDescriptor(descriptor: OcrModelPackageDescriptor) {
        require(SAFE_ID.matches(descriptor.storageKey)) { "model storageKey contains unsafe characters" }
        require(SAFE_ID.matches(descriptor.storageRevision)) { "model storageRevision contains unsafe characters" }
        listOf(descriptor.model, descriptor.projector).forEach { file ->
            require(SAFE_FILE_NAME.matches(file.fileName)) { "model fileName is unsafe" }
            require(file.byteLength > 0L) { "model byteLength must be positive" }
            require(file.downloadUrl.startsWith("https://")) { "model URL must use HTTPS" }
        }
        require(descriptor.model.fileName != descriptor.projector.fileName) {
            "model and projector file names must differ"
        }
    }

    private companion object {
        const val MAX_CLEAN_ATTEMPTS = 2
        const val DISCARDING_STAGING_PREFIX = ".discarding-"
        const val NORMALIZATION_IN_PROGRESS = "in-progress"
        const val NORMALIZATION_MARKER_SUFFIX = ".normalizing"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
