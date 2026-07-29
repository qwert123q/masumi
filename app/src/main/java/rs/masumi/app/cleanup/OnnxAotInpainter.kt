package rs.masumi.app.cleanup

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AOT-GAN inpainting (manga-image-translator / koharu lineage) exported to
 * ONNX and bundled in the APK assets. The model receives a masked crop
 * normalized to [-1, 1] plus a binary hole mask and synthesizes texture for
 * the hole, which classical interpolation cannot do over screentone or art.
 *
 * Long masks are first split at low-ink rows or columns so page-height display
 * text stays near source resolution. Each tile is cropped with surrounding
 * context, scaled to at most [MAX_SIDE] pixels, padded to the model's
 * multiple-of-8 geometry, and synthesized back into masked page pixels only.
 */
class OnnxAotInpainter(
    modelBytes: ByteArray,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : NeuralInpainter, AutoCloseable {
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, MAX_INTRA_OP_THREADS),
        )
        environment.createSession(modelBytes, options)
    }
    private val inferenceLock = Any()

    override fun inpaint(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        roiMask: BooleanArray,
    ): Boolean {
        val roiWidth = roiRight - roiLeft
        val roiHeight = roiBottom - roiTop
        if (roiWidth <= 0 || roiHeight <= 0 || roiMask.size != roiWidth * roiHeight) return false
        if (roiMask.none { it }) return true

        val tiles = InpaintTilePlanner.plan(
            width = roiWidth,
            height = roiHeight,
            mask = roiMask,
            maximumSpan = MAXIMUM_MASK_TILE_SPAN,
            minimumSpan = MINIMUM_MASK_TILE_SPAN,
            cutSearchRadius = TILE_CUT_SEARCH_RADIUS,
        )
        val maskedPageIndexes = IntArray(roiMask.count { it })
        val originalColors = IntArray(maskedPageIndexes.size)
        var maskedIndex = 0
        roiMask.indices.forEach { local ->
            if (!roiMask[local]) return@forEach
            val pageIndex =
                (roiTop + local / roiWidth) * pageWidth + roiLeft + local % roiWidth
            maskedPageIndexes[maskedIndex] = pageIndex
            originalColors[maskedIndex] = pixels[pageIndex]
            maskedIndex += 1
        }
        return try {
            val succeeded = tiles.all { tile ->
                val tileMask = BooleanArray(tile.width * tile.height)
                for (y in tile.top until tile.bottom) for (x in tile.left until tile.right) {
                    tileMask[(y - tile.top) * tile.width + x - tile.left] = roiMask[y * roiWidth + x]
                }
                inpaintSingle(
                    pixels = pixels,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    roiLeft = roiLeft + tile.left,
                    roiTop = roiTop + tile.top,
                    roiRight = roiLeft + tile.right,
                    roiBottom = roiTop + tile.bottom,
                    roiMask = tileMask,
                )
            }
            if (!succeeded) maskedPageIndexes.indices.forEach { pixels[maskedPageIndexes[it]] = originalColors[it] }
            succeeded
        } catch (failure: Throwable) {
            maskedPageIndexes.indices.forEach { pixels[maskedPageIndexes[it]] = originalColors[it] }
            throw failure
        }
    }

    private fun inpaintSingle(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        roiMask: BooleanArray,
    ): Boolean {
        val roiWidth = roiRight - roiLeft
        val roiHeight = roiBottom - roiTop
        val context = (min(roiWidth, roiHeight) * CONTEXT_FRACTION).roundToInt()
            .coerceIn(MINIMUM_CONTEXT_PIXELS, MAXIMUM_CONTEXT_PIXELS)
        val cropLeft = (roiLeft - context).coerceAtLeast(0)
        val cropTop = (roiTop - context).coerceAtLeast(0)
        val cropRight = (roiRight + context).coerceAtMost(pageWidth)
        val cropBottom = (roiBottom + context).coerceAtMost(pageHeight)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth < MINIMUM_CROP_SIDE || cropHeight < MINIMUM_CROP_SIDE) return false

        val scale = min(1.0, MAX_SIDE.toDouble() / max(cropWidth, cropHeight))
        val scaledWidth = (cropWidth * scale).roundToInt().coerceAtLeast(MINIMUM_CROP_SIDE)
        val scaledHeight = (cropHeight * scale).roundToInt().coerceAtLeast(MINIMUM_CROP_SIDE)
        val inputWidth = ceil(scaledWidth / PAD_MULTIPLE.toDouble()).toInt() * PAD_MULTIPLE
        val inputHeight = ceil(scaledHeight / PAD_MULTIPLE.toDouble()).toInt() * PAD_MULTIPLE

        // Model-space hole mask: mark every input pixel whose source footprint
        // touches a masked ROI pixel, so downscaling never shrinks the hole.
        val holeMask = FloatArray(inputWidth * inputHeight)
        val scaleX = scaledWidth.toDouble() / cropWidth
        val scaleY = scaledHeight.toDouble() / cropHeight
        for (localY in 0 until roiHeight) {
            for (localX in 0 until roiWidth) {
                if (!roiMask[localY * roiWidth + localX]) continue
                val pageX = roiLeft + localX
                val pageY = roiTop + localY
                val startX = ((pageX - cropLeft) * scaleX).toInt().coerceIn(0, inputWidth - 1)
                val endX = ((pageX + 1 - cropLeft) * scaleX).toInt().coerceIn(0, inputWidth - 1)
                val startY = ((pageY - cropTop) * scaleY).toInt().coerceIn(0, inputHeight - 1)
                val endY = ((pageY + 1 - cropTop) * scaleY).toInt().coerceIn(0, inputHeight - 1)
                for (y in startY..endY) for (x in startX..endX) {
                    holeMask[y * inputWidth + x] = 1f
                }
            }
        }

        val image = FloatArray(3 * inputWidth * inputHeight)
        val planeSize = inputWidth * inputHeight
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val index = y * inputWidth + x
                if (holeMask[index] > 0f) continue
                val color = sampleBilinear(
                    pixels,
                    pageWidth,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight,
                    (x + 0.5) / scaleX - 0.5,
                    (y + 0.5) / scaleY - 0.5,
                )
                image[index] = Color.red(color) / 127.5f - 1f
                image[planeSize + index] = Color.green(color) / 127.5f - 1f
                image[2 * planeSize + index] = Color.blue(color) / 127.5f - 1f
            }
        }

        val output = synchronized(inferenceLock) {
            val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
            val maskShape = longArrayOf(1, 1, inputHeight.toLong(), inputWidth.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(image), shape).use { imageTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(holeMask), maskShape).use { maskTensor ->
                    session.run(mapOf("image" to imageTensor, "mask" to maskTensor)).use { result ->
                        val tensor = result.get(0) as? OnnxTensor ?: return false
                        val buffer = tensor.floatBuffer ?: return false
                        if (buffer.remaining() != 3 * planeSize) return false
                        FloatArray(3 * planeSize).also(buffer::get)
                    }
                }
            }
        }
        if (output.any { !it.isFinite() }) return false

        for (localY in 0 until roiHeight) {
            for (localX in 0 until roiWidth) {
                if (!roiMask[localY * roiWidth + localX]) continue
                val pageX = roiLeft + localX
                val pageY = roiTop + localY
                val sourceX = ((pageX - cropLeft + 0.5) * scaleX - 0.5)
                    .coerceIn(0.0, (inputWidth - 1).toDouble())
                val sourceY = ((pageY - cropTop + 0.5) * scaleY - 0.5)
                    .coerceIn(0.0, (inputHeight - 1).toDouble())
                val pageIndex = pageY * pageWidth + pageX
                pixels[pageIndex] = Color.argb(
                    Color.alpha(pixels[pageIndex]),
                    output.sampleChannel(0, planeSize, inputWidth, inputHeight, sourceX, sourceY),
                    output.sampleChannel(1, planeSize, inputWidth, inputHeight, sourceX, sourceY),
                    output.sampleChannel(2, planeSize, inputWidth, inputHeight, sourceX, sourceY),
                )
            }
        }
        return true
    }

    override fun close() {
        session.close()
    }

    private fun FloatArray.sampleChannel(
        plane: Int,
        planeSize: Int,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        val base = plane * planeSize
        val top = this[base + y0 * width + x0] * (1 - fx) + this[base + y0 * width + x1] * fx
        val bottom = this[base + y1 * width + x0] * (1 - fx) + this[base + y1 * width + x1] * fx
        val value = top * (1 - fy) + bottom * fy
        return ((value + 1.0) * 127.5).roundToInt().coerceIn(0, 255)
    }

    private fun sampleBilinear(
        pixels: IntArray,
        stride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        x: Double,
        y: Double,
    ): Int {
        val clampedX = x.coerceIn(0.0, (cropWidth - 1).toDouble())
        val clampedY = y.coerceIn(0.0, (cropHeight - 1).toDouble())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = (x0 + 1).coerceAtMost(cropWidth - 1)
        val y1 = (y0 + 1).coerceAtMost(cropHeight - 1)
        val fx = clampedX - x0
        val fy = clampedY - y0
        fun channel(selector: (Int) -> Int): Int {
            val top = selector(pixels[(cropTop + y0) * stride + cropLeft + x0]) * (1 - fx) +
                selector(pixels[(cropTop + y0) * stride + cropLeft + x1]) * fx
            val bottom = selector(pixels[(cropTop + y1) * stride + cropLeft + x0]) * (1 - fx) +
                selector(pixels[(cropTop + y1) * stride + cropLeft + x1]) * fx
            return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return Color.argb(255, channel(Color::red), channel(Color::green), channel(Color::blue))
    }

    companion object {
        const val ASSET_PATH = "models/aot-inpainting.onnx"
        const val REVISION = "aot-inpainting-mit-v1"
        private const val MAX_SIDE = 512
        private const val PAD_MULTIPLE = 8
        private const val CONTEXT_FRACTION = 0.75
        private const val MINIMUM_CONTEXT_PIXELS = 32
        private const val MAXIMUM_CONTEXT_PIXELS = 128
        private const val MINIMUM_CROP_SIDE = 16
        private const val MAXIMUM_MASK_TILE_SPAN = 384
        private const val MINIMUM_MASK_TILE_SPAN = 128
        private const val TILE_CUT_SEARCH_RADIUS = 64
        private const val MAX_INTRA_OP_THREADS = 6
    }
}
