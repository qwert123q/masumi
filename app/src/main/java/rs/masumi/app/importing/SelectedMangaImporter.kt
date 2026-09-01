package rs.masumi.app.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import rs.masumi.app.AndroidDocumentSource
import rs.masumi.app.library.MangaLibraryStore
import rs.masumi.app.library.mangaSourceFingerprint
import rs.masumi.core.importer.ImportOutcome
import rs.masumi.core.importer.PageMediaType
import rs.masumi.core.importer.ProjectImporter
import rs.masumi.core.importer.SourceCandidate
import rs.masumi.core.importer.ZipArchiveSource

data class SelectedMangaDocument(
    val uri: Uri,
    val displayName: String,
    val mediaType: String?,
    val byteLength: Long?,
)

data class ImportedManga(
    val title: String,
    val outcome: ImportOutcome,
)

data class SelectedMangaImportResult(
    val imported: List<ImportedManga>,
    val failedNames: List<String>,
    val unsupportedCount: Int,
)

class SelectedMangaDocumentReader(
    private val resolver: ContentResolver,
) {
    fun read(uris: List<Uri>): List<SelectedMangaDocument> = uris.distinct().mapNotNull { uri ->
        runCatching { read(uri) }.getOrNull()
    }

    private fun read(uri: Uri): SelectedMangaDocument? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        var displayName: String? = null
        var byteLength: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) byteLength = cursor.getLong(sizeColumn)
            }
        }
        val safeName = displayName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: return null
        return SelectedMangaDocument(
            uri = uri,
            displayName = safeName,
            mediaType = resolver.getType(uri),
            byteLength = byteLength?.takeIf { it >= 0L },
        )
    }
}

class SelectedMangaImporter(
    private val resolver: ContentResolver,
    private val workspaceRoot: Path,
    private val cacheDirectory: Path,
    private val libraryRoot: Uri,
) {
    fun import(
        documents: List<SelectedMangaDocument>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): SelectedMangaImportResult {
        val archives = documents.filter(::isZipArchive)
        val looseImages = documents.filterNot(::isZipArchive).filter(::isSupportedImage)
        val unsupportedCount = documents.size - archives.size - looseImages.size
        val total = archives.size + if (looseImages.isEmpty()) 0 else 1
        val imported = mutableListOf<ImportedManga>()
        val failedNames = mutableListOf<String>()
        var completed = 0

        archives.forEach { archive ->
            runCatching { importArchive(archive) }
                .onSuccess(imported::add)
                .onFailure { failedNames += archive.displayName }
            completed += 1
            onProgress(completed, total)
        }
        if (looseImages.isNotEmpty()) {
            val title = looseImageTitle(looseImages)
            runCatching {
                importSources(
                    title = title,
                    sourceUri = looseImages.first().uri,
                    sources = looseImages.map(::documentSource),
                )
            }
                .onSuccess(imported::add)
                .onFailure { failedNames += title }
            completed += 1
            onProgress(completed, total)
        }

        return SelectedMangaImportResult(
            imported = imported,
            failedNames = failedNames,
            unsupportedCount = unsupportedCount,
        )
    }

    private fun importArchive(document: SelectedMangaDocument): ImportedManga {
        Files.createDirectories(cacheDirectory)
        val archivePath = Files.createTempFile(cacheDirectory, "selected-manga-", ".zip")
        try {
            copyArchive(document, archivePath)
            return ZipArchiveSource.open(archivePath).use { archive ->
                importSources(
                    title = document.displayName.substringBeforeLast('.').trim().ifBlank { "未命名漫画" },
                    sourceUri = document.uri,
                    sources = archive.sources,
                )
            }
        } finally {
            Files.deleteIfExists(archivePath)
        }
    }

    private fun importSources(
        title: String,
        sourceUri: Uri,
        sources: List<SourceCandidate>,
    ): ImportedManga {
        val outcome = ProjectImporter(workspaceRoot).importProject(sources)
        val library = MangaLibraryStore(resolver, libraryRoot)
        try {
            library.ensureProject(
                projectId = outcome.manifest.projectId,
                title = title,
                createdAtEpochMillis = outcome.manifest.createdAtEpochMillis,
                sourceTreeUri = sourceUri,
                sourceFingerprint = mangaSourceFingerprint(outcome.manifest.pages),
            )
            library.archiveSourcePages(
                projectId = outcome.manifest.projectId,
                privateProjectDirectory = outcome.projectDirectory,
                pages = outcome.manifest.pages,
            )
        } catch (failure: Throwable) {
            runCatching { library.deleteProject(outcome.manifest.projectId) }
            runCatching { outcome.projectDirectory.toFile().deleteRecursively() }
            throw failure
        }
        return ImportedManga(title, outcome)
    }

    private fun copyArchive(document: SelectedMangaDocument, target: Path) {
        if ((document.byteLength ?: 0L) > MAXIMUM_ARCHIVE_BYTES) {
            throw IOException("Archive exceeds the size limit")
        }
        val input = requireNotNull(resolver.openInputStream(document.uri)) { "Archive stream unavailable" }
        BufferedInputStream(input).use { source ->
            BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    if (copied > MAXIMUM_ARCHIVE_BYTES) throw IOException("Archive exceeds the size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun documentSource(document: SelectedMangaDocument): AndroidDocumentSource =
        AndroidDocumentSource(
            resolver = resolver,
            uri = document.uri,
            displayName = document.displayName,
            mediaType = document.mediaType,
            isDirectory = false,
        )

    private fun looseImageTitle(documents: List<SelectedMangaDocument>): String {
        documents.mapNotNull(::documentParentName).distinct().singleOrNull()?.let { return it }
        if (documents.size == 1) {
            return documents.single().displayName.substringBeforeLast('.').trim().ifBlank { "未命名漫画" }
        }
        val stems = documents.map { it.displayName.substringBeforeLast('.') }
        val commonPrefix = stems.drop(1).fold(stems.first()) { prefix, value ->
            prefix.commonPrefixWith(value, ignoreCase = true)
        }.trimEnd { it.isDigit() || it.isWhitespace() || it in "_-.()[]" }
        return commonPrefix.takeIf { it.length >= MINIMUM_COMMON_TITLE_LENGTH } ?: "图片漫画"
    }

    private fun documentParentName(document: SelectedMangaDocument): String? = runCatching {
        val documentId = DocumentsContract.getDocumentId(document.uri)
        val providerPath = documentId.substringAfter(':', documentId)
        if (!providerPath.contains('/')) return@runCatching null
        providerPath.substringBeforeLast('/').substringAfterLast('/').trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun isSupportedImage(document: SelectedMangaDocument): Boolean =
        PageMediaType.detect(document.displayName, document.mediaType) != null

    private fun isZipArchive(document: SelectedMangaDocument): Boolean {
        val extension = document.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mediaType = document.mediaType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return extension == "zip" ||
            extension == "cbz" ||
            mediaType in ZIP_MEDIA_TYPES
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAXIMUM_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        const val MINIMUM_COMMON_TITLE_LENGTH = 2
        val ZIP_MEDIA_TYPES = setOf(
            "application/zip",
            "application/x-zip",
            "application/x-zip-compressed",
            "application/vnd.comicbook+zip",
            "application/x-cbz",
        )
    }
}
