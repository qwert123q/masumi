package rs.masumi.app.exporting

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

data class DestinationWriteResult(val reusedExisting: Boolean)

data class ExpectedDestinationOutput(
    val outputName: String,
)

internal fun destinationOutputNamesMatch(
    actualNames: List<String>,
    expectedNames: Set<String>,
): Boolean = actualNames.size == expectedNames.size && actualNames.toSet() == expectedNames

internal fun <T : Any> unusableRenameDocument(temporary: T, renamed: T?): T = renamed ?: temporary

interface FolderExportDestination {
    fun matches(outputName: String, expectedSha256: String, expectedByteLength: Long): Boolean

    fun publish(
        outputName: String,
        bytes: ByteArray,
        expectedSha256: String,
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
    private val temporaryPrefix = ".masumi-${jobId.take(32)}-"
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

    override fun matches(outputName: String, expectedSha256: String, expectedByteLength: Long): Boolean {
        val exact = children(workingDirectoryUri).filter { it.displayName == outputName }
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
        if (published) rollbackCommittedSet()
        val existing = children(workingDirectoryUri).filter { it.displayName == outputName }
        if (existing.size == 1 && verify(existing.single().uri, expectedSha256, bytes.size.toLong(), cancellation)) {
            return DestinationWriteResult(reusedExisting = true)
        }
        val temporaryName = "$temporaryPrefix$outputName"
        children(workingDirectoryUri).filter { it.displayName == temporaryName }.forEach { deleteQuietly(it.uri) }
        val temporary = create(workingDirectoryUri, temporaryName)
        var promoted = false
        try {
            writeBytes(temporary, bytes, cancellation)
            if (cancellation()) throw ExportCancellationSignal()
            children(workingDirectoryUri).filter { it.displayName == outputName }.forEach { document ->
                if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_REPLACE_FAILED")
            }
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, temporary, outputName)
            }.getOrNull()
            val finalUri = if (renamed != null && displayName(renamed) == outputName) {
                promoted = true
                renamed
            } else {
                val staleDocuments = linkedSetOf(unusableRenameDocument(temporary, renamed))
                children(workingDirectoryUri)
                    .filter { it.displayName == temporaryName }
                    .mapTo(staleDocuments, DocumentRef::uri)
                staleDocuments.forEach { stale ->
                    if (!delete(stale)) throw ExportDestinationException("DESTINATION_REPLACE_FAILED")
                }
                val direct = create(workingDirectoryUri, outputName)
                try {
                    if (displayName(direct) != outputName) {
                        throw ExportDestinationException("DESTINATION_NAMING_UNSUPPORTED")
                    }
                    writeBytes(direct, bytes, cancellation)
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
            val duplicates = children(workingDirectoryUri).filter { it.displayName == outputName && it.uri != finalUri }
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
            .filter { it.mimeType.startsWith("image/") }
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
        children(parentUri).filter { document ->
            OUTPUT_NAME.matches(document.displayName) ||
                document.displayName.startsWith(rs.masumi.app.library.OutputGeneration.STAGING_PREFIX)
        }.forEach { document ->
            if (!delete(document.uri)) throw ExportDestinationException("DESTINATION_PRUNE_FAILED")
        }
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
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn)
                        val name = cursor.getString(nameColumn) ?: continue
                        add(
                            DocumentRef(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name,
                                cursor.getString(typeColumn) ?: "application/octet-stream",
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

    private data class DocumentRef(val uri: Uri, val displayName: String, val mimeType: String)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val OUTPUT_NAME = Regex("[0-9]{1,12}\\.(png|webp)")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

class ExportDestinationException(val code: String) : RuntimeException(code)
class ExportCancellationSignal : RuntimeException()
