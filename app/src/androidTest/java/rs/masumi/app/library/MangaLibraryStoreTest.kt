package rs.masumi.app.library

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider
import rs.masumi.core.model.PageRecord

@RunWith(AndroidJUnit4::class)
class MangaLibraryStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )

    @Before
    fun setUp() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @Test
    fun projectMetadataAndOutputPagesSurviveAStoreReload() {
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val project = store.ensureProject(
            projectId = "project-one",
            title = "第一话 / 开始",
            createdAtEpochMillis = 123L,
            sourceTreeUri = treeUri,
        )
        assertNotNull(project.outputDirectoryUri)
        val output = requireNotNull(project.outputDirectoryUri)
        createOutput(output, "0002.png", "two")
        createOutput(output, "0001.png", "one")
        // Older chapters may still contain WebP; both formats remain readable.
        createOutput(output, "0003.webp", "three")
        createOutput(output, "notes.txt", "ignored")

        val reloaded = MangaLibraryStore(context.contentResolver, treeUri)
        val projects = reloaded.projects()

        assertEquals(1, projects.size)
        assertEquals("project-one", projects.single().metadata.projectId)
        assertEquals(3, projects.single().metadata.schemaVersion)
        assertEquals("第一话 开始", projects.single().metadata.title)
        assertEquals("第一话 开始", reloaded.documentDisplayName(project.mangaDirectoryUri))
        assertEquals("生肉", reloaded.documentDisplayName(project.sourceDirectoryUri!!))
        assertEquals("翻译后", reloaded.documentDisplayName(project.outputDirectoryUri!!))
        assertNotEquals(project.outputDirectoryUri, project.directoryUri)
        assertEquals(3, projects.single().outputPageCount)
        assertEquals(
            listOf("0001.png", "0002.png", "0003.webp"),
            reloaded.outputPages("project-one").map { it.name },
        )
    }

    @Test
    fun ensuringTheSameProjectReusesItsDirectory() {
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val first = store.ensureProject("project-one", "第一话", 123L, treeUri)
        val second = store.ensureProject("project-one", "ignored replacement", 456L, treeUri)

        assertEquals(first.directoryUri, second.directoryUri)
        assertEquals(1, store.projects().size)
        assertEquals("第一话", second.metadata.title)
    }

    @Test
    fun repeatImportsCreateIndependentFoldersThatCanBeRenamedAndDeleted() {
        val fingerprint = "a".repeat(64)
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val older = store.ensureProject(
            "project-old",
            "铃井",
            1_000L,
            treeUri,
            sourceFingerprint = fingerprint,
        )
        val newer = store.ensureProject(
            "project-new",
            "铃井",
            2_000L,
            treeUri,
            sourceFingerprint = fingerprint,
        )

        assertNotEquals(older.mangaDirectoryUri, newer.mangaDirectoryUri)
        assertNotEquals(older.sourceDirectoryUri, newer.sourceDirectoryUri)
        assertNotEquals(older.outputDirectoryUri, newer.outputDirectoryUri)
        assertEquals("铃井", store.documentDisplayName(older.directoryUri))
        assertEquals("铃井 (2)", store.documentDisplayName(newer.directoryUri))
        assertEquals(
            listOf("project-new", "project-old"),
            store.refreshProjects().map { it.metadata.projectId },
        )

        val renamed = store.renameProject("project-new", "铃井 新译")
        assertEquals("铃井 新译", renamed.metadata.title)
        assertEquals("铃井 新译", store.documentDisplayName(renamed.directoryUri))

        assertTrue(store.deleteProject("project-old"))
        assertFalse(store.deleteProject("project-old"))
        assertEquals(
            listOf("project-new"),
            store.refreshProjects().map { it.metadata.projectId },
        )
    }

    @Test
    fun archivesByteIdenticalSourcesWithTheirOriginalNames() {
        val bytes = "raw-jpeg-content".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-source-")
        try {
            val source = privateProject.resolve("sources/$digest.jpg")
            Files.createDirectories(source.parent)
            Files.write(source, bytes)
            val page = PageRecord(
                order = 0,
                pageId = digest,
                sourceSha256 = digest,
                originalName = "第 01 页.jpg",
                mediaType = "image/jpeg",
                byteLength = bytes.size.toLong(),
                storedPath = "sources/$digest.jpg",
            )
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            store.ensureProject(
                "source-project",
                "铃井",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(listOf(page)),
            )

            store.archiveSourcePages("source-project", privateProject, listOf(page))
            // Repeating the archive must reuse the existing document.
            store.archiveSourcePages("source-project", privateProject, listOf(page))

            val archived = store.sourcePages("source-project").single()
            assertEquals("第 01 页.jpg", archived.name)
            assertTrue(context.contentResolver.openInputStream(archived.uri)!!.use {
                it.readBytes().contentEquals(bytes)
            })
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    private fun createOutput(parent: android.net.Uri, name: String, content: String) {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parent, "image/png", name),
        )
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(content.toByteArray()) }
    }
}
