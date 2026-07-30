package rs.masumi.app.cleanup

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import rs.masumi.core.modelpackage.TextSegmenterModelSignature
import rs.masumi.core.modelpackage.TextSegmenterSignatureValidator

class ComicTextSegmenterException(
    val code: String,
    cause: Throwable? = null,
) : RuntimeException("Comic text segmenter failed: $code", cause)

class OnnxTextSegmenterSignatureValidator(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : TextSegmenterSignatureValidator {
    override fun validate(modelFile: Path): TextSegmenterModelSignature = try {
        createTextSegmenterSession(environment, modelFile).use(::validateTextSegmenterSignature)
    } catch (failure: Throwable) {
        throw ComicTextSegmenterException("MODEL_SIGNATURE_INVALID", failure)
    }
}

/**
 * Runs the comic-text-detector segmentation head once per source page. The
 * page keeps its aspect ratio and is zero-padded to 512x512. The compact
 * result stays in model
 * space and is sampled by cleanup regions through [TextProbabilityMask].
 */
class OnnxComicTextSegmenter(
    modelFile: Path,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : TextMaskProvider, AutoCloseable {
    private val session: OrtSession = try {
        createValidatedTextSegmenterSession(environment, modelFile)
    } catch (failure: Throwable) {
        throw ComicTextSegmenterException("SESSION_CREATE_FAILED", failure)
    }
    private val inferenceLock = Any()

    override fun predict(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        cancellation: () -> Boolean,
    ): TextProbabilityMask = try {
        require(pageWidth > 0 && pageHeight > 0)
        require(pixels.size == Math.multiplyExact(pageWidth, pageHeight))
        val scale = MODEL_SIDE.toDouble() / max(pageWidth, pageHeight)
        val validWidth = (pageWidth * scale).roundToInt().coerceIn(1, MODEL_SIDE)
        val validHeight = (pageHeight * scale).roundToInt().coerceIn(1, MODEL_SIDE)
        val input = prepareInput(
            pixels = pixels,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            validWidth = validWidth,
            validHeight = validHeight,
            cancellation = cancellation,
        )
        val probabilities = synchronized(inferenceLock) {
            OnnxTensor.createTensor(environment, input, INPUT_SHAPE).use { imageTensor ->
                session.run(
                    mapOf(INPUT_NAME to imageTensor),
                    setOf(OUTPUT_NAME),
                ).use { result ->
                    val tensor = result.get(OUTPUT_NAME).orElseThrow {
                        ComicTextSegmenterException("MISSING_OUTPUT")
                    } as? OnnxTensor ?: throw ComicTextSegmenterException("OUTPUT_TYPE")
                    val output = tensor.floatBuffer
                        ?: throw ComicTextSegmenterException("OUTPUT_BUFFER")
                    if (output.remaining() != MODEL_PIXEL_COUNT) {
                        throw ComicTextSegmenterException("OUTPUT_SHAPE")
                    }
                    ByteArray(MODEL_PIXEL_COUNT).also { values ->
                        values.indices.forEach { index ->
                            val probability = output.get()
                            if (!probability.isFinite()) {
                                throw ComicTextSegmenterException("OUTPUT_NONFINITE")
                            }
                            values[index] =
                                (probability.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                        }
                    }
                }
            }
        }
        if (cancellation()) throw CleanupCancellationSignal()
        TextProbabilityMask(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            modelWidth = MODEL_SIDE,
            modelHeight = MODEL_SIDE,
            validWidth = validWidth,
            validHeight = validHeight,
            values = probabilities,
        )
    } catch (failure: CleanupCancellationSignal) {
        throw failure
    } catch (failure: ComicTextSegmenterException) {
        throw failure
    } catch (failure: Throwable) {
        throw ComicTextSegmenterException("INFERENCE_FAILED", failure)
    }

    private fun prepareInput(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        validWidth: Int,
        validHeight: Int,
        cancellation: () -> Boolean,
    ): FloatBuffer {
        val buffer = ByteBuffer
            .allocateDirect(MODEL_PIXEL_COUNT * CHANNEL_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        repeat(CHANNEL_COUNT) { channel ->
            for (modelY in 0 until MODEL_SIDE) {
                if (cancellation()) throw CleanupCancellationSignal()
                for (modelX in 0 until MODEL_SIDE) {
                    if (modelX >= validWidth || modelY >= validHeight) {
                        buffer.put(0f)
                    } else {
                        buffer.put(
                            sampleChannel(
                                pixels = pixels,
                                pageWidth = pageWidth,
                                pageHeight = pageHeight,
                                x = (modelX + 0.5) * pageWidth / validWidth - 0.5,
                                y = (modelY + 0.5) * pageHeight / validHeight - 0.5,
                                shift = (2 - channel) * 8,
                            ) / 255f,
                        )
                    }
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun sampleChannel(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        x: Double,
        y: Double,
        shift: Int,
    ): Float {
        val sourceX = x.coerceIn(0.0, (pageWidth - 1).toDouble())
        val sourceY = y.coerceIn(0.0, (pageHeight - 1).toDouble())
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = ceil(sourceX).toInt().coerceAtMost(pageWidth - 1)
        val y1 = ceil(sourceY).toInt().coerceAtMost(pageHeight - 1)
        val fx = (sourceX - x0).toFloat()
        val fy = (sourceY - y0).toFloat()
        fun value(sampleX: Int, sampleY: Int): Float =
            ((pixels[sampleY * pageWidth + sampleX] ushr shift) and 0xff).toFloat()
        val top = value(x0, y0) * (1f - fx) + value(x1, y0) * fx
        val bottom = value(x0, y1) * (1f - fx) + value(x1, y1) * fx
        return top * (1f - fy) + bottom * fy
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val MODEL_SIDE = 512
        const val MODEL_PIXEL_COUNT = MODEL_SIDE * MODEL_SIDE
        const val CHANNEL_COUNT = 3
        val INPUT_SHAPE = longArrayOf(1, 3, MODEL_SIDE.toLong(), MODEL_SIDE.toLong())
    }
}

private fun createTextSegmenterSession(
    environment: OrtEnvironment,
    modelFile: Path,
): OrtSession = OrtSession.SessionOptions().use { options ->
    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    options.setIntraOpNumThreads(
        (Runtime.getRuntime().availableProcessors() - 2)
            .coerceIn(1, TEXT_SEGMENTER_MAX_INTRA_OP_THREADS),
    )
    environment.createSession(modelFile.toString(), options)
}

private fun createValidatedTextSegmenterSession(
    environment: OrtEnvironment,
    modelFile: Path,
): OrtSession {
    val session = createTextSegmenterSession(environment, modelFile)
    try {
        validateTextSegmenterSignature(session)
        return session
    } catch (failure: Throwable) {
        runCatching { session.close() }
        throw failure
    }
}

private fun validateTextSegmenterSignature(session: OrtSession): TextSegmenterModelSignature {
    val input = session.inputInfo.requireTextSegmenterTensor(INPUT_NAME)
    val output = session.outputInfo.requireTextSegmenterTensor(OUTPUT_NAME)
    requireTextSegmenterShape(input.shape, INPUT_SHAPE, INPUT_NAME)
    requireTextSegmenterShape(output.shape, OUTPUT_SHAPE, OUTPUT_NAME)
    return TextSegmenterModelSignature(
        inputName = INPUT_NAME,
        inputShape = INPUT_SHAPE.map { it.toInt() },
        outputName = OUTPUT_NAME,
        outputShape = OUTPUT_SHAPE.map { it.toInt() },
    )
}

private fun Map<String, NodeInfo>.requireTextSegmenterTensor(name: String): TensorInfo {
    val tensor = get(name)?.info as? TensorInfo
        ?: throw ComicTextSegmenterException("MISSING_TENSOR_$name")
    if (tensor.type != OnnxJavaType.FLOAT) {
        throw ComicTextSegmenterException("TENSOR_TYPE_$name")
    }
    return tensor
}

private fun requireTextSegmenterShape(actual: LongArray, expected: LongArray, name: String) {
    if (!actual.contentEquals(expected)) {
        throw ComicTextSegmenterException("TENSOR_SHAPE_$name")
    }
}

private const val INPUT_NAME = "images"
private const val OUTPUT_NAME = "seg"
private val INPUT_SHAPE = longArrayOf(1, 3, 512, 512)
private val OUTPUT_SHAPE = longArrayOf(1, 1, 512, 512)
private const val TEXT_SEGMENTER_MAX_INTRA_OP_THREADS = 6
