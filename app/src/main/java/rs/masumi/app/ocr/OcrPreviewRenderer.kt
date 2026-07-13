package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import rs.masumi.core.ocr.OcrProtectionPolicy
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.PageOcrArtifact

class OcrPreviewRenderer {
    fun renderPng(page: Bitmap, artifact: PageOcrArtifact): ByteArray {
        require(!page.isRecycled) { "source bitmap is recycled" }
        require(page.width == artifact.visibleWidth && page.height == artifact.visibleHeight) {
            "OCR artifact dimensions do not match visible source"
        }
        val preview = page.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("source bitmap could not be copied")
        try {
            val canvas = Canvas(preview)
            val strokeWidth = max(2f, minOf(page.width, page.height) / 240f)
            artifact.regions
                .sortedBy { it.candidate.readingOrderRank }
                .forEach { region -> drawRegion(canvas, region, strokeWidth) }
            return ByteArrayOutputStream().use { output ->
                check(preview.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "OCR preview PNG encoding failed"
                }
                output.toByteArray()
            }
        } finally {
            preview.recycle()
        }
    }

    private fun drawRegion(canvas: Canvas, region: OcrRegionArtifact, strokeWidth: Float) {
        val box = region.candidate.box
        val left = floor(box.left).toFloat().coerceIn(0f, canvas.width.toFloat())
        val top = floor(box.top).toFloat().coerceIn(0f, canvas.height.toFloat())
        val right = ceil(box.right).toFloat().coerceIn(0f, canvas.width.toFloat())
        val bottom = ceil(box.bottom).toFloat().coerceIn(0f, canvas.height.toFloat())
        if (right <= left || bottom <= top) return

        if (region.candidate.protectionPolicy == OcrProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED) {
            canvas.drawRect(
                (left - strokeWidth * 2).coerceAtLeast(0f),
                (top - strokeWidth * 2).coerceAtLeast(0f),
                (right + strokeWidth * 2).coerceAtMost(canvas.width.toFloat()),
                (bottom + strokeWidth * 2).coerceAtMost(canvas.height.toFloat()),
                outlinePaint(PROTECTED_COLOR, strokeWidth),
            )
        }

        val stateColor = colorFor(region.state)
        canvas.drawRect(left, top, right, bottom, outlinePaint(stateColor, strokeWidth))
        val label = labelFor(region)
        val labelPaint = Paint().apply {
            color = stateColor
            style = Paint.Style.FILL
            isAntiAlias = false
            textSize = max(10f, strokeWidth * 5f)
        }
        canvas.drawText(label, left + strokeWidth, (top + labelPaint.textSize).coerceAtMost(bottom), labelPaint)
    }

    private fun outlinePaint(color: Int, strokeWidth: Float): Paint = Paint().apply {
        this.color = color
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    private fun colorFor(state: OcrRegionState): Int = when (state) {
        OcrRegionState.RECOGNIZED -> RECOGNIZED_COLOR
        OcrRegionState.NEEDS_FALLBACK -> NEEDS_FALLBACK_COLOR
        OcrRegionState.NO_TEXT_CONFIRMED -> NO_TEXT_COLOR
        OcrRegionState.PRESERVED_SOURCE -> PRESERVED_COLOR
        OcrRegionState.PENDING,
        OcrRegionState.RUNNING,
        -> PENDING_COLOR
    }

    private fun labelFor(region: OcrRegionArtifact): String {
        val score = region.quality?.aggregateScore?.let {
            String.format(Locale.ROOT, " %.2f", it)
        }.orEmpty()
        val retries = (region.attempts.size - 1).coerceAtLeast(0)
        return "${region.state.name.take(4)}$score r$retries #${region.candidate.ocrRegionId.take(6)}"
    }

    companion object {
        @JvmField val RECOGNIZED_COLOR: Int = Color.rgb(46, 125, 50)
        @JvmField val NEEDS_FALLBACK_COLOR: Int = Color.rgb(255, 179, 0)
        @JvmField val NO_TEXT_COLOR: Int = Color.rgb(117, 117, 117)
        @JvmField val PRESERVED_COLOR: Int = Color.rgb(198, 40, 40)
        @JvmField val PROTECTED_COLOR: Int = Color.rgb(239, 108, 0)
        @JvmField val PENDING_COLOR: Int = Color.rgb(30, 136, 229)
    }
}
