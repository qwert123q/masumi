package rs.masumi.core.modelpackage

import rs.masumi.core.serialization.CleanupJson
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
        assertEquals(descriptor.toModelRef(), metadata.model)
        assertEquals(signature, metadata.signature)
        assertEquals(100, metadata.acquiredAtEpochMillis)
    }

    @Test
    fun `rejects a mismatched bundled model without publication`() {
        val bytes = "segmentation-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val failure = assertFailsWith<TextSegmenterModelPackageException> {
            TextSegmenterModelPackageStore(workspace).ensureInstalled(
                installId = "segmenter-install",
                descriptor = descriptor.copy(sha256 = "0".repeat(64)),
                acquiredAtEpochMillis = 100,
                openStream = { ByteArrayInputStream(bytes) },
                signatureValidator = TextSegmenterSignatureValidator { signature() },
            )
        }

        assertEquals(TextSegmenterModelPackageErrorCode.HASH_MISMATCH, failure.code)
        assertFalse(workspace.resolve("models/test--text-segmenter").exists())
    }

    private fun descriptor(bytes: ByteArray) = TextSegmenterModelDescriptor(
        modelId = "test/text-segmenter",
        storageKey = "test--text-segmenter",
        repository = "test/repository",
        revision = "revision-1",
        fileName = "segmenter.onnx",
        assetPath = "models/segmenter.onnx",
        byteLength = bytes.size.toLong(),
        sha256 = sha256(bytes),
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

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
