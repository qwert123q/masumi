package rs.masumi.core.translation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, FILE_NAME, ".tmp")
            Files.writeString(temporary, json.encodeToString(serializer, bounded))
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private companion object {
        const val FILE_NAME = "series-glossary.json"
        const val DEFAULT_MAXIMUM_ENTRIES = 800
    }
}
