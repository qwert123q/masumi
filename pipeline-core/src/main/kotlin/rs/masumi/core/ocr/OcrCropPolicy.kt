package rs.masumi.core.ocr

import kotlin.math.max
import kotlin.math.min
import rs.masumi.core.detection.PixelBox

class OcrCropPolicy(
    private val config: OcrCropConfig = OcrCropConfig(),
) {
    fun attempts(
        candidate: OcrCandidate,
        pageWidth: Int,
        pageHeight: Int,
        bubbleBox: PixelBox? = candidate.associatedBubbleBox,
    ): List<OcrCropDescriptor> {
        require(pageWidth > 0) { "pageWidth must be positive" }
        require(pageHeight > 0) { "pageHeight must be positive" }
        val page = PixelBox(0.0, 0.0, pageWidth.toDouble(), pageHeight.toDouble())
        return listOf(
            descriptor(OcrCropStrategy.PADDED_TEXT, candidate.box, config.paddedTextFraction, page),
            descriptor(OcrCropStrategy.TIGHT_TEXT, candidate.box, config.tightTextFraction, page),
            descriptor(
                OcrCropStrategy.CONTEXT_TEXT,
                candidate.box,
                config.contextTextFraction,
                bubbleBox?.let { intersection(page, it) } ?: page,
            ),
        )
    }

    private fun descriptor(
        strategy: OcrCropStrategy,
        source: PixelBox,
        fraction: Double,
        bounds: PixelBox,
    ): OcrCropDescriptor {
        val horizontalPadding = max(config.minimumPaddingPixels.toDouble(), width(source) * fraction)
        val verticalPadding = max(config.minimumPaddingPixels.toDouble(), height(source) * fraction)
        val expanded = PixelBox(
            left = source.left - horizontalPadding,
            top = source.top - verticalPadding,
            right = source.right + horizontalPadding,
            bottom = source.bottom + verticalPadding,
        )
        return OcrCropDescriptor(strategy, intersection(expanded, bounds))
    }

    private fun intersection(first: PixelBox, second: PixelBox): PixelBox = PixelBox(
        left = max(first.left, second.left),
        top = max(first.top, second.top),
        right = max(max(first.left, second.left), min(first.right, second.right)),
        bottom = max(max(first.top, second.top), min(first.bottom, second.bottom)),
    )

    private fun width(box: PixelBox): Double = (box.right - box.left).coerceAtLeast(0.0)

    private fun height(box: PixelBox): Double = (box.bottom - box.top).coerceAtLeast(0.0)
}
