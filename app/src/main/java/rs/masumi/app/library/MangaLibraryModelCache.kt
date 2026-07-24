package rs.masumi.app.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PersistentModelFile(
    val fileName: String,
    val byteLength: Long,
    val sha256: String,
)

data class PersistentModelPackage(
    val cacheKey: String,
    val version: String,
    val files: List<PersistentModelFile>,
)

class MangaLibraryModelCache(
    private val resolver: ContentResolver,
    private val rootTreeUri: Uri,
) {
    init {
        require(
            rootTreeUri.scheme == ContentResolver.SCHEME_CONTENT &&
                DocumentsContract.isTreeUri(rootTreeUri),
        )
    }

    fun restore(
        modelPackage: PersistentModelPackage,
        targetDirectory: Path,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        validatePackage(modelPackage)
        val packageDirectory = findPackageDirectory(modelPackage) ?: return false
        val documents = children(packageDirectory).groupBy(DocumentRef::displayName)
        val manifestDocument = documents[MANIFEST_FILE_NAME]?.singleOrNull() ?: return false
        val manifest = readManifest(manifestDocument) ?: return false
        if (!manifest.matches(modelPackage)) return false
        val manifestFiles = manifest.files.associateBy(ManifestFile::fileName)
        val requiredNames = modelPackage.files.map(PersistentModelFile::fileName) + PACKAGE_METADATA_FILE_NAME
        val selectedDocuments = requiredNames.associateWith { name ->
            documents[name]?.singleOrNull() ?: return false
        }
        val total = requiredNames.sumOf { manifestFiles.getValue(it).byteLength }
        onProgress(0L, total)

        val parent = requireNotNull(targetDirectory.toAbsolutePath().normalize().parent)
        val staging = parent.resolve(".library-restore-${UUID.randomUUID()}").normalize()
        if (staging.parent != parent) return false
        return try {
            staging.toFile().deleteRecursively()
            Files.createDirectories(staging)
            var completed = 0L
            requiredNames.forEach { name ->
                val entry = manifestFiles.getValue(name)
                val target = staging.resolve(name).normalize()
                require(target.parent == staging)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                resolver.openInputStream(selectedDocuments.getValue(name).uri)?.use { source ->
                    BufferedInputStream(source).use { input ->
                        BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                copied += read
                                onProgress(completed + copied, total)
                            }
                        }
                    }
                } ?: return false
                require(copied == entry.byteLength)
                require(digest.digest().toHex() == entry.sha256)
                completed += copied
            }
            targetDirectory.toFile().deleteRecursively()
            Files.createDirectories(parent)
            runCatching {
                Files.move(staging, targetDirectory, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(staging, targetDirectory)
            }
            onProgress(total, total)
            true
        } catch (_: Throwable) {
            false
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    fun backup(
        modelPackage: PersistentModelPackage,
        sourceDirectory: Path,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        validatePackage(modelPackage)
        val normalizedSource = sourceDirectory.toAbsolutePath().normalize()
        val sourceFiles = modelPackage.files.associate { expected ->
            val path = normalizedSource.resolve(expected.fileName).normalize()
            require(path.parent == normalizedSource && Files.isRegularFile(path))
            require(Files.size(path) == expected.byteLength)
            expected.fileName to path
        }.toMutableMap()
        val metadataPath = normalizedSource.resolve(PACKAGE_METADATA_FILE_NAME).normalize()
        require(metadataPath.parent == normalizedSource && Files.isRegularFile(metadataPath))
        require(Files.size(metadataPath) in 1..MAX_METADATA_BYTES)
        sourceFiles[PACKAGE_METADATA_FILE_NAME] = metadataPath

        val manifestFiles = buildList {
            addAll(modelPackage.files.map { ManifestFile(it.fileName, it.byteLength, it.sha256) })
            add(
                ManifestFile(
                    PACKAGE_METADATA_FILE_NAME,
                    Files.size(metadataPath),
                    sha256(metadataPath),
                ),
            )
        }
        val manifest = ModelManifest(modelPackage.cacheKey, modelPackage.version, manifestFiles)
        val total = manifestFiles.sumOf(ManifestFile::byteLength)
        val packageDirectory = ensurePackageDirectory(modelPackage)
        val existingDocuments = children(packageDirectory)
        val existingManifest = existingDocuments
            .singleOrNull { it.displayName == MANIFEST_FILE_NAME }
            ?.let(::readManifest)
        val completeExternalFiles = manifestFiles.all { expected ->
            existingDocuments.singleOrNull { it.displayName == expected.fileName }
                ?.byteLength == expected.byteLength
        }
        if (existingManifest == manifest && completeExternalFiles) {
            onProgress(total, total)
            return true
        }

        return try {
            children(packageDirectory)
                .filter { it.displayName == MANIFEST_FILE_NAME }
                .forEach { DocumentsContract.deleteDocument(resolver, it.uri) }
            var completed = 0L
            manifestFiles.forEach { entry ->
                val target = replaceableDocument(packageDirectory, entry.fileName, BINARY_MIME_TYPE)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                Files.newInputStream(sourceFiles.getValue(entry.fileName)).use { source ->
                    BufferedInputStream(source).use { input ->
                        resolver.openOutputStream(target, "w")?.use { rawOutput ->
                            BufferedOutputStream(rawOutput).use { output ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    copied += read
                                    onProgress(completed + copied, total)
                                }
                            }
                        } ?: error("model cache output could not be opened")
                    }
                }
                require(copied == entry.byteLength)
                require(digest.digest().toHex() == entry.sha256)
                completed += copied
            }
            val manifestUri = replaceableDocument(
                packageDirectory,
                MANIFEST_FILE_NAME,
                JSON_MIME_TYPE,
            )
            resolver.openOutputStream(manifestUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(encodeManifest(manifest))
            } ?: error("model cache manifest could not be written")
            onProgress(total, total)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findPackageDirectory(modelPackage: PersistentModelPackage): Uri? {
        val data = childDirectory(rootDocumentUri(), DATA_DIRECTORY_NAME) ?: return null
        val models = childDirectory(data, MODELS_DIRECTORY_NAME) ?: return null
        val family = childDirectory(models, modelPackage.cacheKey) ?: return null
        return childDirectory(family, modelPackage.version)
    }

    private fun ensurePackageDirectory(modelPackage: PersistentModelPackage): Uri {
        val data = ensureDirectory(rootDocumentUri(), DATA_DIRECTORY_NAME)
        val models = ensureDirectory(data, MODELS_DIRECTORY_NAME)
        val family = ensureDirectory(models, modelPackage.cacheKey)
        return ensureDirectory(family, modelPackage.version)
    }

    private fun childDirectory(parent: Uri, name: String): Uri? = children(parent)
        .singleOrNull {
            it.displayName == name && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }
        ?.uri

    private fun ensureDirectory(parent: Uri, name: String): Uri =
        childDirectory(parent, name) ?: requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ),
        )

    private fun replaceableDocument(parent: Uri, name: String, mimeType: String): Uri {
        val exact = children(parent).filter { it.displayName == name }
        exact.drop(1).forEach { DocumentsContract.deleteDocument(resolver, it.uri) }
        return exact.firstOrNull()?.uri ?: requireNotNull(
            DocumentsContract.createDocument(resolver, parent, mimeType, name),
        )
    }

    private fun children(parent: Uri): List<DocumentRef> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, parentId)
        return resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn)
                    add(
                        DocumentRef(
                            DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, id),
                            cursor.getString(nameColumn) ?: continue,
                            cursor.getString(typeColumn) ?: BINARY_MIME_TYPE,
                            if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun readManifest(document: DocumentRef): ModelManifest? = runCatching {
        require(document.byteLength == null || document.byteLength in 1..MAX_METADATA_BYTES)
        val text = resolver.openInputStream(document.uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: return@runCatching null
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        decodeManifest(text)
    }.getOrNull()

    private fun encodeManifest(manifest: ModelManifest): String = JSONObject()
        .put("schemaVersion", MANIFEST_SCHEMA_VERSION)
        .put("cacheKey", manifest.cacheKey)
        .put("version", manifest.version)
        .put(
            "files",
            JSONArray().apply {
                manifest.files.forEach { file ->
                    put(
                        JSONObject()
                            .put("fileName", file.fileName)
                            .put("byteLength", file.byteLength)
                            .put("sha256", file.sha256),
                    )
                }
            },
        )
        .toString()

    private fun decodeManifest(content: String): ModelManifest {
        val value = JSONObject(content)
        require(value.getInt("schemaVersion") == MANIFEST_SCHEMA_VERSION)
        val filesJson = value.getJSONArray("files")
        val files = List(filesJson.length()) { index ->
            val file = filesJson.getJSONObject(index)
            ManifestFile(
                fileName = file.getString("fileName"),
                byteLength = file.getLong("byteLength"),
                sha256 = file.getString("sha256"),
            )
        }
        return ModelManifest(
            cacheKey = value.getString("cacheKey"),
            version = value.getString("version"),
            files = files,
        ).also { manifest ->
            require(manifest.files.map(ManifestFile::fileName).distinct().size == manifest.files.size)
            manifest.files.forEach {
                require(SAFE_FILE_NAME.matches(it.fileName))
                require(it.byteLength > 0L && SHA256.matches(it.sha256))
            }
        }
    }

    private fun ModelManifest.matches(modelPackage: PersistentModelPackage): Boolean {
        if (cacheKey != modelPackage.cacheKey || version != modelPackage.version) return false
        val actual = files.associateBy(ManifestFile::fileName)
        if (actual.keys != modelPackage.files.map(PersistentModelFile::fileName).toSet() +
            PACKAGE_METADATA_FILE_NAME
        ) {
            return false
        }
        return modelPackage.files.all { expected ->
            actual[expected.fileName] == ManifestFile(
                expected.fileName,
                expected.byteLength,
                expected.sha256,
            )
        }
    }

    private fun validatePackage(modelPackage: PersistentModelPackage) {
        require(SAFE_DIRECTORY_NAME.matches(modelPackage.cacheKey))
        require(SAFE_VERSION.matches(modelPackage.version))
        require(modelPackage.files.isNotEmpty())
        require(modelPackage.files.map(PersistentModelFile::fileName).distinct().size == modelPackage.files.size)
        modelPackage.files.forEach {
            require(SAFE_FILE_NAME.matches(it.fileName))
            require(it.fileName != PACKAGE_METADATA_FILE_NAME && it.fileName != MANIFEST_FILE_NAME)
            require(it.byteLength > 0L && SHA256.matches(it.sha256))
        }
    }

    private fun rootDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        rootTreeUri,
        DocumentsContract.getTreeDocumentId(rootTreeUri),
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class DocumentRef(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val byteLength: Long?,
    )

    private data class ManifestFile(
        val fileName: String,
        val byteLength: Long,
        val sha256: String,
    )

    private data class ModelManifest(
        val cacheKey: String,
        val version: String,
        val files: List<ManifestFile>,
    )

    private companion object {
        const val DATA_DIRECTORY_NAME = "Masumi数据"
        const val MODELS_DIRECTORY_NAME = "模型"
        const val MANIFEST_FILE_NAME = "模型信息.json"
        const val PACKAGE_METADATA_FILE_NAME = "package.json"
        const val MANIFEST_SCHEMA_VERSION = 1
        const val MAX_METADATA_BYTES = 1_048_576L
        const val COPY_BUFFER_SIZE = 256 * 1024
        const val BINARY_MIME_TYPE = "application/octet-stream"
        const val JSON_MIME_TYPE = "application/json"
        val SAFE_DIRECTORY_NAME = Regex("[\\p{L}\\p{N} ._-]{1,80}")
        val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
