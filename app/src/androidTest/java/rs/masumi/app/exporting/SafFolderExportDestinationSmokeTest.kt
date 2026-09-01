package rs.masumi.app.exporting

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider

@RunWith(AndroidJUnit4::class)
class SafFolderExportDestinationSmokeTest {
    @Test
    fun publishesReusesAndReplacesVerifiedPngInGrantedTree() {
        val context = InstrumentationRegistry.getInstrumentation().context
        TestDocumentsProvider.clearDynamicDocuments(context)
        val destinationUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        try {
            val destination = SafFolderExportDestination(
                context.contentResolver,
                destinationUri.toString(),
                "folder-smoke",
                rs.masumi.app.library.OutputGeneration.publishedName(1L, "a".repeat(64)),
            )
            val first = png(Color.WHITE)
            val second = png(Color.LTGRAY)

            val initial = destination.publish("0001.png", first, sha256(first)) { false }
            val reused = destination.publish("0001.png", first, sha256(first)) { false }
            val replaced = destination.publish("0001.png", second, sha256(second)) { false }
            destination.publish("0002.png", first, sha256(first)) { false }
            destination.publish("0003.png", first, sha256(first)) { false }
            destination.commitCompleteSet(
                listOf(
                    ExpectedDestinationOutput("0001.png"),
                    ExpectedDestinationOutput("0002.png"),
                    ExpectedDestinationOutput("0003.png"),
                ),
            )
            destination.pruneManagedOutputs()

            assertFalse(initial.reusedExisting)
            assertTrue(reused.reusedExisting)
            assertFalse(replaced.reusedExisting)
            assertTrue(destination.matches("0001.png", sha256(second), second.size.toLong()))
            assertTrue(destination.matches("0002.png", sha256(first), first.size.toLong()))
            assertTrue(destination.matches("0003.png", sha256(first), first.size.toLong()))
        } finally {
            TestDocumentsProvider.clearDynamicDocuments(context)
        }
    }

    @Test
    fun publishesIntoAChildDirectoryOfTheGrantedTree() {
        val context = InstrumentationRegistry.getInstrumentation().context
        TestDocumentsProvider.clearDynamicDocuments(context)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        try {
            val childDirectory = requireNotNull(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        TestDocumentsProvider.ROOT_ID,
                    ),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    "成品",
                ),
            )
            val destination = SafFolderExportDestination(
                context.contentResolver,
                childDirectory.toString(),
                "nested-folder-smoke",
                rs.masumi.app.library.OutputGeneration.publishedName(1L, "b".repeat(64)),
            )
            val bytes = png(Color.WHITE)

            destination.publish("0001.png", bytes, sha256(bytes)) { false }
            destination.commitCompleteSet(
                listOf(ExpectedDestinationOutput("0001.png")),
            )

            assertTrue(destination.matches("0001.png", sha256(bytes), bytes.size.toLong()))
        } finally {
            TestDocumentsProvider.clearDynamicDocuments(context)
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
