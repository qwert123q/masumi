package rs.masumi.core.modelpackage

import rs.masumi.core.serialization.DetectionJson
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectorModelPackageStoreTest {
    private lateinit var workspace: Path

    @BeforeTest
    fun setUp() {
        workspace = Files.createTempDirectory("masumi-model-package")
    }

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `installs exact bytes and writes safe package metadata`() {
        val bytes = "detector-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val signature = signature()
        val progress = mutableListOf<Long>()

        val modelFile = DetectorModelPackageStore(workspace).ensureInstalled(
            installId = "install-1",
            descriptor = descriptor,
            acquiredAtEpochMillis = 100,
            openStream = { ByteArrayInputStream(bytes) },
            signatureValidator = ModelSignatureValidator { path ->
                assertTrue(Files.readAllBytes(path).contentEquals(bytes))
                signature
            },
            onProgress = { downloaded, _ -> progress += downloaded },
        )

        assertTrue(modelFile.exists())
        assertTrue(Files.readAllBytes(modelFile).contentEquals(bytes))
        assertEquals(bytes.size.toLong(), Files.size(modelFile))
        assertEquals(bytes.size.toLong(), progress.last())
        val metadata = DetectionJson().decodeModelPackageMetadata(
            Files.newBufferedReader(modelFile.parent.resolve("package.json")).use { it.readText() },
        )
        assertEquals(descriptor.storageRevision, metadata.storageRevision)
        assertEquals(descriptor.toModelRef(), metadata.model)
        assertEquals(signature, metadata.signature)
        assertEquals(100, metadata.acquiredAtEpochMillis)
        assertFalse(Files.readString(modelFile.parent.resolve("package.json")).contains("downloadUrl"))
    }

    @Test
    fun `rejects wrong length and signature without publication`() {
        val bytes = "detector-model".encodeToByteArray()
        val store = DetectorModelPackageStore(workspace)

        val lengthFailure = assertFailsWith<ModelPackageException> {
            store.ensureInstalled(
                "length-install",
                descriptor(bytes).copy(byteLength = bytes.size + 1L),
                100,
                { ByteArrayInputStream(bytes) },
                ModelSignatureValidator { signature() },
            )
        }
        assertEquals(ModelPackageErrorCode.LENGTH_MISMATCH, lengthFailure.code)

        val signatureFailure = assertFailsWith<ModelPackageException> {
            store.ensureInstalled(
                "signature-install",
                descriptor(bytes),
                100,
                { ByteArrayInputStream(bytes) },
                ModelSignatureValidator { throw IllegalArgumentException("bad signature") },
            )
        }
        assertEquals(ModelPackageErrorCode.SIGNATURE_MISMATCH, signatureFailure.code)
        assertFalse(workspace.resolve("models/test--detector").exists())
    }

    @Test
    fun `reuses a valid package without reopening the supplied stream`() {
        val bytes = "detector-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val store = DetectorModelPackageStore(workspace)
        var openCount = 0
        val opener = {
            openCount += 1
            ByteArrayInputStream(bytes) as InputStream
        }
        val validator = ModelSignatureValidator { signature() }

        val first = store.ensureInstalled("install-1", descriptor, 100, opener, validator)
        val second = store.ensureInstalled("install-2", descriptor, 200, opener, validator)

        assertEquals(first, second)
        assertEquals(1, openCount)
    }

    @Test
    fun `accepts same length model bytes when the tensor signature is valid`() {
        val expectedBytes = "detector-model".encodeToByteArray()
        val sameLengthBytes = ByteArray(expectedBytes.size) { index -> (index + 1).toByte() }

        val model = DetectorModelPackageStore(workspace).ensureInstalled(
            installId = "same-length-install",
            descriptor = descriptor(expectedBytes),
            acquiredAtEpochMillis = 100,
            openStream = { ByteArrayInputStream(sameLengthBytes) },
            signatureValidator = ModelSignatureValidator { signature() },
        )

        assertTrue(Files.readAllBytes(model).contentEquals(sameLengthBytes))
    }

    @Test
    fun `discovers and reuses a valid legacy named package with old metadata fields`() {
        val bytes = "detector-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val store = DetectorModelPackageStore(workspace)
        var openCount = 0
        val opener = {
            openCount += 1
            ByteArrayInputStream(bytes) as InputStream
        }
        val validator = ModelSignatureValidator { signature() }
        val installed = store.ensureInstalled("install-1", descriptor, 100, opener, validator)
        val metadataPath = installed.parent.resolve("package.json")
        Files.writeString(
            metadataPath,
            Files.readString(metadataPath)
                .replace("  \"storageRevision\": \"${descriptor.storageRevision}\",\n", "")
                .replace(
                    "\"byteLength\"",
                    "\"sha256\": \"legacy-value\",\n        \"byteLength\"",
                ),
        )
        val legacyDirectory = installed.parent.parent.resolve("legacy-opaque-directory")
        Files.move(installed.parent, legacyDirectory)

        val reused = store.ensureInstalled("install-2", descriptor, 200, opener, validator)

        assertEquals(legacyDirectory.resolve("model.onnx"), reused)
        assertEquals(1, openCount)
        assertEquals(
            descriptor.storageRevision,
            DetectionJson().decodeModelPackageMetadata(
                Files.readString(legacyDirectory.resolve("package.json")),
            ).storageRevision,
        )
    }

    @Test
    fun `download failure cleans only staging owned by its install id`() {
        val bytes = "detector-model".encodeToByteArray()
        val foreign = workspace.resolve("models/.staging/other-install")
        Files.createDirectories(foreign)
        Files.write(foreign.resolve("owner.marker"), byteArrayOf(1))

        val failure = assertFailsWith<ModelPackageException> {
            DetectorModelPackageStore(workspace).ensureInstalled(
                "install-1",
                descriptor(bytes),
                100,
                { failingStream() },
                ModelSignatureValidator { signature() },
            )
        }

        assertEquals(ModelPackageErrorCode.INSTALL_IO, failure.code)
        assertFalse(workspace.resolve("models/.staging/install-1").exists())
        assertTrue(foreign.resolve("owner.marker").exists())
    }

    private fun descriptor(bytes: ByteArray): DetectorModelDescriptor = DetectorModelDescriptor(
        modelId = "test/detector",
        storageKey = "test--detector",
        storageRevision = "revision-1",
        repository = "test/detector",
        revision = "revision-1",
        fileName = "detector.onnx",
        byteLength = bytes.size.toLong(),
        license = "Apache-2.0",
        opset = 18,
        runtimeRevision = "runtime:1",
        downloadUrl = "https://example.invalid/model.onnx",
    )

    private fun signature(): DetectorModelSignature = DetectorModelSignature(
        imageInputName = "images",
        imageInputShape = listOf(null, 3, 640, 640),
        sizeInputName = "orig_target_sizes",
        sizeInputShape = listOf(null, 2),
        outputNames = listOf("labels", "boxes", "scores"),
        queryCount = 300,
    )

    private fun failingStream(): InputStream = object : InputStream() {
        private var emitted = false

        override fun read(): Int {
            if (!emitted) {
                emitted = true
                return 1
            }
            throw IOException("stream failed")
        }
    }

}
