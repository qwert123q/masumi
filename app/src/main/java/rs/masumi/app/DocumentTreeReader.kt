package rs.masumi.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import rs.masumi.core.importer.SourceCandidate

class DocumentTreeReader(
    private val resolver: ContentResolver,
) {
    fun read(treeUri: Uri): List<SourceCandidate> {
        val parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val sources = mutableListOf<SourceCandidate>()

        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idColumn)
                val displayName = cursor.getString(nameColumn) ?: documentId
                val mediaType = cursor.getString(mediaTypeColumn)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                sources += AndroidDocumentSource(
                    resolver = resolver,
                    uri = documentUri,
                    displayName = displayName,
                    mediaType = mediaType,
                    isDirectory = mediaType == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }

        return sources
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
