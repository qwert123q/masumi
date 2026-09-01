package rs.masumi.app.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import rs.masumi.core.importer.PageMediaType
import rs.masumi.core.model.PageRecord

data class MangaLibraryProjectMetadata(
    val schemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val projectId: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val sourceTreeUri: String,
    val sourceFingerprint: String = "",
)

data class MangaLibraryProject(
    val metadata: MangaLibraryProjectMetadata,
    /** One visible folder is one manga project. */
    val directoryUri: Uri,
    val mangaDirectoryUri: Uri,
    val sourceDirectoryUri: Uri?,
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

    fun clearRootUri() {
        check(preferences.edit().remove(KEY_ROOT_URI).commit())
    }

    private companion object {
        const val PREFERENCES_NAME = "manga_library"
        const val KEY_ROOT_URI = "root_uri"
    }
}

fun mangaSourceFingerprint(pages: List<PageRecord>): String {
    require(pages.isNotEmpty())
    val digest = MessageDigest.getInstance("SHA-256")
    pages.sortedBy(PageRecord::order).forEach { page ->
        require(SHA256.matches(page.sourceSha256))
        digest.update(page.sourceSha256.toByteArray(Charsets.US_ASCII))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun mangaArchiveSourceFileName(page: PageRecord): String =
    mangaArchiveStableFileName(page, alternateOrdinal = null)

internal fun mangaArchiveAlternativeSourceFileName(page: PageRecord, ordinal: Int): String {
    require(ordinal >= 2)
    return mangaArchiveStableFileName(page, alternateOrdinal = ordinal)
}

private fun mangaArchiveStableFileName(page: PageRecord, alternateOrdinal: Int?): String {
    require(page.order >= 0)
    require(SHA256.matches(page.sourceSha256))
    val extension = requireNotNull(PageMediaType.detect(page.originalName, page.mediaType)).extension
    val sanitizedName = page.originalName
        .replace(UNSAFE_ARCHIVE_FILE_NAME_CHARACTER, "_")
        .filterNot(Char::isISOControl)
    val safeStem = sanitizedName
        .substringBeforeLast('.', sanitizedName)
        .trim(' ', '.', '_')
        .ifBlank { "page" }
    val order = (page.order.toLong() + 1L).toString().padStart(6, '0')
    val identityPrefix = "$order-${page.sourceSha256}-"
    val alternateSuffix = alternateOrdinal?.let { "~$it" }.orEmpty()
    val extensionSuffix = ".$extension"
    val maximumStemLength = MAX_ARCHIVE_SOURCE_FILE_NAME_LENGTH -
        identityPrefix.length -
        alternateSuffix.length -
        extensionSuffix.length
    check(maximumStemLength > 0)
    return identityPrefix + safeStem.take(maximumStemLength) + alternateSuffix + extensionSuffix
}

internal fun legacyMangaArchiveSourceFileName(page: PageRecord): String {
    val extension = page.mediaType.substringAfter('/').let {
        when (it) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            else -> page.storedPath.substringAfterLast('.', "img")
        }
    }
    return page.originalName
        .replace(UNSAFE_ARCHIVE_FILE_NAME_CHARACTER, "_")
        .trim(' ', '.')
        .take(MAX_ARCHIVE_SOURCE_FILE_NAME_LENGTH)
        .ifBlank { "${(page.order + 1).toString().padStart(4, '0')}.$extension" }
}

internal enum class LegacyArchiveSourceAction {
    REUSE_LEGACY,
    WRITE_STABLE,
}

internal fun isReusableArchiveSourceCandidate(
    expectedByteLength: Long,
    expectedSha256: String,
    candidateByteLength: Long,
    candidateSha256: String?,
    candidateAlreadyClaimed: Boolean,
    candidateNameReservedForAnotherPage: Boolean = false,
): Boolean {
    require(expectedByteLength >= 0L)
    require(SHA256.matches(expectedSha256))
    require(candidateByteLength >= -1L)
    require(candidateSha256 == null || SHA256.matches(candidateSha256))
    return !candidateAlreadyClaimed &&
        !candidateNameReservedForAnotherPage &&
        candidateByteLength == expectedByteLength &&
        candidateSha256 == expectedSha256
}

internal fun selectLegacyArchiveSourceAction(
    expectedByteLength: Long,
    expectedSha256: String,
    legacyByteLength: Long,
    legacySha256: String?,
    legacyAlreadyClaimed: Boolean,
    legacyNameReservedForStablePage: Boolean = false,
): LegacyArchiveSourceAction {
    return if (isReusableArchiveSourceCandidate(
            expectedByteLength = expectedByteLength,
            expectedSha256 = expectedSha256,
            candidateByteLength = legacyByteLength,
            candidateSha256 = legacySha256,
            candidateAlreadyClaimed = legacyAlreadyClaimed,
            candidateNameReservedForAnotherPage = legacyNameReservedForStablePage,
        )
    ) {
        LegacyArchiveSourceAction.REUSE_LEGACY
    } else {
        LegacyArchiveSourceAction.WRITE_STABLE
    }
}

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

fun cachedMangaLibraryProjects(rootTreeUri: Uri): List<MangaLibraryProject>? =
    MangaLibraryCache.projects(rootTreeUri.toString())

fun cachedMangaLibraryRootName(rootTreeUri: Uri): String? =
    MangaLibraryCache.rootName(rootTreeUri.toString())

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

    fun rootDisplayName(): String = (displayName(rootDocumentUri()) ?: "Manga")
        .also { MangaLibraryCache.putRootName(cacheKey, it) }

    /**
     * One import always creates one independent folder:
     *
     * Manga/漫画名/生肉
     * Manga/漫画名/翻译后
     *
     * Importing the same source again creates 漫画名 (2), which can be renamed
     * later from the library screen. No translation-version folders exist.
     */
    fun ensureProject(
        projectId: String,
        title: String,
        createdAtEpochMillis: Long,
        sourceTreeUri: Uri,
        sourceFingerprint: String = sha256(sourceTreeUri.toString()),
    ): MangaLibraryProject {
        require(SAFE_ID.matches(projectId))
        require(SHA256.matches(sourceFingerprint))
        refreshProjects().firstOrNull { it.metadata.projectId == projectId }?.let { existing ->
            val source = existing.sourceDirectoryUri
                ?: ensureDirectory(existing.directoryUri, SOURCE_DIRECTORY_NAME)
            val output = existing.outputDirectoryUri
                ?: ensureDirectory(existing.directoryUri, OUTPUT_DIRECTORY_NAME)
            return existing.copy(
                sourceDirectoryUri = source,
                outputDirectoryUri = output,
                outputPageCount = outputPages(output).size,
            )
        }

        val occupiedNames = children(rootDocumentUri())
            .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
            .mapTo(mutableSetOf(), DocumentRef::displayName)
        val directoryName = uniqueDirectoryName(
            safeDirectoryName(title.trim().ifBlank { "未命名漫画" }),
            occupiedNames,
        )
        val directory = ensureDirectory(rootDocumentUri(), directoryName)
        val source = ensureDirectory(directory, SOURCE_DIRECTORY_NAME)
        val output = ensureDirectory(directory, OUTPUT_DIRECTORY_NAME)
        val metadata = MangaLibraryProjectMetadata(
            projectId = projectId,
            title = directoryName,
            createdAtEpochMillis = createdAtEpochMillis,
            sourceTreeUri = sourceTreeUri.toString(),
            sourceFingerprint = sourceFingerprint,
        )
        writeProjectMetadata(directory, metadata)
        MangaLibraryCache.invalidate(cacheKey)
        return MangaLibraryProject(
            metadata = metadata,
            directoryUri = directory,
            mangaDirectoryUri = directory,
            sourceDirectoryUri = source,
            outputDirectoryUri = output,
            outputPageCount = 0,
        )
    }

    fun archiveSourcePages(
        projectId: String,
        privateProjectDirectory: Path,
        pages: List<PageRecord>,
    ) {
        require(SAFE_ID.matches(projectId))
        val project = requireNotNull(project(projectId)) { "library project was not found" }
        val sourceDirectory = project.sourceDirectoryUri
            ?: ensureDirectory(project.directoryUri, SOURCE_DIRECTORY_NAME)
        val sortedPages = pages.sortedBy(PageRecord::order)
        val reservedStableNames = sortedPages.mapTo(mutableSetOf(), ::mangaArchiveSourceFileName)
        val existing = children(sourceDirectory)
            .groupByTo(mutableMapOf(), DocumentRef::displayName)
            .mapValuesTo(mutableMapOf()) { (_, documents) -> documents.toMutableList() }
        val claimedDocuments = mutableSetOf<Uri>()
        val documentDigests = mutableMapOf<Uri, String?>()

        fun digest(candidate: DocumentRef): String? {
            if (documentDigests.containsKey(candidate.uri)) return documentDigests[candidate.uri]
            return runCatching { documentSha256(candidate.uri) }.getOrNull().also {
                documentDigests[candidate.uri] = it
            }
        }

        fun reusableCandidate(
            page: PageRecord,
            candidate: DocumentRef,
            nameReservedForAnotherPage: Boolean,
        ): Boolean {
            val candidateDigest = if (
                candidate.byteLength == page.byteLength &&
                candidate.uri !in claimedDocuments &&
                !nameReservedForAnotherPage
            ) {
                digest(candidate)
            } else {
                null
            }
            return isReusableArchiveSourceCandidate(
                expectedByteLength = page.byteLength,
                expectedSha256 = page.sourceSha256,
                candidateByteLength = candidate.byteLength,
                candidateSha256 = candidateDigest,
                candidateAlreadyClaimed = candidate.uri in claimedDocuments,
                candidateNameReservedForAnotherPage = nameReservedForAnotherPage,
            )
        }

        fun reuseMatching(
            page: PageRecord,
            name: String,
            nameReservedForAnotherPage: Boolean,
        ): Boolean {
            val reusable = existing[name]
                .orEmpty()
                .firstOrNull { reusableCandidate(page, it, nameReservedForAnotherPage) }
                ?: return false
            claimedDocuments += reusable.uri
            return true
        }

        sortedPages.forEach { page ->
            val source = resolveInside(privateProjectDirectory, page.storedPath)
            require(Files.isRegularFile(source) && Files.size(source) == page.byteLength)
            val primaryStableName = mangaArchiveSourceFileName(page)
            if (reuseMatching(page, primaryStableName, nameReservedForAnotherPage = false)) {
                return@forEach
            }

            val legacyName = legacyMangaArchiveSourceFileName(page)
            if (
                legacyName !in reservedStableNames &&
                reuseMatching(page, legacyName, nameReservedForAnotherPage = false)
            ) {
                return@forEach
            }

            var outputName = primaryStableName
            var alternateOrdinal = 2
            while (
                existing[outputName].orEmpty().isNotEmpty() ||
                (outputName != primaryStableName && outputName in reservedStableNames)
            ) {
                outputName = mangaArchiveAlternativeSourceFileName(page, alternateOrdinal)
                check(alternateOrdinal < Int.MAX_VALUE) { "source archive name space exhausted" }
                alternateOrdinal += 1
                if (
                    outputName !in reservedStableNames &&
                    reuseMatching(page, outputName, nameReservedForAnotherPage = false)
                ) {
                    return@forEach
                }
            }

            val target = DocumentsContract.createDocument(
                resolver,
                sourceDirectory,
                page.mediaType,
                outputName,
            ) ?: error("could not create source archive page")
            var created = true
            try {
                Files.newInputStream(source).buffered().use { input ->
                    resolver.openOutputStream(target, "w")?.buffered()?.use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                        output.flush()
                    } ?: error("could not write source archive page")
                }
                require(documentLength(target) == page.byteLength) { "source archive length mismatch" }
                created = false
                val actualName = displayName(target) ?: outputName
                existing.getOrPut(actualName, ::mutableListOf) +=
                    DocumentRef(target, actualName, page.mediaType, page.byteLength)
                claimedDocuments += target
                documentDigests[target] = page.sourceSha256
            } finally {
                if (created) runCatching { DocumentsContract.deleteDocument(resolver, target) }
            }
        }
    }

    fun projects(): List<MangaLibraryProject> =
        MangaLibraryCache.projects(cacheKey) ?: refreshProjects()

    fun refreshProjects(): List<MangaLibraryProject> = children(rootDocumentUri())
        .asSequence()
        .filter {
            it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                !it.displayName.startsWith('.')
        }
        .mapNotNull(::readProject)
        .sortedWith(
            compareByDescending<MangaLibraryProject> { it.metadata.createdAtEpochMillis }
                .thenBy { it.metadata.title.lowercase(Locale.ROOT) },
        )
        .toList()
        .also { MangaLibraryCache.putProjects(cacheKey, it) }

    fun project(projectId: String): MangaLibraryProject? {
        if (!SAFE_ID.matches(projectId)) return null
        return projects().firstOrNull { it.metadata.projectId == projectId }
    }

    fun ensureOutputDirectory(projectId: String): Uri {
        require(SAFE_ID.matches(projectId))
        val project = requireNotNull(refreshProjects().firstOrNull {
            it.metadata.projectId == projectId
        }) { "library project was not found" }
        return project.outputDirectoryUri
            ?: ensureDirectory(project.directoryUri, OUTPUT_DIRECTORY_NAME)
    }

    fun markProjectCompleted(projectId: String, completedAtEpochMillis: Long) {
        require(SAFE_ID.matches(projectId) && completedAtEpochMillis > 0L)
        val project = refreshProjects().firstOrNull { it.metadata.projectId == projectId } ?: return
        writeProjectMetadata(
            project.directoryUri,
            project.metadata.copy(createdAtEpochMillis = completedAtEpochMillis),
        )
        MangaLibraryCache.invalidate(cacheKey)
    }

    fun renameProject(projectId: String, requestedTitle: String): MangaLibraryProject {
        require(SAFE_ID.matches(projectId))
        val project = requireNotNull(refreshProjects().firstOrNull {
            it.metadata.projectId == projectId
        }) { "library project was not found" }
        val title = safeDirectoryName(requestedTitle)
        require(title.isNotBlank())
        val conflicting = children(rootDocumentUri()).any {
            it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                it.uri != project.directoryUri &&
                it.displayName.equals(title, ignoreCase = true)
        }
        require(!conflicting) { "library project name already exists" }
        val renamed = if (displayName(project.directoryUri) == title) {
            project.directoryUri
        } else {
            requireNotNull(DocumentsContract.renameDocument(resolver, project.directoryUri, title)) {
                "library project could not be renamed"
            }
        }
        val actualTitle = displayName(renamed)?.let(::safeDirectoryName) ?: title
        writeProjectMetadata(renamed, project.metadata.copy(title = actualTitle))
        MangaLibraryCache.invalidate(cacheKey)
        return requireNotNull(refreshProjects().firstOrNull { it.metadata.projectId == projectId })
    }

    fun deleteProject(projectId: String): Boolean {
        require(SAFE_ID.matches(projectId))
        val project = refreshProjects().firstOrNull { it.metadata.projectId == projectId } ?: return false
        val deleted = DocumentsContract.deleteDocument(resolver, project.directoryUri)
        if (deleted) MangaLibraryCache.invalidate(cacheKey)
        return deleted
    }

    fun outputPages(projectId: String): List<MangaLibraryPage> =
        project(projectId)?.outputDirectoryUri?.let(::outputPages).orEmpty()

    fun sourcePages(projectId: String): List<MangaLibraryPage> =
        project(projectId)?.sourceDirectoryUri?.let(::sourcePages).orEmpty()

    fun documentDisplayName(uri: Uri): String? = displayName(
        runCatching {
            DocumentsContract.getDocumentId(uri)
            uri
        }.getOrElse {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        },
    )

    private fun readProject(directory: DocumentRef): MangaLibraryProject? = runCatching {
        val entries = children(directory.uri)
        val metadataDocument = entries.singleOrNull {
            it.displayName == PROJECT_METADATA_FILE_NAME &&
                it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
        } ?: return@runCatching null
        val metadata = resolver.openInputStream(metadataDocument.uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { decodeProjectMetadata(it.readText()) }
            ?: return@runCatching null
        validateProjectMetadata(metadata)
        if (metadata.schemaVersion != CURRENT_PROJECT_SCHEMA_VERSION) return@runCatching null
        val actualName = safeDirectoryName(directory.displayName)
        val normalizedMetadata = if (metadata.title == actualName) {
            metadata
        } else {
            metadata.copy(title = actualName)
        }
        val source = entries.singleOrNull {
            it.displayName == SOURCE_DIRECTORY_NAME &&
                it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.uri
        val output = entries.singleOrNull {
            it.displayName == OUTPUT_DIRECTORY_NAME &&
                it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.uri
        MangaLibraryProject(
            metadata = normalizedMetadata,
            directoryUri = directory.uri,
            mangaDirectoryUri = directory.uri,
            sourceDirectoryUri = source,
            outputDirectoryUri = output,
            outputPageCount = output?.let(::outputPages)?.size ?: 0,
        )
    }.getOrNull()

    private fun validateProjectMetadata(metadata: MangaLibraryProjectMetadata) {
        require(
            metadata.schemaVersion == CURRENT_PROJECT_SCHEMA_VERSION &&
                SAFE_ID.matches(metadata.projectId) &&
                metadata.title.isNotBlank() &&
                metadata.createdAtEpochMillis >= 0L &&
                SHA256.matches(metadata.sourceFingerprint),
        )
    }

    private fun outputPages(directoryUri: Uri): List<MangaLibraryPage> {
        val publishedGeneration = children(directoryUri)
            .asSequence()
            .filter {
                it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                    OutputGeneration.isPublishedDirectory(it.displayName)
            }
            .maxByOrNull { it.displayName }
        val visibleDirectory = publishedGeneration?.uri ?: directoryUri
        return children(visibleDirectory)
        .asSequence()
        .filter { it.mimeType.startsWith("image/") && OUTPUT_FILE.matches(it.displayName) }
        .sortedWith(compareBy<DocumentRef> { outputOrder(it.displayName) }.thenBy { it.displayName })
        .map { MangaLibraryPage(it.displayName, it.uri) }
        .toList()
    }

    private fun sourcePages(directoryUri: Uri): List<MangaLibraryPage> = children(directoryUri)
        .asSequence()
        .filter { it.mimeType.startsWith("image/") && SOURCE_FILE.matches(it.displayName) }
        .sortedBy { it.displayName.lowercase(Locale.ROOT) }
        .map { MangaLibraryPage(it.displayName, it.uri) }
        .toList()

    private fun writeProjectMetadata(directoryUri: Uri, metadata: MangaLibraryProjectMetadata) {
        val content = JSONObject()
            .put("schemaVersion", metadata.schemaVersion)
            .put("projectId", metadata.projectId)
            .put("title", metadata.title)
            .put("createdAtEpochMillis", metadata.createdAtEpochMillis)
            .put("sourceTreeUri", metadata.sourceTreeUri)
            .put("sourceFingerprint", metadata.sourceFingerprint)
            .toString()
        val existing = children(directoryUri).singleOrNull {
            it.displayName == PROJECT_METADATA_FILE_NAME
        }
        val metadataUri = existing?.uri ?: DocumentsContract.createDocument(
            resolver,
            directoryUri,
            JSON_MIME_TYPE,
            PROJECT_METADATA_FILE_NAME,
        ) ?: error("could not create library metadata")
        resolver.openOutputStream(metadataUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(content)
        } ?: error("could not write library metadata")
        MangaLibraryCache.invalidate(cacheKey)
    }

    private fun decodeProjectMetadata(content: String): MangaLibraryProjectMetadata {
        val value = JSONObject(content)
        return MangaLibraryProjectMetadata(
            schemaVersion = value.getInt("schemaVersion"),
            projectId = value.getString("projectId"),
            title = value.getString("title"),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            sourceTreeUri = value.getString("sourceTreeUri"),
            sourceFingerprint = value.getString("sourceFingerprint"),
        )
    }

    private fun ensureDirectory(parentUri: Uri, displayName: String): Uri {
        children(parentUri).firstOrNull {
            it.displayName == displayName && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.let { return it.uri }
        return requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                displayName,
            ),
        ).also { MangaLibraryCache.invalidate(cacheKey) }
    }

    private fun children(parentUri: Uri): List<DocumentRef> {
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, parentDocumentId)
        return resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
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
                            byteLength = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn),
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

    private fun documentLength(uri: Uri): Long = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
    } ?: -1L

    private fun documentSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(resolver.openInputStream(uri)) { "source archive stream unavailable" }
        input.buffered().use { stream ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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

    private fun uniqueDirectoryName(base: String, occupied: Set<String>): String {
        val occupiedLower = occupied.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        if (base.lowercase(Locale.ROOT) !in occupiedLower) return base
        var suffix = 2
        while (true) {
            val suffixText = " ($suffix)"
            val candidate = "${base.take(MAX_DIRECTORY_NAME_LENGTH - suffixText.length)}$suffixText"
            if (candidate.lowercase(Locale.ROOT) !in occupiedLower) return candidate
            suffix += 1
        }
    }

    private fun outputOrder(name: String): Long =
        name.substringBeforeLast('.').toLongOrNull() ?: Long.MAX_VALUE

    private fun resolveInside(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/'))
        val normalizedRoot = root.toAbsolutePath().normalize()
        return normalizedRoot.resolve(relative).normalize().also {
            require(it.startsWith(normalizedRoot))
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class DocumentRef(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val byteLength: Long,
    )

    private companion object {
        const val PROJECT_METADATA_FILE_NAME = ".masumi-project.json"
        const val SOURCE_DIRECTORY_NAME = "生肉"
        const val OUTPUT_DIRECTORY_NAME = "翻译后"
        const val JSON_MIME_TYPE = "application/json"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_DIRECTORY_NAME_LENGTH = 80
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val OUTPUT_FILE = Regex("[0-9]{1,12}\\.(png|webp)", RegexOption.IGNORE_CASE)
        val SOURCE_FILE = Regex(".+\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)
        val UNSAFE_DIRECTORY_CHARACTER = Regex("[/\\\\:*?\"<>|]")
        val WHITESPACE = Regex("\\s+")
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

private const val CURRENT_PROJECT_SCHEMA_VERSION = 3
private const val MAX_ARCHIVE_SOURCE_FILE_NAME_LENGTH = 180
private val SHA256 = Regex("[0-9a-f]{64}")
private val UNSAFE_ARCHIVE_FILE_NAME_CHARACTER = Regex("[/\\\\:*?\"<>|]")
