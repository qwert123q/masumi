package rs.masumi.app.library

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A small encoded-image cache shared by shelf covers and reader pages.  Decoded
 * bitmaps never enter this cache, so its disk budget does not become a bitmap
 * memory-retention budget.
 */
enum class LibraryImageAssetKind(internal val evictionRank: Int) {
    FIRST_VIEWPORT_COVER(0),
    READER_PAGE(1),
    COVER(2),
}

class LibraryImageDiskCache(
    private val directory: Path,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val sharedState = directoryStates.computeIfAbsent(directory.toAbsolutePath().normalize()) {
        DirectoryState()
    }
    private val accessSequence: AtomicLong get() = sharedState.accessSequence
    private val directoryLock: Any get() = sharedState.lock
    private val entriesByKey: MutableMap<String, Entry> get() = sharedState.entriesByKey

    init {
        require(maxBytes > 0L)
        synchronized(directoryLock) {
            loadEntries()
            trimToBudget()
        }
    }

    fun read(key: String, promoteTo: LibraryImageAssetKind? = null): ByteArray? = synchronized(directoryLock) {
        if (key.isBlank()) return null
        val entry = entriesByKey[key] ?: return null
        if (!Files.isRegularFile(entry.asset) || !Files.isRegularFile(entry.metadata)) {
            delete(entry)
            return null
        }
        val byteLength = runCatching { Files.size(entry.asset) }.getOrNull()
        if (byteLength == null || byteLength <= 0L || byteLength > maximumEntryBytes()) {
            delete(entry)
            return null
        }
        val bytes = runCatching { Files.readAllBytes(entry.asset) }.getOrNull() ?: run {
            delete(entry)
            return null
        }
        val stored = readMetadata(entry.metadata)?.takeIf { it.key == key } ?: run {
            delete(entry)
            return null
        }
        val storedKind = stored.kind
        val touchedKind = promoteTo?.takeIf { it.evictionRank < storedKind.evictionRank } ?: storedKind
        val previousMetadataBytes = Files.size(entry.metadata)
        writeMetadata(entry, key, touchedKind)
        if (Files.size(entry.metadata) > previousMetadataBytes) trimToBudget()
        return bytes
    }

    fun write(key: String, kind: LibraryImageAssetKind, bytes: ByteArray) {
        synchronized(directoryLock) {
            require(bytes.isNotEmpty() && bytes.size.toLong() <= maximumEntryBytes()) {
                "one image cache entry exceeds the per-entry budget"
            }
            if (key.isBlank()) return
            Files.createDirectories(directory)
            val entry = entriesByKey[key] ?: newEntry(key, kind)
            writeAtomically(entry.asset, bytes)
            writeMetadata(entry, key, kind)
            entriesByKey[key] = entry
            trimToBudget()
        }
    }

    fun sizeBytes(): Long = synchronized(directoryLock) {
        cleanupOwnedTemporaryFiles()
        currentEntries().sumOf(::entrySizeBytes)
    }

    private fun trimToBudget() {
        cleanupOwnedTemporaryFiles()
        val currentEntries = currentEntries()
        var total = currentEntries.sumOf(::entrySizeBytes)
        if (total <= maxBytes) return
        currentEntries
            .sortedWith(
                compareByDescending<Entry> { it.metadataValue.evictionRank }
                    .thenBy { it.metadataValue.lastAccess },
            )
            .forEach { entry ->
                if (total <= maxBytes) return@forEach
                total -= entrySizeBytes(entry)
                delete(entry)
            }
    }

    private fun entrySizeBytes(entry: Entry): Long = Files.size(entry.asset) + Files.size(entry.metadata)

    private fun maximumEntryBytes(): Long = minOf(maxBytes, MAX_ENTRY_BYTES)

    private fun loadEntries() {
        entriesByKey.clear()
        if (!Files.isDirectory(directory)) return
        val knownStems = mutableSetOf<String>()
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().endsWith(ASSET_SUFFIX) }
                .forEach { asset ->
                    val stem = asset.fileName.toString().removeSuffix(ASSET_SUFFIX)
                    val metadataPath = directory.resolve("$stem$METADATA_SUFFIX")
                    val value = readMetadata(metadataPath)
                    if (value == null || value.key.isBlank()) {
                        deleteFiles(asset, metadataPath)
                        return@forEach
                    }
                    knownStems += stem
                    val entry = Entry(asset, metadataPath, value)
                    val previous = entriesByKey[value.key]
                    if (previous == null || previous.metadataValue.lastAccess < value.lastAccess) {
                        if (previous != null) deleteFiles(previous.asset, previous.metadata)
                        entriesByKey[value.key] = entry
                    } else {
                        deleteFiles(entry.asset, entry.metadata)
                    }
                }
        }
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().endsWith(METADATA_SUFFIX) }
                .filter { path -> path.fileName.toString().removeSuffix(METADATA_SUFFIX) !in knownStems }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
        accessSequence.set(entriesByKey.values.maxOfOrNull { it.metadataValue.lastAccess } ?: 0L)
    }

    private fun currentEntries(): List<Entry> = entriesByKey.values.toList().filter { entry ->
        val complete = Files.isRegularFile(entry.asset) && Files.isRegularFile(entry.metadata)
        if (!complete) delete(entry)
        complete
    }

    private fun newEntry(key: String, kind: LibraryImageAssetKind): Entry {
        while (true) {
            val stem = UUID.randomUUID().toString()
            val asset = directory.resolve("$stem$ASSET_SUFFIX")
            val metadata = directory.resolve("$stem$METADATA_SUFFIX")
            if (Files.exists(asset) || Files.exists(metadata)) continue
            return Entry(asset, metadata, Metadata(key, kind, 0L))
        }
    }

    private fun readMetadata(path: Path): Metadata? = runCatching {
        val parts = Files.newBufferedReader(path, StandardCharsets.UTF_8)
            .use { it.readText() }
            .trim()
            .split('|', limit = 4)
        require(parts.size == 4 && parts[0] == METADATA_VERSION)
        Metadata(
            key = String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8),
            kind = LibraryImageAssetKind.valueOf(parts[1]),
            lastAccess = parts[2].toLong(),
        )
    }.getOrNull()

    private fun writeMetadata(entry: Entry, key: String, kind: LibraryImageAssetKind) {
        val nextAccess = accessSequence.updateAndGet { previous ->
            maxOf(previous + 1L, System.currentTimeMillis())
        }
        val encodedKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.toByteArray(StandardCharsets.UTF_8))
        writeAtomically(
            entry.metadata,
            "$METADATA_VERSION|${kind.name}|$nextAccess|$encodedKey".toByteArray(StandardCharsets.UTF_8),
        )
        entry.metadataValue = Metadata(key, kind, nextAccess)
    }

    private fun delete(entry: Entry) {
        deleteFiles(entry.asset, entry.metadata)
        if (entriesByKey[entry.metadataValue.key] === entry) {
            entriesByKey.remove(entry.metadataValue.key)
        }
    }

    private fun deleteFiles(asset: Path, metadata: Path) {
        runCatching { Files.deleteIfExists(asset) }
        runCatching { Files.deleteIfExists(metadata) }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun cleanupOwnedTemporaryFiles() {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filter { path -> OWNED_TEMP_FILE.matches(path.fileName.toString()) }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private data class Metadata(
        val key: String,
        val kind: LibraryImageAssetKind,
        val lastAccess: Long,
    ) {
        val evictionRank: Int get() = kind.evictionRank
    }

    private data class Entry(
        val asset: Path,
        val metadata: Path,
        var metadataValue: Metadata,
    )

    private class DirectoryState {
        val accessSequence = AtomicLong()
        val lock = Any()
        val entriesByKey = mutableMapOf<String, Entry>()
    }

    companion object {
        const val MAX_BYTES = 256L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 24L * 1024L * 1024L
        const val ASSET_SUFFIX = ".asset"
        const val METADATA_SUFFIX = ".meta"
        private const val METADATA_VERSION = "v3"
        private val OWNED_TEMP_FILE = Regex("[A-Za-z0-9._-]+(?:\\.asset|\\.meta).+\\.tmp")
        private val directoryStates = ConcurrentHashMap<Path, DirectoryState>()
    }
}
