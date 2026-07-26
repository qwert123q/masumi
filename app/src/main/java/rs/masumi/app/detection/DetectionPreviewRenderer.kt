package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import rs.masumi.app.PreviewImageEncoder
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectorClass
import java.util.Locale

class DetectionPreviewRenderer(
    private val labelTextSizePx: Float = 20f,
    private val strokeWidthPx: Float = 3f,
) {
    fun render(
        page: DecodedPage,
        bubbles: List<DetectedRegion>,
        textRegions: List<DetectedRegion>,
    ): ByteArray {
        val derived = page.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Preview bitmap could not be created")
        try {
            val canvas = Canvas(derived)
            (bubbles + textRegions).forEach { region -> drawRegion(canvas, region) }
            return PreviewImageEncoder.encode(derived)
        } finally {
            derived.recycle()
        }
    }

    private fun drawRegion(canvas: Canvas, region: DetectedRegion) {
        val color = region.detectorClass.previewColor()
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            this.color = color
            isAntiAlias = false
        }
        val box = RectF(
            region.box.left.toFloat(),
            region.box.top.toFloat(),
            region.box.right.toFloat(),
            region.box.bottom.toFloat(),
        )
        canvas.drawRect(box, boxPaint)

        val labelPaint = Paint().apply {
            style = Paint.Style.FILL
            textSize = labelTextSizePx
            this.color = Color.WHITE
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        val label = "${region.detectorClass.name} " +
            String.format(Locale.ROOT, "%.2f", region.confidence) +
            " ${region.regionId.take(12)}"
        val baseline = (box.top + labelTextSizePx).coerceAtMost(canvas.height.toFloat())
        canvas.drawText(label, box.left.coerceAtLeast(0f), baseline, labelPaint)
    }

    private fun DetectorClass.previewColor(): Int = when (this) {
        DetectorClass.BUBBLE -> Color.rgb(25, 118, 210)
        DetectorClass.TEXT_IN_BUBBLE -> Color.rgb(46, 125, 50)
        DetectorClass.TEXT_FREE -> Color.rgb(239, 108, 0)
    }
}
