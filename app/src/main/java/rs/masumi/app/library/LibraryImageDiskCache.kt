package rs.masumi.app.library

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
    private val accessSequence = AtomicLong()
    private val directoryLock = directoryLocks.computeIfAbsent(directory.toAbsolutePath().normalize()) { Any() }

    init {
        require(maxBytes > 0L)
        synchronized(directoryLock) {
            trimToBudget()
        }
    }

    fun read(key: String, promoteTo: LibraryImageAssetKind? = null): ByteArray? = synchronized(directoryLock) {
        val entry = entryFor(key) ?: return null
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
        val storedKind = readMetadata(entry.metadata)?.kind ?: LibraryImageAssetKind.COVER
        val touchedKind = promoteTo?.takeIf { it.evictionRank < storedKind.evictionRank } ?: storedKind
        val previousMetadataBytes = Files.size(entry.metadata)
        writeMetadata(entry, touchedKind)
        if (Files.size(entry.metadata) > previousMetadataBytes) trimToBudget()
        return bytes
    }

    fun write(key: String, kind: LibraryImageAssetKind, bytes: ByteArray) {
        synchronized(directoryLock) {
            require(bytes.isNotEmpty() && bytes.size.toLong() <= maximumEntryBytes()) {
                "one image cache entry exceeds the per-entry budget"
            }
            Files.createDirectories(directory)
            val entry = entryFor(key) ?: return
            writeAtomically(entry.asset, bytes)
            writeMetadata(entry, kind)
            trimToBudget()
        }
    }

    fun sizeBytes(): Long = synchronized(directoryLock) {
        cleanupOwnedTemporaryFiles()
        entries().sumOf(::entrySizeBytes)
    }

    private fun trimToBudget() {
        cleanupOwnedTemporaryFiles()
        val currentEntries = entries()
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

    private fun entries(): List<Entry> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().endsWith(ASSET_SUFFIX) }
                .mapNotNull { asset ->
                    val stem = asset.fileName.toString().removeSuffix(ASSET_SUFFIX)
                    val metadata = directory.resolve("$stem$METADATA_SUFFIX")
                    val value = readMetadata(metadata)
                    if (value == null) {
                        runCatching { Files.deleteIfExists(asset) }
                        runCatching { Files.deleteIfExists(metadata) }
                        null
                    } else {
                        Entry(asset, metadata, value)
                    }
                }
                .toList()
        }
    }

    private fun entryFor(key: String): Entry? {
        if (key.isBlank()) return null
        val stem = sha256(key)
        val asset = directory.resolve("$stem$ASSET_SUFFIX")
        val metadata = directory.resolve("$stem$METADATA_SUFFIX")
        return Entry(asset, metadata, readMetadata(metadata) ?: Metadata(LibraryImageAssetKind.COVER, 0L))
    }

    private fun readMetadata(path: Path): Metadata? = runCatching {
        val parts = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { it.readText() }.trim().split('|')
        when {
            parts.size == 3 && parts[0] == METADATA_VERSION ->
                Metadata(LibraryImageAssetKind.valueOf(parts[1]), parts[2].toLong())
            // v1 used System.nanoTime(), whose epoch changes after reboot. Keep
            // the asset readable, but rank the legacy timestamp as unknown so
            // a subsequent read can migrate it into the wall-clock domain.
            parts.size == 2 -> Metadata(LibraryImageAssetKind.valueOf(parts[0]), 0L)
            else -> error("unsupported cache metadata")
        }
    }.getOrNull()

    private fun writeMetadata(entry: Entry, kind: LibraryImageAssetKind) {
        val nextAccess = accessSequence.updateAndGet { previous ->
            maxOf(previous + 1L, System.currentTimeMillis())
        }
        writeAtomically(
            entry.metadata,
            "$METADATA_VERSION|${kind.name}|$nextAccess".toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun delete(entry: Entry) {
        runCatching { Files.deleteIfExists(entry.asset) }
        runCatching { Files.deleteIfExists(entry.metadata) }
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class Metadata(
        val kind: LibraryImageAssetKind,
        val lastAccess: Long,
    ) {
        val evictionRank: Int get() = kind.evictionRank
    }

    private data class Entry(
        val asset: Path,
        val metadata: Path,
        val metadataValue: Metadata,
    )

    companion object {
        const val MAX_BYTES = 256L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 24L * 1024L * 1024L
        const val ASSET_SUFFIX = ".asset"
        const val METADATA_SUFFIX = ".meta"
        private const val METADATA_VERSION = "v2"
        private val OWNED_TEMP_FILE = Regex("[0-9a-f]{64}(?:\\.asset|\\.meta).+\\.tmp")
        private val directoryLocks = ConcurrentHashMap<Path, Any>()
    }
}
