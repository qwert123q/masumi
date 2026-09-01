package rs.masumi.app.cleanup

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Color
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import rs.masumi.core.modelpackage.PinnedAotInpainter

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
    private val session: OrtSession = createValidatedSession(environment, modelBytes)
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
        control: NeuralRunControl,
    ): Boolean {
        val roiWidth = roiRight - roiLeft
        val roiHeight = roiBottom - roiTop
        if (roiWidth <= 0 || roiHeight <= 0 || roiMask.size != roiWidth * roiHeight) return false
        if (roiMask.none { it }) return true
        if (control.shouldStop()) return false

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
                if (control.shouldStop()) return@all false
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
                    contextMaskLeft = roiLeft,
                    contextMaskTop = roiTop,
                    contextMaskWidth = roiWidth,
                    contextMaskHeight = roiHeight,
                    contextMask = roiMask,
                    control = control,
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
        contextMaskLeft: Int,
        contextMaskTop: Int,
        contextMaskWidth: Int,
        contextMaskHeight: Int,
        contextMask: BooleanArray,
        control: NeuralRunControl,
    ): Boolean {
        if (control.shouldStop()) return false
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
        val scaleX = scaledWidth.toDouble() / cropWidth
        val scaleY = scaledHeight.toDouble() / cropHeight
        val holeMask = InpaintContextMask.project(
            mask = contextMask,
            maskLeft = contextMaskLeft,
            maskTop = contextMaskTop,
            maskWidth = contextMaskWidth,
            maskHeight = contextMaskHeight,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
        )

        val image = FloatArray(3 * inputWidth * inputHeight)
        val planeSize = inputWidth * inputHeight
        for (y in 0 until inputHeight) {
            if (control.shouldStop()) return false
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
            if (control.shouldStop()) return false
            val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
            val maskShape = longArrayOf(1, 1, inputHeight.toLong(), inputWidth.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(image), shape).use { imageTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(holeMask), maskShape).use { maskTensor ->
                    OrtSession.RunOptions().use { runOptions ->
                        val timeoutGuard = Any()
                        var timeoutActive = true
                        val remaining = control.remainingMillis()
                        if (remaining <= 0L) return false
                        val firstStopCheck = minOf(STOP_CHECK_INTERVAL_MILLIS, remaining)
                        val timeout = TIMEOUT_EXECUTOR.scheduleWithFixedDelay(
                            {
                                if (!control.shouldStop()) return@scheduleWithFixedDelay
                                synchronized(timeoutGuard) {
                                    if (timeoutActive) runCatching { runOptions.setTerminate(true) }
                                }
                            },
                            firstStopCheck,
                            STOP_CHECK_INTERVAL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                        try {
                            session.run(
                                mapOf("image" to imageTensor, "mask" to maskTensor),
                                runOptions,
                            ).use { result ->
                                val tensor = result.get(0) as? OnnxTensor ?: return false
                                val buffer = tensor.floatBuffer ?: return false
                                if (buffer.remaining() != 3 * planeSize) return false
                                FloatArray(3 * planeSize).also(buffer::get)
                            }
                        } finally {
                            synchronized(timeoutGuard) {
                                timeoutActive = false
                                timeout?.cancel(false)
                            }
                        }
                    }
                }
            }
        }
        if (control.shouldStop()) return false
        if (output.any { !it.isFinite() }) return false

        for (localY in 0 until roiHeight) {
            if (control.shouldStop()) return false
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
        synchronized(inferenceLock) {
            session.close()
        }
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
        val ASSET_PATH: String = PinnedAotInpainter.descriptor.assetPath
        val REVISION: String = PinnedAotInpainter.descriptor.revision
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
        private const val STOP_CHECK_INTERVAL_MILLIS = 25L
        private val TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "masumi-aot-timeout").apply { isDaemon = true }
        }

        private fun createValidatedSession(
            environment: OrtEnvironment,
            modelBytes: ByteArray,
        ): OrtSession {
            val descriptor = PinnedAotInpainter.descriptor
            require(modelBytes.size.toLong() == descriptor.byteLength) { "AOT_MODEL_LENGTH_MISMATCH" }
            val digest = MessageDigest.getInstance("SHA-256").digest(modelBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            require(digest == descriptor.sha256) { "AOT_MODEL_HASH_MISMATCH" }
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(
                    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, MAX_INTRA_OP_THREADS),
                )
                environment.createSession(modelBytes, options)
            }
            try {
                session.inputInfo.requireFloatImageTensor("image", channels = 3)
                session.inputInfo.requireFloatImageTensor("mask", channels = 1)
                require(session.inputInfo.size == 2) { "AOT_MODEL_INPUT_SIGNATURE_MISMATCH" }
                require(session.outputInfo.size == 1) { "AOT_MODEL_OUTPUT_SIGNATURE_MISMATCH" }
                val output = session.outputInfo.values.single().info as? TensorInfo
                    ?: error("AOT_MODEL_OUTPUT_SIGNATURE_MISMATCH")
                require(output.type == OnnxJavaType.FLOAT && output.shape.isImageShape(3)) {
                    "AOT_MODEL_OUTPUT_SIGNATURE_MISMATCH"
                }
                return session
            } catch (failure: Throwable) {
                runCatching { session.close() }
                throw failure
            }
        }

        private fun Map<String, NodeInfo>.requireFloatImageTensor(name: String, channels: Long) {
            val tensor = get(name)?.info as? TensorInfo
                ?: error("AOT_MODEL_INPUT_SIGNATURE_MISMATCH")
            require(tensor.type == OnnxJavaType.FLOAT && tensor.shape.isImageShape(channels)) {
                "AOT_MODEL_INPUT_SIGNATURE_MISMATCH"
            }
        }

        private fun LongArray.isImageShape(channels: Long): Boolean =
            size == 4 && (this[0] == 1L || this[0] == -1L) && this[1] == channels &&
                this[2] != 0L && this[3] != 0L
    }
}
