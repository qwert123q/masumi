package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.modelpackage.OcrModelCapabilities
import rs.masumi.core.modelpackage.OcrModelCapabilityValidator
import rs.masumi.core.modelpackage.OcrModelFileDescriptor
import rs.masumi.core.modelpackage.OcrModelPackageDescriptor
import rs.masumi.core.modelpackage.OcrNativeRuntimeDescriptor
import rs.masumi.core.modelpackage.OcrRangeResponse
import rs.masumi.core.modelpackage.OcrRangeSource

@RunWith(AndroidJUnit4::class)
class OcrModelProviderTest {
    @Test
    fun installsBothFilesFromInjectedRangeSourceAndReportsPackageProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "ocr-provider-")
        val files = mapOf(
            "model.gguf" to "model-data".encodeToByteArray(),
            "projector.gguf" to "projector-data".encodeToByteArray(),
        )
        val descriptor = descriptor(files)
        val progress = mutableListOf<Long>()
        val provider = DefaultOcrModelProvider(
            workspaceRoot = workspace,
            descriptor = descriptor,
            rangeSource = OcrRangeSource { file, offset ->
                val bytes = files.getValue(file.fileName)
                OcrRangeResponse(
                    statusCode = if (offset == 0L) 200 else 206,
                    totalLength = bytes.size.toLong(),
                    contentRangeStart = offset.takeIf { it > 0L },
                    input = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()),
                )
            },
            capabilityValidator = OcrModelCapabilityValidator { model, projector ->
                assertTrue(Files.readAllBytes(model).contentEquals(files.getValue("model.gguf")))
                assertTrue(Files.readAllBytes(projector).contentEquals(files.getValue("projector.gguf")))
                OcrModelCapabilities(true, true, "paddleocr-vl")
            },
            clock = Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC),
        )

        try {
            val installed = provider.acquire("install-1") { downloaded, _ -> progress += downloaded }

            assertTrue(Files.exists(installed.model))
            assertTrue(Files.exists(installed.projector))
            assertEquals(progress.sorted(), progress)
            assertEquals(files.values.sumOf { it.size }.toLong(), progress.last())
            assertEquals(100L, installed.metadata.acquiredAtEpochMillis)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

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
        runtime = OcrNativeRuntimeDescriptor("b8935", "commit", "arm64-v8a", "cpu", "mtmd-v1"),
    )

    private fun fileDescriptor(fileName: String, bytes: ByteArray) = OcrModelFileDescriptor(
        fileName = fileName,
        byteLength = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        downloadUrl = "https://example.invalid/$fileName",
    )
}
