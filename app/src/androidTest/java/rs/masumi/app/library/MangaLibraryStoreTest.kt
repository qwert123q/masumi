package rs.masumi.app.library

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider

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

        val reloaded = MangaLibraryStore(context.contentResolver, treeUri)
        val projects = reloaded.projects()

        assertEquals(1, projects.size)
        assertEquals("project-one", projects.single().metadata.projectId)
        assertEquals("第一话 / 开始", projects.single().metadata.title)
        assertEquals(2, projects.single().outputPageCount)
        assertEquals(listOf("0001.png", "0002.png"), reloaded.outputPages("project-one").map { it.name })
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

    private fun createOutput(parent: android.net.Uri, name: String, content: String) {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parent, "image/png", name),
        )
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(content.toByteArray()) }
    }
}
