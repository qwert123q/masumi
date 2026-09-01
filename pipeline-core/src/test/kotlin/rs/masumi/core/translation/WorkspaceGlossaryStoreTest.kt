package rs.masumi.core.translation

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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

    @Test
    fun `concurrent stores retain both mappings while earlier mapping stays authoritative`() {
        val root = Files.createTempDirectory("glossary-concurrent-")
        try {
            WorkspaceGlossaryStore(root).record(listOf(TranslationGlossaryEntry("既存", "先到")))
            val start = CountDownLatch(1)
            val ready = CountDownLatch(2)
            val finished = CountDownLatch(2)
            val first = WorkspaceGlossaryStore(root)
            val second = WorkspaceGlossaryStore(root)
            val workers = listOf(
                thread {
                    ready.countDown()
                    start.await()
                    first.record(listOf(TranslationGlossaryEntry("既存", "后到"), TranslationGlossaryEntry("甲", "甲译")))
                    finished.countDown()
                },
                thread {
                    ready.countDown()
                    start.await()
                    second.record(listOf(TranslationGlossaryEntry("乙", "乙译")))
                    finished.countDown()
                },
            )
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            workers.forEach(Thread::join)

            assertEquals(
                listOf(
                    TranslationGlossaryEntry("乙", "乙译"),
                    TranslationGlossaryEntry("既存", "先到"),
                    TranslationGlossaryEntry("甲", "甲译"),
                ),
                WorkspaceGlossaryStore(root).load(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
