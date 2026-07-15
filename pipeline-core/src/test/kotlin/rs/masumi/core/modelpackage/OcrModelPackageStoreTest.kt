package rs.masumi.core.modelpackage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
    fun `bad complete digest is deleted and retried once from zero`() {
        val files = files()
        val descriptor = descriptor(files)
        val source = CorruptFirstModelSource(files)

        val installed = OcrModelPackageStore(workspace).ensureInstalled(
            "install",
            descriptor,
            source,
            validator(files),
        ) { _, _ -> }

        assertContentEquals(files.getValue("model.gguf"), Files.readAllBytes(installed.model))
        assertEquals(listOf(0L, 0L), source.modelOffsets)
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

        assertFalse(workspace.resolve("models/test--ocr/${descriptor.packageSha256}").exists())
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
        assertEquals("paddleocr-vl", installed.metadata.capabilities.projectorType)
    }

    @Test
    fun `transient capability failure never deletes an integrity verified package`() {
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
        repository = "test/ocr",
        revision = "revision",
        model = fileDescriptor("model.gguf", files.getValue("model.gguf")),
        projector = fileDescriptor("projector.gguf", files.getValue("projector.gguf")),
        packageSha256 = "f".repeat(64),
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
        sha256 = sha256(bytes),
        downloadUrl = "https://example.invalid/$name",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

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

    private class CorruptFirstModelSource(
        private val files: Map<String, ByteArray>,
    ) : OcrRangeSource {
        val modelOffsets = mutableListOf<Long>()

        override fun open(file: OcrModelFileDescriptor, offset: Long): OcrRangeResponse {
            val original = files.getValue(file.fileName)
            val bytes = if (file.fileName == "model.gguf") {
                modelOffsets += offset
                if (modelOffsets.size == 1) original.clone().also { it[0] = (it[0] + 1).toByte() } else original
            } else {
                original
            }
            return response(bytes, offset)
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
