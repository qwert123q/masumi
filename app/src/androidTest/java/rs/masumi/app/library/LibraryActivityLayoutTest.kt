package rs.masumi.app.library

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.HorizontalSwipeViewFlipper
import rs.masumi.app.R

@RunWith(AndroidJUnit4::class)
class LibraryActivityLayoutTest {
    @Test
    fun libraryHomeKeepsImportAndProjectActionsAboveTechnicalDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View
        lateinit var item: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            root = LayoutInflater.from(context).inflate(R.layout.activity_library, null, false)
            item = LayoutInflater.from(context).inflate(R.layout.item_library_project, null, false)
        }

        assertEquals("导入漫画", root.findViewById<Button>(R.id.libraryHomeImport).text.toString())
        assertEquals("我的漫画", root.findViewById<TextView>(R.id.libraryHomeProjectsTitle).text.toString())
        assertEquals(6, item.findViewById<ProgressBar>(R.id.libraryProjectProgress).max)
        assertEquals("阅读成品", item.findViewById<Button>(R.id.libraryProjectReadButton).text.toString())
        assertEquals("管理", item.findViewById<Button>(R.id.libraryProjectOpenButton).text.toString())
    }

    @Test
    fun libraryHomeSplitsFinishedAndProcessingIntoSwipeableColumns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            root = LayoutInflater.from(context).inflate(R.layout.activity_library, null, false)
        }

        val pager = root.findViewById<HorizontalSwipeViewFlipper>(R.id.libraryHomePager)
        assertEquals(2, pager.childCount)
        assertEquals("成品漫画", root.findViewById<Button>(R.id.libraryHomeTabFinished).text.toString())
        assertEquals("处理中", root.findViewById<Button>(R.id.libraryHomeTabProcessing).text.toString())
        // Each column owns its list and empty placeholder so one side never
        // leaks into the other while swiping.
        listOf(
            R.id.libraryHomeFinishedList to R.id.libraryHomeFinishedEmpty,
            R.id.libraryHomeProcessingList to R.id.libraryHomeProcessingEmpty,
        ).forEachIndexed { index, (listId, emptyId) ->
            val pane = pager.getChildAt(index)
            assertNotNull(pane.findViewById<View>(listId))
            assertNotNull(pane.findViewById<View>(emptyId))
        }
    }
}
