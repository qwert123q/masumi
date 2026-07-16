package rs.masumi.app.exporting

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

data class DestinationWriteResult(val reusedExisting: Boolean)

interface FolderExportDestination {
    fun matches(outputName: String, expectedSha256: String, expectedByteLength: Long): Boolean

    fun publish(
        outputName: String,
        bytes: ByteArray,
        expectedSha256: String,
        cancellation: () -> Boolean,
    ): DestinationWriteResult

    fun pruneManagedOutputs(expectedNames: Set<String>)
}

class SafFolderExportDestination(
    private val resolver: ContentResolver,
    treeUriString: String,
    jobId: String,
) : FolderExportDestination {
    private val treeUri = Uri.parse(treeUriString)
    private val parentUri: Uri
    private val temporaryPrefix = ".masumi-${jobId.take(32)}-"

    init {
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(treeUri)) {
            throw ExportDestinationException("DESTINATION_INVALID")
        }
        parentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrElse { throw ExportDestinationException("DESTINATION_INVALID") }
    }

    override fun matches(outputName: String, expectedSha256: String, expectedByteLength: Long): Boolean {
        val exact = children().filter { it.displayName == outputName }
        if (exact.size != 1) return false
        return verify(exact.single().uri, expectedSha256, expectedByteLength) { false }
    }

    override fun publish(
        outputName: String,
        bytes: ByteArray,
        expectedSha256: String,
        cancellation: () -> Boolean,
    ): DestinationWriteResult {
        require(OUTPUT_NAME.matches(outputName))
        require(SHA256.matches(expectedSha256) && bytes.isNotEmpty())
        if (cancellation()) throw ExportCancellationSignal()
        val existing = children().filter { it.displayName == outputName }
        if (existing.size == 1 && verify(existing.single().uri, expectedSha256, bytes.size.toLong(), cancellation)) {
            return DestinationWriteResult(reusedExisting = true)
        }
        val temporaryName = "$temporaryPrefix$outputName"
        children().filter { it.displayName == temporaryName }.forEach { deleteQuietly(it.uri) }
        val temporary = create(temporaryName)
        var promoted = false
        try {
            writeAndVerify(temporary, bytes, expectedSha256, cancellation)
            if (cancellation()) throw ExportCancellationSignal()
            children().filter { it.displayName == outputName }.forEach { document ->
                if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_REPLACE_FAILED")
            }
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, temporary, outputName)
            }.getOrNull()
            val finalUri = if (renamed != null && displayName(renamed) == outputName) {
                promoted = true
                renamed
            } else {
                val direct = create(outputName)
                try {
                    if (displayName(direct) != outputName) {
                        throw ExportDestinationException("DESTINATION_NAMING_UNSUPPORTED")
                    }
                    writeAndVerify(direct, bytes, expectedSha256, cancellation)
                    promoted = true
                    deleteQuietly(temporary)
                    direct
                } catch (failure: Throwable) {
                    deleteQuietly(direct)
                    throw failure
                }
            }
            if (!verify(finalUri, expectedSha256, bytes.size.toLong(), cancellation)) {
                deleteQuietly(finalUri)
                throw ExportDestinationException("DESTINATION_FINAL_VERIFY_FAILED")
            }
            val duplicates = children().filter { it.displayName == outputName && it.uri != finalUri }
            duplicates.forEach { deleteQuietly(it.uri) }
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
            if (!promoted) deleteQuietly(temporary)
        }
    }

    override fun pruneManagedOutputs(expectedNames: Set<String>) {
        require(expectedNames.isNotEmpty() && expectedNames.all(OUTPUT_NAME::matches))
        children().filter { document ->
            (OUTPUT_NAME.matches(document.displayName) && document.displayName !in expectedNames) ||
                document.displayName.startsWith(".masumi-")
        }.forEach { document ->
            if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_PRUNE_FAILED")
        }
    }

    private fun writeAndVerify(
        uri: Uri,
        bytes: ByteArray,
        expectedSha256: String,
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
        if (!verify(uri, expectedSha256, bytes.size.toLong(), cancellation)) {
            throw ExportDestinationException("DESTINATION_TEMP_VERIFY_FAILED")
        }
    }

    private fun verify(
        uri: Uri,
        expectedSha256: String,
        expectedByteLength: Long,
        cancellation: () -> Boolean,
    ): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.buffered()?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (cancellation()) throw ExportCancellationSignal()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } ?: return@runCatching false
        total == expectedByteLength && digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256
    }.getOrElse {
        if (it is ExportCancellationSignal) throw it
        false
    }

    private fun children(): List<DocumentRef> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        return try {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn)
                        val name = cursor.getString(nameColumn) ?: continue
                        add(DocumentRef(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name))
                    }
                }
            } ?: throw ExportDestinationException("DESTINATION_QUERY_FAILED")
        } catch (_: SecurityException) {
            throw ExportDestinationException("DESTINATION_PERMISSION_DENIED")
        }
    }

    private fun create(displayName: String): Uri = try {
        DocumentsContract.createDocument(resolver, parentUri, "image/png", displayName)
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

    private data class DocumentRef(val uri: Uri, val displayName: String)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val OUTPUT_NAME = Regex("[0-9]{1,12}\\.png")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

class ExportDestinationException(val code: String) : RuntimeException(code)
class ExportCancellationSignal : RuntimeException()
