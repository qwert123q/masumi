package rs.masumi.core.translation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceGlossaryStoreTest {
    @Test
    fun `loads empty when no glossary was recorded`() {
        val root = Files.createTempDirectory("glossary-empty-")
        try {
            assertTrue(WorkspaceGlossaryStore(root).load().isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records entries and keeps earlier chapters authoritative`() {
        val root = Files.createTempDirectory("glossary-merge-")
        try {
            val store = WorkspaceGlossaryStore(root)
            store.record(
                listOf(
                    TranslationGlossaryEntry("ハルカ", "遥"),
                    TranslationGlossaryEntry("先輩", "前辈"),
                ),
            )
            store.record(
                listOf(
                    TranslationGlossaryEntry("ハルカ", "春香"),
                    TranslationGlossaryEntry("東京", "东京"),
                ),
            )

            val loaded = store.load()
            assertEquals(
                listOf(
                    TranslationGlossaryEntry("ハルカ", "遥"),
                    TranslationGlossaryEntry("先輩", "前辈"),
                    TranslationGlossaryEntry("東京", "东京"),
                ),
                loaded.sortedBy(TranslationGlossaryEntry::source),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `caps stored entries and survives corrupt files`() {
        val root = Files.createTempDirectory("glossary-cap-")
        try {
            val store = WorkspaceGlossaryStore(root, maximumEntries = 3)
            store.record((1..5).map { TranslationGlossaryEntry("源$it", "译$it") })
            assertEquals(3, store.load().size)

            Files.writeString(root.resolve("series-glossary.json"), "not json")
            assertTrue(WorkspaceGlossaryStore(root).load().isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
