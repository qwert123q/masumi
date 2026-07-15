package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.modelpackage.OcrModelPackageStore
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.serialization.OcrJson

@RunWith(AndroidJUnit4::class)
class OcrModelPackageReuseTest {
    @Test
    fun verifiedNormalizedPackageIsReusableAfterProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = context.filesDir.toPath().resolve("workspace")
        val descriptor = PinnedPaddleOcrVl.descriptor
        val directory = workspace.resolve("models")
            .resolve(descriptor.storageKey)
            .resolve(descriptor.packageSha256)
        val model = directory.resolve(descriptor.model.fileName)
        val projector = directory.resolve(descriptor.projector.fileName)
        val metadataPath = directory.resolve("package.json")
        assumeTrue(Files.isRegularFile(model) && Files.isRegularFile(projector) && Files.isRegularFile(metadataPath))

        assertEquals(descriptor.model.byteLength, Files.size(model))
        assertEquals(descriptor.projector.byteLength, Files.size(projector))
        assertEquals(descriptor.model.installedSha256, sha256(model))
        assertEquals(descriptor.projector.installedSha256, sha256(projector))
        val metadata = OcrJson().decodeModelPackageMetadata(
            Files.newBufferedReader(metadataPath).use { it.readText() },
        )
        assertEquals(1, metadata.schemaVersion)
        assertEquals(descriptor.toRef(), metadata.modelPackage)
        assertEquals(descriptor.runtime.toRef(), metadata.runtime)
        assertEquals(descriptor.prompt, metadata.prompt)
        assertEquals(
            metadata.capabilities,
            NativePaddleOcrCapabilityValidator().validate(model, projector),
        )

        val installed = OcrModelPackageStore(workspace).readInstalled(
            descriptor,
            NativePaddleOcrCapabilityValidator(),
        )
        assertNotNull(installed)
        assertEquals(model, installed?.model)
        assertEquals(projector, installed?.projector)
    }

    private fun sha256(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
