package rs.masumi.app.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.modelpackage.DetectorModelDescriptor
import rs.masumi.core.modelpackage.DetectorModelSignature
import rs.masumi.core.modelpackage.ModelPackageErrorCode
import rs.masumi.core.modelpackage.ModelPackageException
import rs.masumi.core.modelpackage.ModelSignatureValidator
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DetectorModelProviderTest {
    @Test
    fun installsFromInjectedStreamAndReportsMonotonicProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "provider-")
        val bytes = "detector-model".encodeToByteArray()
        val progress = mutableListOf<Long>()
        val provider = DefaultDetectorModelProvider(
            workspaceRoot = workspace,
            descriptor = descriptor(bytes),
            streamSource = ModelStreamSource { ByteArrayInputStream(bytes) },
            signatureValidator = ModelSignatureValidator { signature() },
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )

        try {
            val model = provider.acquire("install-1") { downloaded, _ -> progress += downloaded }

            assertTrue(Files.exists(model))
            assertTrue(Files.readAllBytes(model).contentEquals(bytes))
            assertEquals(progress.sorted(), progress)
            assertEquals(bytes.size.toLong(), progress.last())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun mapsStreamFailureToSafeInstallError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "provider-failure-")
        val bytes = "detector-model".encodeToByteArray()
        val provider = DefaultDetectorModelProvider(
            workspaceRoot = workspace,
            descriptor = descriptor(bytes),
            streamSource = ModelStreamSource { throw IOException("network details") },
            signatureValidator = ModelSignatureValidator { signature() },
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )

        try {
            val failure = try {
                provider.acquire("install-1") { _, _ -> }
                throw AssertionError("Expected model acquisition failure")
            } catch (error: ModelPackageException) {
                error
            }
            assertEquals(ModelPackageErrorCode.INSTALL_IO, failure.code)
            assertEquals(ModelPackageErrorCode.INSTALL_IO.safeMessage, failure.message)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun reusesLegacyNamedPackageWithoutRedownloading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "provider-legacy-")
        val bytes = "detector-model".encodeToByteArray()
        val descriptor = descriptor(bytes)
        val firstProvider = DefaultDetectorModelProvider(
            workspaceRoot = workspace,
            descriptor = descriptor,
            streamSource = ModelStreamSource { ByteArrayInputStream(bytes) },
            signatureValidator = ModelSignatureValidator { signature() },
        )

        try {
            val installed = firstProvider.acquire("install-1") { _, _ -> }
            val metadataPath = installed.parent.resolve("package.json")
            val legacyMetadata = Files.newBufferedReader(metadataPath, Charsets.UTF_8).use { reader ->
                reader.readText().replace(
                    "  \"storageRevision\": \"${descriptor.storageRevision}\",\n",
                    "",
                )
            }
            Files.newBufferedWriter(metadataPath, Charsets.UTF_8).use { writer ->
                writer.write(legacyMetadata)
            }
            val legacyDirectory = installed.parent.parent.resolve("legacy-opaque-directory")
            Files.move(installed.parent, legacyDirectory)
            val secondProvider = DefaultDetectorModelProvider(
                workspaceRoot = workspace,
                descriptor = descriptor,
                streamSource = ModelStreamSource { throw IOException("must reuse installed package") },
                signatureValidator = ModelSignatureValidator { signature() },
            )

            val reused = secondProvider.acquire("install-2") { _, _ -> }

            assertEquals(legacyDirectory.resolve("model.onnx"), reused)
            assertTrue(
                Files.newBufferedReader(legacyDirectory.resolve("package.json"), Charsets.UTF_8).use { reader ->
                    reader.readText().contains("storageRevision")
                },
            )
        } finally {
            workspace.toFile().deleteRecursively()
        }
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
}
