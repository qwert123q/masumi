package rs.masumi.app

import android.database.Cursor
import android.database.MatrixCursor
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
            synchronized(DYNAMIC_DOCUMENTS) {
                DYNAMIC_DOCUMENTS.keys.sorted().forEach { addDocumentRow(it) }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId != ROOT_ID && document(documentId) != null

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val dynamic = synchronized(DYNAMIC_DOCUMENTS) { DYNAMIC_DOCUMENTS[documentId] }
        if (dynamic != null) {
            return ParcelFileDescriptor.open(dynamic.file, ParcelFileDescriptor.parseMode(mode))
        }
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

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == ROOT_ID && displayName.isNotBlank())
        val id = "created-${NEXT_ID.incrementAndGet()}"
        val file = File(requireNotNull(context).cacheDir, "provider-$id").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
        }
        synchronized(DYNAMIC_DOCUMENTS) {
            DYNAMIC_DOCUMENTS[id] = DynamicDocument(displayName, mimeType, file)
        }
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        synchronized(DYNAMIC_DOCUMENTS) {
            val current = requireNotNull(DYNAMIC_DOCUMENTS[documentId])
            DYNAMIC_DOCUMENTS[documentId] = current.copy(displayName = displayName)
        }
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        val removed = synchronized(DYNAMIC_DOCUMENTS) { DYNAMIC_DOCUMENTS.remove(documentId) }
        removed?.file?.delete()
    }

    private fun MatrixCursor.addDocumentRow(documentId: String) {
        val document = requireNotNull(document(documentId))
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, document.displayName)
            .add(Document.COLUMN_MIME_TYPE, document.mediaType)
            .add(Document.COLUMN_FLAGS, document.flags)
            .add(Document.COLUMN_SIZE, document.byteLength)
    }

    private fun document(documentId: String): TestDocument? = DOCUMENTS[documentId]
        ?: synchronized(DYNAMIC_DOCUMENTS) {
            DYNAMIC_DOCUMENTS[documentId]?.let { dynamic ->
                TestDocument(
                    displayName = dynamic.displayName,
                    mediaType = dynamic.mediaType,
                    byteLength = dynamic.file.length(),
                    flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_RENAME,
                )
            }
        }

    private data class TestDocument(
        val displayName: String,
        val mediaType: String,
        val byteLength: Long,
        val flags: Int = Document.FLAG_SUPPORTS_THUMBNAIL,
    )

    private data class DynamicDocument(
        val displayName: String,
        val mediaType: String,
        val file: File,
    )

    companion object {
        const val AUTHORITY = "rs.masumi.app.dev.test.documents"
        const val ROOT_ID = "root"

        fun clearDynamicDocuments(context: Context) {
            synchronized(DYNAMIC_DOCUMENTS) {
                DYNAMIC_DOCUMENTS.values.forEach { it.file.delete() }
                DYNAMIC_DOCUMENTS.clear()
            }
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("provider-created-") }
                ?.forEach(File::delete)
        }

        private val DOCUMENTS = mapOf(
            ROOT_ID to TestDocument(
                "Test chapter",
                Document.MIME_TYPE_DIR,
                0,
                Document.FLAG_DIR_SUPPORTS_CREATE,
            ),
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
        private val DYNAMIC_DOCUMENTS = linkedMapOf<String, DynamicDocument>()
        private val NEXT_ID = AtomicInteger()
    }
}
