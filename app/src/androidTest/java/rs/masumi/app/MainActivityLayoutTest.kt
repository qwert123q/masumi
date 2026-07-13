package rs.masumi.app

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLayoutTest {
    @Test
    fun idleLayoutShowsImportControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                android.R.style.Theme_Material_Light_NoActionBar,
            )
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        }

        assertEquals("导入漫画文件夹", root.findViewById<Button>(R.id.importButton).text.toString())
        assertEquals("等待导入", root.findViewById<TextView>(R.id.statusText).text.toString())
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.importProgress).visibility)
    }

    @Test
    fun idleLayoutContainsSafeAnalysisProgressAndPreviewControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                android.R.style.Theme_Material_Light_NoActionBar,
            )
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        }

        val analysisButton = root.findViewById<Button>(R.id.analysisButton)
        val cancelButton = root.findViewById<Button>(R.id.cancelAnalysisButton)
        val progress = root.findViewById<ProgressBar>(R.id.detectionProgress)
        assertEquals("开始页面分析", analysisButton.text.toString())
        assertEquals(false, analysisButton.isEnabled)
        assertEquals(View.GONE, cancelButton.visibility)
        assertEquals(false, progress.isIndeterminate)
        assertEquals(View.GONE, progress.visibility)
        assertEquals("尚未导入漫画", root.findViewById<TextView>(R.id.detectionStatus).text.toString())

        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.previewImage).visibility)
        assertEquals(false, root.findViewById<Button>(R.id.previousPageButton).isEnabled)
        assertEquals(false, root.findViewById<Button>(R.id.nextPageButton).isEnabled)
        assertEquals("尚无分析结果", root.findViewById<TextView>(R.id.pageIndicator).text.toString())
        assertEquals("蓝色：对白框候选", root.findViewById<TextView>(R.id.legendBubble).text.toString())
        assertEquals("绿色：框内文字", root.findViewById<TextView>(R.id.legendTextInBubble).text.toString())
        assertEquals("橙色：待分类游离文字", root.findViewById<TextView>(R.id.legendFreeText).text.toString())

        val ocrButton = root.findViewById<Button>(R.id.ocrButton)
        assertEquals("开始本地 OCR", ocrButton.text.toString())
        assertEquals(false, ocrButton.isEnabled)
        assertEquals(View.GONE, root.findViewById<Button>(R.id.cancelOcrButton).visibility)
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.ocrProgress).visibility)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.ocrPreviewImage).visibility)
        assertEquals("尚无 OCR 结果", root.findViewById<TextView>(R.id.ocrPageIndicator).text.toString())
        assertEquals("", root.findViewById<TextView>(R.id.ocrDetailText).text.toString())
    }
}
