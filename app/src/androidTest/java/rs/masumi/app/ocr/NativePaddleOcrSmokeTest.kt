package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.ocr.OcrExecutionBackend

@RunWith(AndroidJUnit4::class)
class NativePaddleOcrSmokeTest {
    @Test
    fun recognizesGeneratedJapaneseCropWithTokenProbabilities() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString("ocrModelPath")
        val projectorPath = arguments.getString("ocrProjectorPath")
        val expectedBackend = arguments.getString("nativeOcrExpectedBackend")
            ?.let(OcrExecutionBackend::valueOf)
            ?: OcrExecutionBackend.VULKAN
        val forceCpu = arguments.getString("nativeOcrForceCpu").toBoolean()
        assumeTrue(!modelPath.isNullOrBlank() && !projectorPath.isNullOrBlank())
        val (rgb, dimensions) = japaneseCrop()

        val engine = if (forceCpu) {
            NativePaddleOcrEngine.openCpuOnly(Path.of(modelPath), Path.of(projectorPath))
        } else {
            NativePaddleOcrEngine.open(Path.of(modelPath), Path.of(projectorPath))
        }
        engine.use {
            assertEquals(expectedBackend, it.executionBackend)
            val result = it.recognize(
                OcrEngineRequest(
                    rgb = rgb,
                    width = dimensions.first,
                    height = dimensions.second,
                    maximumGeneratedTokens = 64,
                ),
                cancellation = { false },
            )

            assertTrue(result.rawText.isNotBlank())
            assertTrue(result.rawText.contains("今日は"))
            assertTrue(result.rawText.toByteArray(Charsets.UTF_8).isNotEmpty())
            assertTrue(result.tokenIds.isNotEmpty())
            assertEquals(result.tokenIds.size, result.tokenProbabilities.size)
            assertTrue(result.visualTokenCount > 0)
            assertFalse(result.truncated)
            println(
                "OCR_SMOKE_METRICS visualTokens=${result.visualTokenCount} " +
                    "generatedTokens=${result.generatedTokenCount} " +
                    "promptMillis=${result.promptEvaluationMillis} " +
                    "generationMillis=${result.generationMillis}",
            )
        }
    }

    private fun japaneseCrop(): Pair<ByteArray, Pair<Int, Int>> {
        val width = 384
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "今日は",
                24f,
                92f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 72f
                },
            )
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val rgb = ByteArray(width * height * 3)
        pixels.forEachIndexed { index, color ->
            rgb[index * 3] = Color.red(color).toByte()
            rgb[index * 3 + 1] = Color.green(color).toByte()
            rgb[index * 3 + 2] = Color.blue(color).toByte()
        }
        return rgb to (width to height)
    }
}
