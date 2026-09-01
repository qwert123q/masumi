package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val storageRoot = workspace.resolve("models").resolve(descriptor.storageKey)
        assumeTrue(Files.isDirectory(storageRoot))
        val directory = Files.list(storageRoot).use { paths ->
            paths.filter(Files::isDirectory)
                .filter { candidate ->
                    Files.isRegularFile(candidate.resolve(descriptor.model.fileName)) &&
                        Files.isRegularFile(candidate.resolve(descriptor.projector.fileName)) &&
                        Files.isRegularFile(candidate.resolve("package.json"))
                }
                .findFirst()
                .orElse(null)
        }
        assumeTrue(directory != null)
        requireNotNull(directory)
        val model = directory.resolve(descriptor.model.fileName)
        val projector = directory.resolve(descriptor.projector.fileName)
        val metadataPath = directory.resolve("package.json")
        assumeTrue(Files.isRegularFile(model) && Files.isRegularFile(projector) && Files.isRegularFile(metadataPath))

        assertEquals(descriptor.model.byteLength, Files.size(model))
        assertEquals(descriptor.projector.byteLength, Files.size(projector))
        val metadataTime = Files.getLastModifiedTime(metadataPath)
        assertTrue(Files.getLastModifiedTime(model) <= metadataTime)
        assertTrue(Files.getLastModifiedTime(projector) <= metadataTime)
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
}
