package rs.masumi.app.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

data class MangaLibraryProjectMetadata(
    val schemaVersion: Int = 1,
    val projectId: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val sourceTreeUri: String,
)

data class MangaLibraryProject(
    val metadata: MangaLibraryProjectMetadata,
    val directoryUri: Uri,
    val outputDirectoryUri: Uri?,
    val outputPageCount: Int,
)

data class MangaLibraryPage(
    val name: String,
    val uri: Uri,
)

class MangaLibraryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun rootUri(): Uri? = preferences.getString(KEY_ROOT_URI, null)
        ?.takeIf(String::isNotBlank)
        ?.let(Uri::parse)
        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(it) }

    fun saveRootUri(uri: Uri) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri))
        check(preferences.edit().putString(KEY_ROOT_URI, uri.toString()).commit())
    }

    private companion object {
        const val PREFERENCES_NAME = "manga_library"
        const val KEY_ROOT_URI = "root_uri"
    }
}

/**
 * Enumerating the library costs one `DocumentsProvider` query per directory, and
 * the main screen re-reads it on every resume. Snapshots are cached for the
 * process so a resume paints immediately, and every write invalidates the entry
 * that it touched.
 */
private object MangaLibraryCache {
    private val lock = Any()
    private val projectSnapshots = mutableMapOf<String, List<MangaLibraryProject>>()
    private val rootNames = mutableMapOf<String, String>()

    fun projects(root: String): List<MangaLibraryProject>? = synchronized(lock) { projectSnapshots[root] }

    fun putProjects(root: String, value: List<MangaLibraryProject>) {
        synchronized(lock) { projectSnapshots[root] = value }
    }

    fun rootName(root: String): String? = synchronized(lock) { rootNames[root] }

    fun putRootName(root: String, value: String) {
        synchronized(lock) { rootNames[root] = value }
    }

    fun invalidate(root: String) {
        synchronized(lock) { projectSnapshots.remove(root) }
    }
}

/**
 * The last known library snapshot, or null when this process has not scanned the
 * root yet. Callers render it straight away and then refresh in the background.
 */
fun cachedMangaLibraryProjects(rootTreeUri: Uri): List<MangaLibraryProject>? =
    MangaLibraryCache.projects(rootTreeUri.toString())

fun cachedMangaLibraryRootName(rootTreeUri: Uri): String? =
    MangaLibraryCache.rootName(rootTreeUri.toString())

/** Drops the cached snapshot when the tree was changed behind the store's back. */
fun invalidateMangaLibraryCache(rootTreeUri: Uri) {
    MangaLibraryCache.invalidate(rootTreeUri.toString())
}

class MangaLibraryStore(
    private val resolver: ContentResolver,
    private val rootTreeUri: Uri,
) {
    private val cacheKey = rootTreeUri.toString()

    init {
        require(
            rootTreeUri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(rootTreeUri),
        ) { "library root must be a document tree" }
    }

    fun rootDisplayName(): String = (displayName(rootDocumentUri()) ?: "Masumi")
        .also { MangaLibraryCache.putRootName(cacheKey, it) }

    fun ensureProject(
        projectId: String,
        title: String,
        createdAtEpochMillis: Long,
        sourceTreeUri: Uri,
    ): MangaLibraryProject {
        require(SAFE_ID.matches(projectId))
        refreshProjects().firstOrNull { it.metadata.projectId == projectId }?.let { existing ->
            val output = existing.outputDirectoryUri ?: ensureDirectory(existing.directoryUri, OUTPUT_DIRECTORY_NAME)
            return existing.copy(
                outputDirectoryUri = output,
                outputPageCount = outputPages(output).size,
            )
        }

        val normalizedTitle = title.trim().ifBlank { "未命名漫画" }.take(MAX_TITLE_LENGTH)
        val directoryName = "${safeDirectoryName(normalizedTitle)} · ${projectId.take(8)}"
        val projectDirectory = ensureDirectory(rootDocumentUri(), directoryName)
        val metadata = MangaLibraryProjectMetadata(
            projectId = projectId,
            title = normalizedTitle,
            createdAtEpochMillis = createdAtEpochMillis,
            sourceTreeUri = sourceTreeUri.toString(),
        )
        writeMetadata(projectDirectory, metadata)
        val outputDirectory = ensureDirectory(projectDirectory, OUTPUT_DIRECTORY_NAME)
        MangaLibraryCache.invalidate(cacheKey)
        return MangaLibraryProject(metadata, projectDirectory, outputDirectory, 0)
    }

    /** Cached snapshot when this process already scanned the root, otherwise a fresh scan. */
    fun projects(): List<MangaLibraryProject> =
        MangaLibraryCache.projects(cacheKey) ?: refreshProjects()

    /** Rescans the library root and replaces the cached snapshot. */
    fun refreshProjects(): List<MangaLibraryProject> = children(rootDocumentUri())
        .asSequence()
        .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
        .mapNotNull(::readProject)
        .sortedWith(
            compareByDescending<MangaLibraryProject> { it.metadata.createdAtEpochMillis }
                .thenByDescending { it.metadata.projectId },
        )
        .toList()
        .also { MangaLibraryCache.putProjects(cacheKey, it) }

    fun project(projectId: String): MangaLibraryProject? {
        if (!SAFE_ID.matches(projectId)) return null
        return projects().firstOrNull { it.metadata.projectId == projectId }
    }

    fun ensureOutputDirectory(projectId: String): Uri {
        require(SAFE_ID.matches(projectId)) { "library project id is invalid" }
        val project = requireNotNull(
            refreshProjects().firstOrNull { it.metadata.projectId == projectId },
        ) { "library project was not found" }
        return project.outputDirectoryUri ?: ensureDirectory(project.directoryUri, OUTPUT_DIRECTORY_NAME)
    }

    fun outputPages(projectId: String): List<MangaLibraryPage> {
        val project = project(projectId) ?: return emptyList()
        return project.outputDirectoryUri?.let(::outputPages).orEmpty()
    }

    fun documentDisplayName(uri: Uri): String? = displayName(
        runCatching {
            DocumentsContract.getDocumentId(uri)
            uri
        }.getOrElse {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        },
    )

    private fun readProject(directory: DocumentRef): MangaLibraryProject? = runCatching {
        // One children() call is one IPC round trip into the DocumentsProvider,
        // so read the project directory once and pick both entries out of it.
        val entries = children(directory.uri)
        val metadataDocument = entries.singleOrNull {
            it.displayName == METADATA_FILE_NAME && it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
        } ?: return@runCatching null
        val metadata = resolver.openInputStream(metadataDocument.uri)?.bufferedReader(Charsets.UTF_8)?.use {
            decodeMetadata(it.readText())
        } ?: return@runCatching null
        require(metadata.schemaVersion == 1 && SAFE_ID.matches(metadata.projectId) && metadata.title.isNotBlank())
        val output = entries.singleOrNull {
            it.displayName == OUTPUT_DIRECTORY_NAME && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.uri
        MangaLibraryProject(
            metadata = metadata,
            directoryUri = directory.uri,
            outputDirectoryUri = output,
            outputPageCount = output?.let(::outputPages)?.size ?: 0,
        )
    }.getOrNull()

    private fun outputPages(directoryUri: Uri): List<MangaLibraryPage> = children(directoryUri)
        .asSequence()
        .filter { it.mimeType.startsWith("image/") && OUTPUT_FILE.matches(it.displayName) }
        .sortedWith(compareBy<DocumentRef> { outputOrder(it.displayName) }.thenBy { it.displayName })
        .map { MangaLibraryPage(it.displayName, it.uri) }
        .toList()

    private fun writeMetadata(directoryUri: Uri, metadata: MangaLibraryProjectMetadata) {
        val existing = children(directoryUri).singleOrNull { it.displayName == METADATA_FILE_NAME }
        val metadataUri = existing?.uri ?: DocumentsContract.createDocument(
            resolver,
            directoryUri,
            "application/json",
            METADATA_FILE_NAME,
        ) ?: error("could not create library metadata")
        resolver.openOutputStream(metadataUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(encodeMetadata(metadata))
        } ?: error("could not write library metadata")
        MangaLibraryCache.invalidate(cacheKey)
    }

    private fun ensureDirectory(parentUri: Uri, displayName: String): Uri {
        children(parentUri).firstOrNull {
            it.displayName == displayName && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.let { return it.uri }
        return (
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                displayName,
            ) ?: error("could not create library directory")
            ).also { MangaLibraryCache.invalidate(cacheKey) }
    }

    private fun children(parentUri: Uri): List<DocumentRef> {
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, parentDocumentId)
        return resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn) ?: continue
                    val type = cursor.getString(typeColumn) ?: "application/octet-stream"
                    add(
                        DocumentRef(
                            uri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, id),
                            displayName = name,
                            mimeType = type,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun rootDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        rootTreeUri,
        DocumentsContract.getTreeDocumentId(rootTreeUri),
    )

    private fun safeDirectoryName(value: String): String = value
        .replace(UNSAFE_DIRECTORY_CHARACTER, " ")
        .replace(WHITESPACE, " ")
        .trim(' ', '.')
        .ifBlank { "未命名漫画" }
        .take(MAX_DIRECTORY_NAME_LENGTH)

    private fun outputOrder(name: String): Long = name.substringBeforeLast('.').toLongOrNull() ?: Long.MAX_VALUE

    private fun encodeMetadata(metadata: MangaLibraryProjectMetadata): String = JSONObject()
        .put("schemaVersion", metadata.schemaVersion)
        .put("projectId", metadata.projectId)
        .put("title", metadata.title)
        .put("createdAtEpochMillis", metadata.createdAtEpochMillis)
        .put("sourceTreeUri", metadata.sourceTreeUri)
        .toString()

    private fun decodeMetadata(content: String): MangaLibraryProjectMetadata {
        val value = JSONObject(content)
        return MangaLibraryProjectMetadata(
            schemaVersion = value.getInt("schemaVersion"),
            projectId = value.getString("projectId"),
            title = value.getString("title"),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            sourceTreeUri = value.getString("sourceTreeUri"),
        )
    }

    private data class DocumentRef(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    private companion object {
        const val METADATA_FILE_NAME = ".masumi-project.json"
        const val OUTPUT_DIRECTORY_NAME = "成品"
        const val MAX_TITLE_LENGTH = 120
        const val MAX_DIRECTORY_NAME_LENGTH = 48
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val OUTPUT_FILE = Regex("[0-9]{1,12}\\.(png|webp)", RegexOption.IGNORE_CASE)
        val UNSAFE_DIRECTORY_CHARACTER = Regex("[/\\\\:*?\"<>|]")
        val WHITESPACE = Regex("\\s+")
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
