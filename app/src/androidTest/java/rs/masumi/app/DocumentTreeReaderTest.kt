package rs.masumi.app

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.importer.IdSource
import rs.masumi.core.importer.ProjectImporter
import java.nio.file.Files
import kotlin.io.path.exists

@RunWith(AndroidJUnit4::class)
class DocumentTreeReaderTest {
    @Test
    fun readsDirectChildrenAndTheirStreams() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        val providerInfo = context.packageManager.resolveContentProvider(
            TestDocumentsProvider.AUTHORITY,
            0,
        )
        assertNotNull("The test provider must resolve", providerInfo)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            assertEquals("The provider must expose four direct children", 4, cursor.count)
        } ?: error("The provider query returned no cursor")

        val sources = DocumentTreeReader(context.contentResolver).read(treeUri)

        assertEquals(listOf("1.jpg", "2.png", "notes.txt", "nested"), sources.map { it.displayName })
        assertFalse(sources[0].isDirectory)
        assertEquals("image/jpeg", sources[0].mediaType)
        assertEquals("one", sources[0].openStream().bufferedReader().use { it.readText() })
        assertTrue(sources[3].isDirectory)
    }

    @Test
    fun importsProviderDocumentsIntoACompleteProject() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        val workspace = Files.createTempDirectory(
            instrumentation.targetContext.cacheDir.toPath(),
            "import-contract-",
        )

        try {
            val sources = DocumentTreeReader(context.contentResolver).read(treeUri)
            val ids = ArrayDeque(listOf("project-contract", "job-contract"))
            val outcome = ProjectImporter(
                workspaceRoot = workspace,
                idSource = IdSource { ids.removeFirst() },
            ).importProject(sources)

            assertEquals(listOf("1.jpg", "2.png"), outcome.manifest.pages.map { it.originalName })
            assertEquals(4, outcome.report.discoveredCount)
            assertEquals(2, outcome.report.importedCount)
            assertEquals(2, outcome.report.skippedCount)
            assertTrue(outcome.projectDirectory.resolve("manifest.json").exists())
            assertTrue(outcome.projectDirectory.resolve("reports/job-contract.json").exists())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
