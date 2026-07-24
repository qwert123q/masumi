package rs.masumi.app.library

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider

@RunWith(AndroidJUnit4::class)
class MangaLibraryModelCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )

    @Before
    fun setUp() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @Test
    fun backsUpAndRestoresAVerifiedModelPackage() {
        val cache = MangaLibraryModelCache(context.contentResolver, treeUri)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "model-cache-")
        try {
            val source = root.resolve("source")
            Files.createDirectories(source)
            val modelBytes = "verified model bytes".encodeToByteArray()
            val metadataBytes = """{"schemaVersion":1}""".encodeToByteArray()
            Files.write(source.resolve("model.onnx"), modelBytes)
            Files.write(source.resolve("package.json"), metadataBytes)
            val modelPackage = PersistentModelPackage(
                cacheKey = "漫画检测",
                version = "a".repeat(64),
                files = listOf(
                    PersistentModelFile(
                        fileName = "model.onnx",
                        byteLength = modelBytes.size.toLong(),
                        sha256 = sha256(modelBytes),
                    ),
                ),
            )
            val progress = mutableListOf<Pair<Long, Long>>()

            assertTrue(cache.backup(modelPackage, source) { completed, total ->
                progress += completed to total
            })
            val restored = root.resolve("restored")
            assertTrue(cache.restore(modelPackage, restored) { completed, total ->
                progress += completed to total
            })

            assertArrayEquals(modelBytes, Files.readAllBytes(restored.resolve("model.onnx")))
            assertArrayEquals(metadataBytes, Files.readAllBytes(restored.resolve("package.json")))
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.last().first == progress.last().second)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingLibraryPackageFallsBackWithoutTouchingTheTarget() {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "model-cache-missing-")
        try {
            val target = root.resolve("target")
            Files.createDirectories(target)
            Files.write(target.resolve("keep"), byteArrayOf(1))
            val restored = MangaLibraryModelCache(context.contentResolver, treeUri).restore(
                PersistentModelPackage(
                    cacheKey = "文字识别",
                    version = "b".repeat(64),
                    files = listOf(PersistentModelFile("model.gguf", 1L, "c".repeat(64))),
                ),
                target,
            ) { _, _ -> }

            assertFalse(restored)
            assertTrue(Files.exists(target.resolve("keep")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
