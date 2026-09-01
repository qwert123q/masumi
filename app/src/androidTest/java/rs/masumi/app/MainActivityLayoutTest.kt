package rs.masumi.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                R.style.Theme_Masumi,
            )
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        }

        assertEquals("导入漫画文件夹", root.findViewById<Button>(R.id.importButton).text.toString())
        assertEquals(true, root.findViewById<Button>(R.id.importButton).isEnabled)
        assertEquals("等待导入", root.findViewById<TextView>(R.id.statusText).text.toString())
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.importProgress).visibility)
        assertEquals(false, root.findViewById<Button>(R.id.processButton).isEnabled)
        assertEquals("开始自动处理", root.findViewById<Button>(R.id.processButton).text.toString())
        assertEquals("选择漫画库文件夹", root.findViewById<Button>(R.id.chooseLibraryButton).text.toString())
        assertEquals(
            "尚未设置漫画库，导入前会先让你选择。",
            root.findViewById<TextView>(R.id.libraryLocationText).text.toString(),
        )
        assertEquals(View.VISIBLE, root.findViewById<TextView>(R.id.libraryEmptyText).visibility)
        assertEquals(0, root.findViewById<HorizontalSwipeViewFlipper>(R.id.contentPager).displayedChild)
    }

    @Test
    fun workspaceAndProcessingDetailsSwitchHorizontally() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        }

        val pager = root.findViewById<HorizontalSwipeViewFlipper>(R.id.contentPager)
        assertEquals(2, pager.childCount)
        assertEquals(0, pager.displayedChild)
        assertEquals(View.VISIBLE, pager.getChildAt(0).visibility)
        assertEquals(View.INVISIBLE, pager.getChildAt(1).visibility)
        repeat(pager.childCount) { index ->
            val page = pager.getChildAt(index) as ScrollView
            assertNull((page.getChildAt(0) as ViewGroup).layoutTransition)
        }
        pager.displayedChild = 1
        assertEquals(1, pager.displayedChild)
        assertEquals(View.INVISIBLE, pager.getChildAt(0).visibility)
        assertEquals(View.VISIBLE, pager.getChildAt(1).visibility)
        pager.displayedChild = 0
        assertEquals(0, pager.displayedChild)
        assertEquals(View.VISIBLE, pager.getChildAt(0).visibility)
        assertEquals(View.INVISIBLE, pager.getChildAt(1).visibility)
    }

    @Test
    fun idleLayoutContainsSafeAnalysisProgressAndPreviewControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                R.style.Theme_Masumi,
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

        assertEquals(
            "保存并设为当前厂商",
            root.findViewById<Button>(R.id.saveTranslationSettingsButton).text.toString(),
        )
        assertEquals(
            "API 地址（HTTP/HTTPS）",
            root.findViewById<EditText>(R.id.translationApiUrl).hint.toString(),
        )
        assertEquals(
            View.VISIBLE,
            root.findViewById<Spinner>(R.id.translationEndpointPreset).visibility,
        )
        assertEquals(
            "获取可用模型",
            root.findViewById<Button>(R.id.fetchTranslationModelsButton).text.toString(),
        )
        assertEquals(
            View.GONE,
            root.findViewById<ProgressBar>(R.id.translationModelsProgress).visibility,
        )
        assertEquals(View.GONE, root.findViewById<Spinner>(R.id.translationModelPreset).visibility)
        assertEquals(View.VISIBLE, root.findViewById<EditText>(R.id.translationModel).visibility)
        assertEquals(
            "测试连接与试译",
            root.findViewById<Button>(R.id.testTranslationConnectionButton).text.toString(),
        )
        assertEquals(
            View.GONE,
            root.findViewById<ProgressBar>(R.id.translationConnectionProgress).visibility,
        )
        assertEquals(
            "使用当前地址、Key 和模型进行一次日译中试译",
            root.findViewById<TextView>(R.id.translationConnectionStatus).text.toString(),
        )
        assertEquals("选择或配置翻译厂商", root.findViewById<Button>(R.id.translationSettingsToggleButton).text.toString())
        assertEquals(View.GONE, root.findViewById<LinearLayout>(R.id.translationSettingsContainer).visibility)
        assertEquals("开始整章翻译", root.findViewById<Button>(R.id.translationButton).text.toString())
        assertEquals(false, root.findViewById<Button>(R.id.translationButton).isEnabled)
        assertEquals(View.GONE, root.findViewById<Button>(R.id.cancelTranslationButton).visibility)
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.translationProgress).visibility)
        assertEquals("完成 OCR 后可直接开始翻译", root.findViewById<TextView>(R.id.translationStatus).text.toString())

        assertEquals("生成清理后页面", root.findViewById<Button>(R.id.cleanupButton).text.toString())
        assertEquals(false, root.findViewById<Button>(R.id.cleanupButton).isEnabled)
        assertEquals(View.GONE, root.findViewById<Button>(R.id.cancelCleanupButton).visibility)
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.cleanupProgress).visibility)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.cleanupPreviewImage).visibility)
        assertEquals("尚无清理结果", root.findViewById<TextView>(R.id.cleanupPageIndicator).text.toString())
        assertEquals("完成翻译后可清理已翻译区域的日文", root.findViewById<TextView>(R.id.cleanupStatus).text.toString())

        assertEquals("生成嵌字成品页", root.findViewById<Button>(R.id.typesettingButton).text.toString())
        assertEquals(false, root.findViewById<Button>(R.id.typesettingButton).isEnabled)
        assertEquals(View.GONE, root.findViewById<Button>(R.id.cancelTypesettingButton).visibility)
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.typesettingProgress).visibility)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.typesettingPreviewImage).visibility)
        assertEquals("尚无嵌字结果", root.findViewById<TextView>(R.id.typesettingPageIndicator).text.toString())
        assertEquals("完成原文清理后可生成中文嵌字页面", root.findViewById<TextView>(R.id.typesettingStatus).text.toString())

        assertEquals("保存全部页面到漫画库", root.findViewById<Button>(R.id.exportButton).text.toString())
        assertEquals(false, root.findViewById<Button>(R.id.exportButton).isEnabled)
        assertEquals(View.GONE, root.findViewById<Button>(R.id.cancelExportButton).visibility)
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.exportProgress).visibility)
        assertEquals("完成中文嵌字后可导出全部 PNG 页面", root.findViewById<TextView>(R.id.exportStatus).text.toString())
    }

    @Test
    fun idleLayoutRendersAReadablePhoneViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var snapshot: File
        lateinit var workflowSnapshot: File

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false) as LinearLayout
            val width = (PHONE_WIDTH_DP * context.resources.displayMetrics.density).toInt()
            val height = (PHONE_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
            root.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)

            val minimumTouchTarget = (48 * context.resources.displayMetrics.density).toInt()
            assertTrue(root.findViewById<Button>(R.id.importButton).measuredHeight >= minimumTouchTarget)
            assertTrue(root.findViewById<Button>(R.id.analysisButton).measuredHeight >= minimumTouchTarget)

            val snapshotWidth = width / 2
            val snapshotHeight = height / 2
            val bitmap = Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(context.getColor(R.color.masumi_background))
            canvas.scale(0.5f, 0.5f)
            root.draw(canvas)
            assertTrue(bitmap.getPixel(snapshotWidth / 2, snapshotHeight / 2) != Color.TRANSPARENT)
            snapshot = File(instrumentation.targetContext.cacheDir, SNAPSHOT_NAME)
            snapshot.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            bitmap.recycle()

            val pager = root.findViewById<HorizontalSwipeViewFlipper>(R.id.contentPager)
            pager.displayedChild = 1
            root.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            val workflowBitmap = Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.ARGB_8888)
            val workflowCanvas = Canvas(workflowBitmap)
            workflowCanvas.drawColor(context.getColor(R.color.masumi_background))
            workflowCanvas.scale(0.5f, 0.5f)
            root.draw(workflowCanvas)
            workflowSnapshot = File(instrumentation.targetContext.cacheDir, WORKFLOW_SNAPSHOT_NAME)
            workflowSnapshot.outputStream().use { output ->
                workflowBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            workflowBitmap.recycle()
        }

        assertTrue(snapshot.isFile)
        assertTrue(snapshot.length() > 10_000L)
        assertTrue(workflowSnapshot.isFile)
        assertTrue(workflowSnapshot.length() > 10_000L)
    }

    private companion object {
        const val PHONE_WIDTH_DP = 393
        const val PHONE_HEIGHT_DP = 852
        const val SNAPSHOT_NAME = "masumi-main-layout.png"
        const val WORKFLOW_SNAPSHOT_NAME = "masumi-main-workflow.png"
    }
}
