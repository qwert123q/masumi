package rs.masumi.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_PROJECTION).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Test chapter")
                .add(Root.COLUMN_FLAGS, 0)
                .add(Root.COLUMN_ICON, 0)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
            addDocumentRow(documentId)
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
        if (parentDocumentId == ROOT_ID) {
            addDocumentRow("image-1")
            addDocumentRow("image-2")
            addDocumentRow("notes")
            addDocumentRow("nested")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId in DOCUMENTS && documentId != ROOT_ID

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val content = when (documentId) {
            "image-1" -> "one"
            "image-2" -> "two"
            "notes" -> "notes"
            else -> error("Unsupported test document: $documentId")
        }
        val file = File(requireNotNull(context).cacheDir, "provider-$documentId")
        file.writeText(content)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun MatrixCursor.addDocumentRow(documentId: String) {
        val document = DOCUMENTS.getValue(documentId)
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, document.displayName)
            .add(Document.COLUMN_MIME_TYPE, document.mediaType)
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
            .add(Document.COLUMN_SIZE, document.byteLength)
    }

    private data class TestDocument(
        val displayName: String,
        val mediaType: String,
        val byteLength: Long,
    )

    companion object {
        const val AUTHORITY = "rs.masumi.app.dev.test.documents"
        const val ROOT_ID = "root"

        private val DOCUMENTS = mapOf(
            ROOT_ID to TestDocument("Test chapter", Document.MIME_TYPE_DIR, 0),
            "image-1" to TestDocument("1.jpg", "image/jpeg", 3),
            "image-2" to TestDocument("2.png", "image/png", 3),
            "notes" to TestDocument("notes.txt", "text/plain", 5),
            "nested" to TestDocument("nested", Document.MIME_TYPE_DIR, 0),
        )
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
