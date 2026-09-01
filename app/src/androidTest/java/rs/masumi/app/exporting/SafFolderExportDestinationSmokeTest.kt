package rs.masumi.app.exporting

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider

@RunWith(AndroidJUnit4::class)
class SafFolderExportDestinationSmokeTest {
    @Test
    fun publishesReusesSameLengthAndReplacesDifferentLengthOutputInGrantedTree() {
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
                rs.masumi.app.library.OutputGeneration.publishedName(1L, "export-run.a"),
            )
            val first = png(Color.WHITE)
            val sameLengthDifferentBytes = first.copyOf().apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            val differentLength = first + 0.toByte()

            val initial = destination.publish("0001.png", first) { false }
            val reused = destination.publish("0001.png", sameLengthDifferentBytes) { false }
            val replaced = destination.publish("0001.png", differentLength) { false }
            destination.publish("0002.png", first) { false }
            destination.publish("0003.png", first) { false }
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
            assertTrue(destination.matches("0001.png", differentLength.size.toLong()))
            assertTrue(destination.matches("0002.png", first.size.toLong()))
            assertTrue(destination.matches("0003.png", first.size.toLong()))
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
                rs.masumi.app.library.OutputGeneration.publishedName(1L, "export-run.b"),
            )
            val bytes = png(Color.WHITE)

            destination.publish("0001.png", bytes) { false }
            destination.commitCompleteSet(
                listOf(ExpectedDestinationOutput("0001.png")),
            )

            assertTrue(destination.matches("0001.png", bytes.size.toLong()))
        } finally {
            TestDocumentsProvider.clearDynamicDocuments(context)
        }
    }

    @Test
    fun pruneKeepsUnownedRootImageAndAnotherJobsStagingDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().context
        TestDocumentsProvider.clearDynamicDocuments(context)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        try {
            requireNotNull(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    "image/png",
                    "0001.png",
                ),
            )
            requireNotNull(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    ".masumi-staging-other",
                ),
            )
            val destination = SafFolderExportDestination(
                context.contentResolver,
                treeUri.toString(),
                "current-job",
                rs.masumi.app.library.OutputGeneration.publishedName(2L, "export-run.current"),
            )
            val bytes = png(Color.WHITE)

            destination.publish("0002.png", bytes) { false }
            destination.commitCompleteSet(listOf(ExpectedDestinationOutput("0002.png")))
            destination.pruneManagedOutputs()

            val rootNames = childDisplayNames(treeUri)
            assertTrue(rootNames.contains("0001.png"))
            assertTrue(rootNames.contains(".masumi-staging-other"))
        } finally {
            TestDocumentsProvider.clearDynamicDocuments(context)
        }
    }

    private fun childDisplayNames(treeUri: Uri): Set<String> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        return requireNotNull(
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            ),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
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
}
