package rs.masumi.core.modelpackage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import rs.masumi.core.io.NioProjectFileSystem
import rs.masumi.core.io.ProjectFileSystem

class OcrModelPackageStoreTest {
    private lateinit var workspace: Path

    @BeforeTest
    fun setUp() {
        workspace = Files.createTempDirectory("masumi-ocr-model")
    }

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `valid range response resumes and publishes both verified files`() {
        val files = files()
        val descriptor = descriptor(files)
        val store = OcrModelPackageStore(workspace) { 100L }
        val staging = workspace.resolve("models/.staging/install")
        Files.createDirectories(staging)
        Files.write(staging.resolve("model.gguf.part"), files.getValue("model.gguf").copyOfRange(0, 5))
        val source = FakeRangeSource(files)

        val installed = store.ensureInstalled("install", descriptor, source, validator(files)) { _, _ -> }

        assertContentEquals(files.getValue(descriptor.model.fileName), Files.readAllBytes(installed.model))
        assertContentEquals(files.getValue(descriptor.projector.fileName), Files.readAllBytes(installed.projector))
        assertTrue(source.requests.contains(descriptor.model.fileName to 5L))
        assertNotNull(store.readInstalled(descriptor, validator(files)))
    }

    @Test
    fun `server returning 200 for resume restarts the file cleanly`() {
        val files = files()
        val descriptor = descriptor(files)
        val staging = workspace.resolve("models/.staging/install")
        Files.createDirectories(staging)
        Files.write(staging.resolve("model.gguf.part"), byteArrayOf(9, 9, 9, 9, 9))
        val source = FakeRangeSource(files, ignoreRangeFor = setOf("model.gguf"))

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "install",
            descriptor,
            source,
            validator(files),
        ) { _, _ -> }

        assertContentEquals(files.getValue("model.gguf"), Files.readAllBytes(installed.model))
        assertTrue(source.requests.contains("model.gguf" to 5L))
    }

    @Test
    fun `accepts same length downloaded bytes when model capabilities are valid`() {
        val files = files()
        val descriptor = descriptor(files)
        val replacement = ByteArray(files.getValue("model.gguf").size) { index -> (index + 3).toByte() }
        val sourceFiles = files + ("model.gguf" to replacement)

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "same-length-install",
            descriptor,
            FakeRangeSource(sourceFiles),
            OcrModelCapabilityValidator { _, _ -> OcrModelCapabilities(true, true, "paddleocr-vl") },
        ) { _, _ -> }

        assertContentEquals(replacement, Files.readAllBytes(installed.model))
    }

    @Test
    fun `discovers and reuses a valid legacy named package with old metadata fields`() {
        val files = files()
        val descriptor = descriptor(files)
        val store = OcrModelPackageStore(workspace)
        val validator = validator(files)
        val installed = store.ensureInstalled(
            "install",
            descriptor,
            FakeRangeSource(files),
            validator,
        ) { _, _ -> }
        val metadataPath = installed.model.parent.resolve("package.json")
        Files.writeString(
            metadataPath,
            Files.readString(metadataPath)
                .replace("  \"storageRevision\": \"${descriptor.storageRevision}\",\n", "")
                .replace(
                    "\"byteLength\"",
                    "\"sha256\": \"legacy-value\",\n          \"byteLength\"",
                )
                .replace(
                    "\"license\"",
                    "\"packageSha256\": \"legacy-value\",\n      \"license\"",
                ),
        )
        val legacyDirectory = installed.model.parent.parent.resolve("legacy-opaque-directory")
        Files.move(installed.model.parent, legacyDirectory)

        val reused = store.ensureInstalled(
            "second-install",
            descriptor,
            OcrRangeSource { _, _ -> throw IOException("must reuse legacy package") },
            validator,
        ) { _, _ -> }

        assertEquals(legacyDirectory.resolve(descriptor.model.fileName), reused.model)
        assertEquals(legacyDirectory.resolve(descriptor.projector.fileName), reused.projector)
        assertEquals(descriptor.storageRevision, reused.metadata.storageRevision)
    }

    @Test
    fun `one file never publishes a usable package`() {
        val files = files()
        val descriptor = descriptor(files)
        val source = object : OcrRangeSource {
            override fun open(file: OcrModelFileDescriptor, offset: Long): OcrRangeResponse {
                if (file.fileName == "projector.gguf") throw IOException("projector unavailable")
                val bytes = files.getValue(file.fileName)
                return response(bytes, offset)
            }
        }

        assertFailsWith<OcrModelPackageException> {
            OcrModelPackageStore(workspace).ensureInstalled(
                "install",
                descriptor,
                source,
                validator(files),
            ) { _, _ -> }
        }

        assertFalse(workspace.resolve("models/test--ocr/${descriptor.storageRevision}").exists())
        assertEquals(null, OcrModelPackageStore(workspace).readInstalled(descriptor, validator(files)))
    }

    @Test
    fun `capability validation covers the model and projector together`() {
        val files = files()
        val descriptor = descriptor(files)
        var validationCount = 0
        val validator = OcrModelCapabilityValidator { model, projector ->
            validationCount += 1
            assertContentEquals(files.getValue("model.gguf"), Files.readAllBytes(model))
            assertContentEquals(files.getValue("projector.gguf"), Files.readAllBytes(projector))
            OcrModelCapabilities(vision = true, embeddedChatTemplate = true, projectorType = "paddleocr-vl")
        }

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "install",
            descriptor,
            FakeRangeSource(files),
            validator,
        ) { _, _ -> }

        assertEquals(1, validationCount)
        assertEquals(descriptor.storageRevision, installed.metadata.storageRevision)
        assertEquals("paddleocr-vl", installed.metadata.capabilities.projectorType)
    }

    @Test
    fun `transient capability failure never deletes an installed package`() {
        val files = files()
        val descriptor = descriptor(files)
        val store = OcrModelPackageStore(workspace)
        val installed = store.ensureInstalled(
            "install",
            descriptor,
            FakeRangeSource(files),
            validator(files),
        ) { _, _ -> }
        val failingValidator = OcrModelCapabilityValidator { _, _ ->
            throw IllegalStateException("temporary native load failure")
        }

        val failure = assertFailsWith<OcrModelPackageException> {
            store.ensureInstalled(
                "another-install",
                descriptor,
                OcrRangeSource { _, _ -> throw IOException("must not redownload") },
                failingValidator,
            ) { _, _ -> }
        }

        assertEquals(OcrModelPackageErrorCode.CAPABILITY_MISMATCH, failure.code)
        assertTrue(installed.model.exists())
        assertTrue(installed.projector.exists())
    }

    @Test
    fun `runtime contract upgrade reuses verified model bytes and refreshes metadata`() {
        val files = files()
        val descriptor = descriptor(files)
        val store = OcrModelPackageStore(workspace)
        val installed = store.ensureInstalled(
            "install",
            descriptor,
            FakeRangeSource(files),
            validator(files),
        ) { _, _ -> }
        val upgraded = descriptor.copy(
            runtime = descriptor.runtime.copy(
                backend = "vulkan-preferred-cpu-fallback",
                buildContract = "mtmd-vulkan-image-default-v1",
            ),
        )

        val reused = store.ensureInstalled(
            "upgraded-install",
            upgraded,
            OcrRangeSource { _, _ -> throw IOException("verified bytes must not be downloaded") },
            validator(files),
        ) { _, _ -> }

        assertEquals(upgraded.runtime.toRef(), reused.metadata.runtime)
        assertEquals(installed.model, reused.model)
        assertEquals(installed.projector, reused.projector)
    }

    @Test
    fun `canonical BF16 downloads are normalized and verified before publish`() {
        val canonical = ggufFixture(0x3f80)
        val normalized = convertFixture(canonical)
        val files = mapOf("model.gguf" to canonical, "projector.gguf" to canonical)
        val base = descriptor(files)
        val normalizedDescriptor = base.copy(
            model = base.model.copy(
                normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
            ),
            projector = base.projector.copy(
                normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
            ),
        )
        val validator = OcrModelCapabilityValidator { model, projector ->
            assertContentEquals(normalized, Files.readAllBytes(model))
            assertContentEquals(normalized, Files.readAllBytes(projector))
            OcrModelCapabilities(true, true, "paddleocr-vl")
        }

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "normalized-install",
            normalizedDescriptor,
            FakeRangeSource(files),
            validator,
        ) { _, _ -> }

        assertContentEquals(normalized, Files.readAllBytes(installed.model))
        assertEquals(normalized.size.toLong(), installed.metadata.modelPackage.model.byteLength)
    }

    @Test
    fun `interrupted normalization discards staging bytes and restarts download from zero`() {
        val canonical = ggufFixture(0x3f80)
        val normalized = convertFixture(canonical)
        val files = mapOf("model.gguf" to canonical, "projector.gguf" to canonical)
        val base = descriptor(files)
        val normalizedDescriptor = base.copy(
            model = base.model.copy(
                normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
            ),
            projector = base.projector.copy(
                normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
            ),
        )
        val staging = workspace.resolve("models/.staging/interrupted-normalization")
        Files.createDirectories(staging)
        Files.write(staging.resolve("model.gguf.part"), normalized)
        Files.writeString(staging.resolve("model.gguf.part.normalizing"), "in-progress")
        val source = FakeRangeSource(files)
        val validator = OcrModelCapabilityValidator { model, projector ->
            assertContentEquals(normalized, Files.readAllBytes(model))
            assertContentEquals(normalized, Files.readAllBytes(projector))
            OcrModelCapabilities(true, true, "paddleocr-vl")
        }

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "interrupted-normalization",
            normalizedDescriptor,
            source,
            validator,
        ) { _, _ -> }

        assertTrue(source.requests.contains("model.gguf" to 0L))
        assertContentEquals(normalized, Files.readAllBytes(installed.model))
        assertFalse(installed.model.parent.resolve("model.gguf.part.normalizing").exists())
        assertFalse(workspace.resolve("models/.staging/interrupted-normalization").exists())
    }

    @Test
    fun `failed recovery cleanup quarantines the marker before deleting any staging bytes`() {
        val files = files()
        val descriptor = descriptor(files)
        val staging = workspace.resolve("models/.staging/interrupted-cleanup")
        Files.createDirectories(staging)
        Files.write(staging.resolve("model.gguf.part"), files.getValue("model.gguf"))
        Files.writeString(staging.resolve("model.gguf.part.normalizing"), "in-progress")
        val nio = NioProjectFileSystem()
        val failingCleanup = object : ProjectFileSystem by nio {
            override fun deleteRecursively(path: Path) {
                if (path.fileName.toString().startsWith(".discarding-interrupted-cleanup-")) {
                    throw IOException("simulated cleanup interruption")
                }
                nio.deleteRecursively(path)
            }
        }

        val failure = assertFailsWith<OcrModelPackageException> {
            OcrModelPackageStore(workspace, fileSystem = failingCleanup).ensureInstalled(
                "interrupted-cleanup",
                descriptor,
                FakeRangeSource(files),
                validator(files),
            ) { _, _ -> }
        }

        assertEquals(OcrModelPackageErrorCode.INSTALL_IO, failure.code)
        assertFalse(staging.exists())
        val quarantined = Files.list(staging.parent).use { entries ->
            entries.filter { it.fileName.toString().startsWith(".discarding-interrupted-cleanup-") }
                .toList()
                .single()
        }
        assertTrue(quarantined.resolve("model.gguf.part.normalizing").exists())
        assertTrue(quarantined.resolve("model.gguf.part").exists())
    }

    @Test
    fun `a new install revision cleans quarantines left by older revisions`() {
        val files = files()
        val abandoned = workspace.resolve(
            "models/.staging/.discarding-old-revision-550e8400-e29b-41d4-a716-446655440000",
        )
        Files.createDirectories(abandoned)
        Files.writeString(abandoned.resolve("model.gguf.part.normalizing"), "in-progress")
        Files.write(abandoned.resolve("model.gguf.part"), files.getValue("model.gguf"))

        OcrModelPackageStore(workspace).ensureInstalled(
            "new-revision",
            descriptor(files),
            FakeRangeSource(files),
            validator(files),
        ) { _, _ -> }

        assertFalse(abandoned.exists())
    }

    @Test
    fun `reusing an installed package also cleans an abandoned quarantine`() {
        val files = files()
        val descriptor = descriptor(files)
        val store = OcrModelPackageStore(workspace)
        store.ensureInstalled(
            "first-install",
            descriptor,
            FakeRangeSource(files),
            validator(files),
        ) { _, _ -> }
        val abandoned = workspace.resolve(
            "models/.staging/.discarding-obsolete-550e8400-e29b-41d4-a716-446655440000",
        )
        Files.createDirectories(abandoned)
        Files.writeString(abandoned.resolve("model.gguf.part.normalizing"), "in-progress")

        store.ensureInstalled(
            "second-install",
            descriptor,
            OcrRangeSource { _, _ -> throw IOException("installed package must be reused") },
            validator(files),
        ) { _, _ -> }

        assertFalse(abandoned.exists())
    }

    private fun validator(files: Map<String, ByteArray>) = OcrModelCapabilityValidator { model, projector ->
        assertContentEquals(files.getValue("model.gguf"), Files.readAllBytes(model))
        assertContentEquals(files.getValue("projector.gguf"), Files.readAllBytes(projector))
        OcrModelCapabilities(vision = true, embeddedChatTemplate = true, projectorType = "paddleocr-vl")
    }

    private fun files(): Map<String, ByteArray> = mapOf(
        "model.gguf" to "model-bytes-0123456789".encodeToByteArray(),
        "projector.gguf" to "projector-bytes-abcdefghij".encodeToByteArray(),
    )

    private fun descriptor(files: Map<String, ByteArray>) = OcrModelPackageDescriptor(
        packageId = "test/ocr",
        storageKey = "test--ocr",
        storageRevision = "revision-f16-v1",
        repository = "test/ocr",
        revision = "revision",
        model = fileDescriptor("model.gguf", files.getValue("model.gguf")),
        projector = fileDescriptor("projector.gguf", files.getValue("projector.gguf")),
        license = "Apache-2.0",
        prompt = "OCR:",
        runtime = OcrNativeRuntimeDescriptor(
            llamaTag = "b8935",
            llamaCommit = "runtime-commit",
            abi = "arm64-v8a",
            backend = "cpu",
            buildContract = "mtmd-v1",
        ),
    )

    private fun fileDescriptor(name: String, bytes: ByteArray) = OcrModelFileDescriptor(
        fileName = name,
        byteLength = bytes.size.toLong(),
        downloadUrl = "https://example.invalid/$name",
    )

    private fun convertFixture(source: ByteArray): ByteArray {
        val path = Files.createTempFile(workspace, "normalize", ".gguf")
        return try {
            Files.write(path, source)
            GgufBf16ToF16Converter.convertInPlace(path)
            Files.readAllBytes(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun ggufFixture(bf16: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("GGUF".encodeToByteArray())
            output.writeInt(Integer.reverseBytes(3))
            output.writeLong(java.lang.Long.reverseBytes(1))
            output.writeLong(java.lang.Long.reverseBytes(0))
            val name = "weight".encodeToByteArray()
            output.writeLong(java.lang.Long.reverseBytes(name.size.toLong()))
            output.write(name)
            output.writeInt(Integer.reverseBytes(1))
            output.writeLong(java.lang.Long.reverseBytes(1))
            output.writeInt(Integer.reverseBytes(30))
            output.writeLong(java.lang.Long.reverseBytes(0))
            while (bytes.size() % 32 != 0) output.writeByte(0)
            output.writeShort(java.lang.Short.reverseBytes(bf16.toShort()).toInt())
        }
        return bytes.toByteArray()
    }

    private class FakeRangeSource(
        private val files: Map<String, ByteArray>,
        private val ignoreRangeFor: Set<String> = emptySet(),
    ) : OcrRangeSource {
        val requests = mutableListOf<Pair<String, Long>>()

        override fun open(file: OcrModelFileDescriptor, offset: Long): OcrRangeResponse {
            requests += file.fileName to offset
            val bytes = files.getValue(file.fileName)
            return if (offset > 0L && file.fileName !in ignoreRangeFor) {
                response(bytes, offset)
            } else {
                OcrRangeResponse(
                    statusCode = 200,
                    totalLength = bytes.size.toLong(),
                    contentRangeStart = null,
                    input = ByteArrayInputStream(bytes),
                )
            }
        }
    }

    private companion object {
        fun response(bytes: ByteArray, offset: Long): OcrRangeResponse = OcrRangeResponse(
            statusCode = if (offset == 0L) 200 else 206,
            totalLength = bytes.size.toLong(),
            contentRangeStart = offset.takeIf { it > 0L },
            input = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()),
        )
    }
}
