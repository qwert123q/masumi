package rs.masumi.core.modelpackage

import rs.masumi.core.serialization.CleanupJson
import java.io.ByteArrayInputStream
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

class TextSegmenterModelPackageStoreTest {
    private lateinit var workspace: Path

    @BeforeTest
    fun setUp() {
        workspace = Files.createTempDirectory("masumi-text-segmenter-package")
    }

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `installs exact bundled model and records its signature`() {
        val bytes = "segmentation-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val signature = signature()

        val model = TextSegmenterModelPackageStore(workspace).ensureInstalled(
            installId = "segmenter-install",
            descriptor = descriptor,
            acquiredAtEpochMillis = 100,
            openStream = { ByteArrayInputStream(bytes) },
            signatureValidator = TextSegmenterSignatureValidator { signature },
        )

        assertTrue(model.exists())
        assertTrue(Files.readAllBytes(model).contentEquals(bytes))
        val metadata = CleanupJson().decodeTextSegmenterModelPackageMetadata(
            Files.readString(model.parent.resolve("package.json")),
        )
        assertEquals(descriptor.storageRevision, metadata.storageRevision)
        assertEquals(descriptor.toModelRef(), metadata.model)
        assertEquals(signature, metadata.signature)
        assertEquals(100, metadata.acquiredAtEpochMillis)
    }

    @Test
    fun `rejects a wrong length bundled model without publication`() {
        val bytes = "segmentation-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val failure = assertFailsWith<TextSegmenterModelPackageException> {
            TextSegmenterModelPackageStore(workspace).ensureInstalled(
                installId = "segmenter-install",
                descriptor = descriptor.copy(byteLength = descriptor.byteLength + 1),
                acquiredAtEpochMillis = 100,
                openStream = { ByteArrayInputStream(bytes) },
                signatureValidator = TextSegmenterSignatureValidator { signature() },
            )
        }

        assertEquals(TextSegmenterModelPackageErrorCode.LENGTH_MISMATCH, failure.code)
        assertFalse(workspace.resolve("models/test--text-segmenter").exists())
    }

    @Test
    fun `accepts same length bundled model when the tensor signature is valid`() {
        val expectedBytes = "segmentation-model".encodeToByteArray()
        val sameLengthBytes = ByteArray(expectedBytes.size) { index -> (index + 5).toByte() }

        val model = TextSegmenterModelPackageStore(workspace).ensureInstalled(
            installId = "same-length-install",
            descriptor = descriptor(expectedBytes),
            acquiredAtEpochMillis = 100,
            openStream = { ByteArrayInputStream(sameLengthBytes) },
            signatureValidator = TextSegmenterSignatureValidator { signature() },
        )

        assertTrue(Files.readAllBytes(model).contentEquals(sameLengthBytes))
    }

    @Test
    fun `discovers and reuses a valid legacy named package with old metadata fields`() {
        val bytes = "segmentation-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val store = TextSegmenterModelPackageStore(workspace)
        val installed = store.ensureInstalled(
            installId = "install",
            descriptor = descriptor,
            acquiredAtEpochMillis = 100,
            openStream = { ByteArrayInputStream(bytes) },
            signatureValidator = TextSegmenterSignatureValidator { signature() },
        )
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

        val reused = store.ensureInstalled(
            installId = "second-install",
            descriptor = descriptor,
            acquiredAtEpochMillis = 200,
            openStream = { error("must reuse legacy package") },
            signatureValidator = TextSegmenterSignatureValidator { signature() },
        )

        assertEquals(legacyDirectory.resolve("model.onnx"), reused)
        assertEquals(
            descriptor.storageRevision,
            CleanupJson().decodeTextSegmenterModelPackageMetadata(
                Files.readString(legacyDirectory.resolve("package.json")),
            ).storageRevision,
        )
    }

    private fun descriptor(bytes: ByteArray) = TextSegmenterModelDescriptor(
        modelId = "test/text-segmenter",
        storageKey = "test--text-segmenter",
        storageRevision = "revision-1",
        repository = "test/repository",
        revision = "revision-1",
        fileName = "segmenter.onnx",
        assetPath = "models/segmenter.onnx",
        byteLength = bytes.size.toLong(),
        license = "GPL-3.0-only",
        opset = 11,
        runtimeRevision = "onnxruntime-android:1.27.0",
    )

    private fun signature() = TextSegmenterModelSignature(
        inputName = "images",
        inputShape = listOf(1, 3, 640, 640),
        outputName = "seg",
        outputShape = listOf(1, 1, 640, 640),
    )

}
