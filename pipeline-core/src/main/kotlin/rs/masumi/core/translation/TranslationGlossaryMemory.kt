package rs.masumi.core.translation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists established source-to-translation pairs across chapters so names,
 * honorifics, and recurring terms stay consistent for a whole series instead
 * of restarting from an empty glossary on every project.
 */
interface TranslationGlossaryMemory {
    fun load(): List<TranslationGlossaryEntry>

    fun record(entries: List<TranslationGlossaryEntry>)
}

class WorkspaceGlossaryStore(
    workspaceRoot: Path,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) : TranslationGlossaryMemory {
    private val file = workspaceRoot.toAbsolutePath().normalize().resolve(FILE_NAME)
    private val lockFile = file.resolveSibling("$FILE_NAME.lock")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TranslationGlossaryEntry.serializer())

    init {
        require(maximumEntries > 0)
    }

    override fun load(): List<TranslationGlossaryEntry> {
        val raw = runCatching { Files.readString(file) }.getOrNull() ?: return emptyList()
        val entries = runCatching { json.decodeFromString(serializer, raw) }.getOrNull() ?: return emptyList()
        return entries
            .filter { it.source.isNotBlank() && it.translation.isNotBlank() }
            .distinctBy(TranslationGlossaryEntry::source)
            .take(maximumEntries)
            .sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
    }

    override fun record(entries: List<TranslationGlossaryEntry>) {
        if (entries.isEmpty()) return
        val jvmLock = JVM_LOCKS.computeIfAbsent(lockFile) { ReentrantLock() }
        jvmLock.withLock {
            Files.createDirectories(file.parent)
            FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    // Earlier chapters win: an established rendering must not flip when a
                    // later chapter discovers a different candidate for the same source.
                    val merged = linkedMapOf<String, String>()
                    load().forEach { merged[it.source] = it.translation }
                    entries.forEach { entry ->
                        if (entry.source.isNotBlank() && entry.translation.isNotBlank()) {
                            merged.putIfAbsent(entry.source, entry.translation)
                        }
                    }
                    val bounded = merged.entries.take(maximumEntries)
                        .map { TranslationGlossaryEntry(it.key, it.value) }
                        .sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
                    val temporary = Files.createTempFile(file.parent, FILE_NAME, ".tmp")
                    try {
                        Files.writeString(temporary, json.encodeToString(serializer, bounded))
                        Files.move(
                            temporary,
                            file,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }
    }

    private companion object {
        const val FILE_NAME = "series-glossary.json"
        const val DEFAULT_MAXIMUM_ENTRIES = 800
        val JVM_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
