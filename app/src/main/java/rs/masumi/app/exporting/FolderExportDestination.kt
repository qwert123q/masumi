package rs.masumi.app.exporting

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

data class DestinationWriteResult(val reusedExisting: Boolean)

data class ExpectedDestinationOutput(
    val outputName: String,
)

internal fun destinationOutputNamesMatch(
    actualNames: List<String>,
    expectedNames: Set<String>,
): Boolean = actualNames.size == expectedNames.size && actualNames.toSet() == expectedNames

internal fun destinationOutputLengthMatches(actualByteLength: Long?, expectedByteLength: Long): Boolean =
    expectedByteLength > 0L && actualByteLength == expectedByteLength

interface FolderExportDestination {
    fun matches(outputName: String, expectedByteLength: Long): Boolean

    fun publish(
        outputName: String,
        bytes: ByteArray,
        cancellation: () -> Boolean,
    ): DestinationWriteResult

    /** Verifies the staged filename set, then makes it visible in one directory promotion. */
    fun commitCompleteSet(expectedOutputs: List<ExpectedDestinationOutput>)

    /** Hides a just-published generation again if durable completion fails. */
    fun rollbackCommittedSet()

    /** Best-effort cleanup that runs only after the success report and job are durable. */
    fun pruneManagedOutputs()
}

class SafFolderExportDestination(
    private val resolver: ContentResolver,
    treeUriString: String,
    jobId: String,
    private val generationName: String,
) : FolderExportDestination {
    private val treeUri = Uri.parse(treeUriString)
    private val parentUri: Uri
    private val stagingName = "${rs.masumi.app.library.OutputGeneration.STAGING_PREFIX}${jobId.take(48)}"
    private var workingDirectoryUri: Uri
    private var published = false

    init {
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(treeUri)) {
            throw ExportDestinationException("DESTINATION_INVALID")
        }
        parentUri = runCatching {
            runCatching {
                DocumentsContract.getDocumentId(treeUri)
                treeUri
            }.getOrElse {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            }
        }.getOrElse { throw ExportDestinationException("DESTINATION_INVALID") }
        require(rs.masumi.app.library.OutputGeneration.isPublishedDirectory(generationName))
        val existingGeneration = children(parentUri).singleOrNull {
            it.displayName == generationName && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }
        if (existingGeneration != null) {
            workingDirectoryUri = existingGeneration.uri
            published = true
        } else {
            workingDirectoryUri = ensureDirectory(parentUri, stagingName)
        }
    }

    override fun matches(outputName: String, expectedByteLength: Long): Boolean {
        val exact = children(workingDirectoryUri).filter { it.displayName == outputName }
        if (exact.size != 1) return false
        return destinationOutputLengthMatches(exact.single().byteLength, expectedByteLength)
    }

    override fun publish(
        outputName: String,
        bytes: ByteArray,
        cancellation: () -> Boolean,
    ): DestinationWriteResult {
        require(OUTPUT_NAME.matches(outputName))
        require(bytes.isNotEmpty())
        if (cancellation()) throw ExportCancellationSignal()
        if (published) rollbackCommittedSet()
        val existing = children(workingDirectoryUri).filter { it.displayName == outputName }
        if (
            existing.size == 1 &&
            destinationOutputLengthMatches(existing.single().byteLength, bytes.size.toLong())
        ) {
            return DestinationWriteResult(reusedExisting = true)
        }
        existing.forEach { document ->
            if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_REPLACE_FAILED")
        }
        val outputUri = create(workingDirectoryUri, outputName)
        var complete = false
        try {
            if (displayName(outputUri) != outputName) {
                throw ExportDestinationException("DESTINATION_NAMING_UNSUPPORTED")
            }
            writeBytes(outputUri, bytes, cancellation)
            if (cancellation()) throw ExportCancellationSignal()
            val publishedDocuments = children(workingDirectoryUri).filter { it.displayName == outputName }
            val publishedDocument = publishedDocuments.singleOrNull()
            if (
                publishedDocument == null ||
                publishedDocument.uri != outputUri ||
                !destinationOutputLengthMatches(publishedDocument.byteLength, bytes.size.toLong())
            ) {
                throw ExportDestinationException("DESTINATION_FINAL_VERIFY_FAILED")
            }
            complete = true
            return DestinationWriteResult(reusedExisting = false)
        } catch (failure: SecurityException) {
            throw ExportDestinationException("DESTINATION_PERMISSION_DENIED")
        } catch (failure: ExportCancellationSignal) {
            throw failure
        } catch (failure: ExportDestinationException) {
            throw failure
        } catch (_: Throwable) {
            throw ExportDestinationException("DESTINATION_WRITE_FAILED")
        } finally {
            if (!complete) deleteQuietly(outputUri)
        }
    }

    override fun commitCompleteSet(expectedOutputs: List<ExpectedDestinationOutput>) {
        require(expectedOutputs.isNotEmpty())
        require(expectedOutputs.map { it.outputName }.distinct().size == expectedOutputs.size)
        require(expectedOutputs.all { OUTPUT_NAME.matches(it.outputName) })
        val expectedNames = expectedOutputs.mapTo(linkedSetOf(), ExpectedDestinationOutput::outputName)
        children(workingDirectoryUri)
            .filter { it.displayName.startsWith(".masumi-") }
            .forEach { document ->
                if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_PRUNE_FAILED")
            }
        val actual = children(workingDirectoryUri)
            .map(DocumentRef::displayName)
        if (!destinationOutputNamesMatch(actual, expectedNames)) {
            throw ExportDestinationException("DESTINATION_FINAL_SET_INVALID")
        }
        if (published) return
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, workingDirectoryUri, generationName)
        }.getOrNull()
        if (renamed == null || displayName(renamed) != generationName) {
            throw ExportDestinationException("DESTINATION_ATOMIC_PROMOTION_UNSUPPORTED")
        }
        workingDirectoryUri = renamed
        published = true
    }

    override fun rollbackCommittedSet() {
        if (!published) return
        children(parentUri).filter { document ->
            document.displayName == stagingName && document.uri != workingDirectoryUri
        }.forEach { deleteQuietly(it.uri) }
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, workingDirectoryUri, stagingName)
        }.getOrNull()
        if (renamed != null && displayName(renamed) == stagingName) {
            workingDirectoryUri = renamed
            published = false
            return
        }
        if (!delete(workingDirectoryUri)) {
            throw ExportDestinationException("DESTINATION_ROLLBACK_FAILED")
        }
        workingDirectoryUri = ensureDirectory(parentUri, stagingName)
        published = false
    }

    override fun pruneManagedOutputs() {
        check(published) { "generation must be committed before pruning" }
        // The selected SAF directory may contain user-owned numeric images or
        // another recoverable export's staging directory. After this job's
        // staging directory has been renamed to its published generation there
        // is no remaining root entry whose ownership this instance can prove.
    }

    private fun writeBytes(
        uri: Uri,
        bytes: ByteArray,
        cancellation: () -> Boolean,
    ) {
        resolver.openOutputStream(uri, "w")?.use { output ->
            var offset = 0
            while (offset < bytes.size) {
                if (cancellation()) throw ExportCancellationSignal()
                val count = minOf(BUFFER_SIZE, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
            output.flush()
        } ?: throw ExportDestinationException("DESTINATION_OPEN_FAILED")
    }

    private fun children(directoryUri: Uri): List<DocumentRef> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(directoryUri),
        )
        return try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn)
                        val name = cursor.getString(nameColumn) ?: continue
                        add(
                            DocumentRef(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name,
                                cursor.getString(typeColumn) ?: "application/octet-stream",
                                if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                            ),
                        )
                    }
                }
            } ?: throw ExportDestinationException("DESTINATION_QUERY_FAILED")
        } catch (_: SecurityException) {
            throw ExportDestinationException("DESTINATION_PERMISSION_DENIED")
        }
    }

    private fun create(directoryUri: Uri, displayName: String): Uri = try {
        val mimeType = if (displayName.endsWith(".webp", ignoreCase = true)) "image/webp" else "image/png"
        DocumentsContract.createDocument(resolver, directoryUri, mimeType, displayName)
            ?: throw ExportDestinationException("DESTINATION_CREATE_FAILED")
    } catch (_: SecurityException) {
        throw ExportDestinationException("DESTINATION_PERMISSION_DENIED")
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: SecurityException) {
        throw ExportDestinationException("DESTINATION_PERMISSION_DENIED")
    }

    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private fun ensureDirectory(parent: Uri, displayName: String): Uri {
        children(parent).singleOrNull {
            it.displayName == displayName && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.let { return it.uri }
        return DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        ) ?: throw ExportDestinationException("DESTINATION_CREATE_FAILED")
    }

    private data class DocumentRef(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val byteLength: Long?,
    )

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val OUTPUT_NAME = Regex("[0-9]{1,12}\\.(png|webp)")
    }
}

class ExportDestinationException(val code: String) : RuntimeException(code)
class ExportCancellationSignal : RuntimeException()
