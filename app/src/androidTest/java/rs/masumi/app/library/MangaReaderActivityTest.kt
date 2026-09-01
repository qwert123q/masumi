package rs.masumi.app.library

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.R
import rs.masumi.app.TestDocumentsProvider

@RunWith(AndroidJUnit4::class)
class MangaReaderActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val targetContext = instrumentation.targetContext
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
    fun opensACompletedLibraryProjectForReading() {
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val project = store.ensureProject("reader-project", "第一话", 123L, treeUri)
        val outputDirectory = requireNotNull(project.outputDirectoryUri)
        val pageUri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                outputDirectory,
                "image/png",
                "0001.png",
            ),
        )
        context.contentResolver.openOutputStream(pageUri, "w")!!.use { it.write(png()) }
        val reloadedProject = requireNotNull(store.project("reader-project"))
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${targetContext.packageName}/rs.masumi.app.library.LibraryActivity",
            ),
        ).use { it.readBytes() }
        instrumentation.waitForIdleSync()

        ActivityScenario.launch<MangaReaderActivity>(
            MangaReaderActivity.intent(targetContext, treeUri, reloadedProject),
        ).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var loaded = false
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    loaded = activity.findViewById<TextView>(R.id.readerStatus)
                        .text.toString().contains("1 / 1")
                }
                if (!loaded) SystemClock.sleep(50L)
            }
            assertTrue("reader did not render the first page", loaded)
            scenario.onActivity { activity ->
                assertTrue(
                    activity.findViewById<Button>(R.id.readerJumpButton).text.toString() == "跳页",
                )
                assertTrue(
                    activity.findViewById<EditText>(R.id.readerJumpInput).hint.toString() == "页码",
                )
                assertTrue(
                    activity.findViewById<ContinuousReaderView>(R.id.readerContinuous).visibility ==
                        android.view.View.VISIBLE,
                )
                assertTrue(
                    activity.findViewById<Button>(R.id.readerRestartButton).text.toString() == "从头阅读",
                )
            }
        }
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
