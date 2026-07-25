package rs.masumi.app.detection

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import rs.masumi.core.detection.ModelQuery
import rs.masumi.core.modelpackage.DetectorModelSignature
import rs.masumi.core.modelpackage.ModelSignatureValidator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.nio.file.Path

class ComicDetectorException(
    val code: String,
    cause: Throwable? = null,
) : RuntimeException("Comic detector failed: $code", cause)

class OnnxComicDetectorFactory(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
    private val preprocessor: OnnxInputPreprocessor = OnnxInputPreprocessor(),
) : ComicDetectorFactory {
    override fun open(modelFile: Path): ComicDetector =
        OnnxComicDetector(modelFile, environment, preprocessor)
}

class OnnxModelSignatureValidator(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : ModelSignatureValidator {
    override fun validate(modelFile: Path): DetectorModelSignature = try {
        createSession(environment, modelFile).use(::validateSignature)
    } catch (failure: Throwable) {
        throw ComicDetectorException("MODEL_SIGNATURE_INVALID", failure)
    }
}

class OnnxComicDetector(
    modelFile: Path,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
    private val preprocessor: OnnxInputPreprocessor = OnnxInputPreprocessor(),
) : ComicDetector {
    private val session: OrtSession = try {
        createValidatedSession(environment, modelFile)
    } catch (failure: Throwable) {
        throw ComicDetectorException("SESSION_CREATE_FAILED", failure)
    }

    override fun detect(page: DecodedPage): List<ModelQuery> = try {
        val prepared = preprocessor.prepare(page.bitmap)
        val originalSizeBuffer = prepared.originalSizeValues.toDirectLongBuffer()
        OnnxTensor.createTensor(environment, prepared.imageBuffer, prepared.imageShape).use { imageTensor ->
            OnnxTensor.createTensor(
                environment,
                originalSizeBuffer,
                prepared.originalSizeShape,
            ).use { sizeTensor ->
                session.run(
                    mapOf(
                        IMAGE_INPUT to imageTensor,
                        SIZE_INPUT to sizeTensor,
                    ),
                ).use { result ->
                    result.toModelQueries()
                }
            }
        }
    } catch (failure: ComicDetectorException) {
        throw failure
    } catch (failure: Throwable) {
        throw ComicDetectorException("INFERENCE_FAILED", failure)
    }

    override fun close() {
        session.close()
    }

    private fun OrtSession.Result.toModelQueries(): List<ModelQuery> {
        @Suppress("UNCHECKED_CAST")
        val labels = requireOutput(LABELS_OUTPUT).value as? Array<LongArray>
            ?: throw ComicDetectorException("LABEL_OUTPUT_TYPE")
        @Suppress("UNCHECKED_CAST")
        val boxes = requireOutput(BOXES_OUTPUT).value as? Array<Array<FloatArray>>
            ?: throw ComicDetectorException("BOX_OUTPUT_TYPE")
        @Suppress("UNCHECKED_CAST")
        val scores = requireOutput(SCORES_OUTPUT).value as? Array<FloatArray>
            ?: throw ComicDetectorException("SCORE_OUTPUT_TYPE")

        if (labels.size != 1 || boxes.size != 1 || scores.size != 1) {
            throw ComicDetectorException("OUTPUT_BATCH_SIZE")
        }
        if (
            labels[0].size != QUERY_COUNT ||
            boxes[0].size != QUERY_COUNT ||
            scores[0].size != QUERY_COUNT
        ) {
            throw ComicDetectorException("OUTPUT_QUERY_COUNT")
        }

        return List(QUERY_COUNT) { index ->
            val box = boxes[0][index]
            if (box.size != BOX_COORDINATE_COUNT) {
                throw ComicDetectorException("OUTPUT_BOX_SHAPE")
            }
            ModelQuery(
                queryIndex = index,
                label = labels[0][index],
                score = scores[0][index],
                box = box.copyOf(),
            )
        }
    }

    private fun OrtSession.Result.requireOutput(name: String) = get(name).orElseThrow {
        ComicDetectorException("MISSING_OUTPUT_$name")
    }

    private fun LongArray.toDirectLongBuffer(): LongBuffer = ByteBuffer
        .allocateDirect(size * Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()
        .apply {
            put(this@toDirectLongBuffer)
            rewind()
        }

    private companion object {
        const val BOX_COORDINATE_COUNT = 4
    }
}

private fun createSession(environment: OrtEnvironment, modelFile: Path): OrtSession =
    OrtSession.SessionOptions().use { options ->
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Pin intra-op parallelism to the performance-core count. The default
        // spans every core, and scheduling shards onto the little cores makes
        // them the critical path for the 640x640 DETR pass.
        options.setIntraOpNumThreads(
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, DETECTOR_MAX_INTRA_OP_THREADS),
        )
        environment.createSession(modelFile.toString(), options)
    }

private fun createValidatedSession(environment: OrtEnvironment, modelFile: Path): OrtSession {
    val session = createSession(environment, modelFile)
    try {
        validateSignature(session)
        return session
    } catch (failure: Throwable) {
        runCatching { session.close() }
        throw failure
    }
}

private fun validateSignature(session: OrtSession): DetectorModelSignature {
    val image = session.inputInfo.requireTensor(IMAGE_INPUT, OnnxJavaType.FLOAT)
    val size = session.inputInfo.requireTensor(SIZE_INPUT, OnnxJavaType.INT64)
    val labels = session.outputInfo.requireTensor(LABELS_OUTPUT, OnnxJavaType.INT64)
    val boxes = session.outputInfo.requireTensor(BOXES_OUTPUT, OnnxJavaType.FLOAT)
    val scores = session.outputInfo.requireTensor(SCORES_OUTPUT, OnnxJavaType.FLOAT)

    requireShape(image.shape, listOf(null, 3, 640, 640), IMAGE_INPUT)
    requireShape(size.shape, listOf(null, 2), SIZE_INPUT)
    requireShape(labels.shape, listOf(null, QUERY_COUNT), LABELS_OUTPUT)
    requireShape(boxes.shape, listOf(null, QUERY_COUNT, 4), BOXES_OUTPUT)
    requireShape(scores.shape, listOf(null, QUERY_COUNT), SCORES_OUTPUT)

    return DetectorModelSignature(
        imageInputName = IMAGE_INPUT,
        imageInputShape = listOf(null, 3, 640, 640),
        sizeInputName = SIZE_INPUT,
        sizeInputShape = listOf(null, 2),
        outputNames = listOf(LABELS_OUTPUT, BOXES_OUTPUT, SCORES_OUTPUT),
        queryCount = QUERY_COUNT,
    )
}

private fun Map<String, NodeInfo>.requireTensor(name: String, type: OnnxJavaType): TensorInfo {
    val tensor = get(name)?.info as? TensorInfo
        ?: throw ComicDetectorException("MISSING_TENSOR_$name")
    if (tensor.type != type) throw ComicDetectorException("TENSOR_TYPE_$name")
    return tensor
}

private fun requireShape(actual: LongArray, expected: List<Int?>, name: String) {
    if (actual.size != expected.size) throw ComicDetectorException("TENSOR_RANK_$name")
    expected.forEachIndexed { index, dimension ->
        if (dimension != null && actual[index] != dimension.toLong()) {
            throw ComicDetectorException("TENSOR_SHAPE_$name")
        }
        if (dimension == null && actual[index] != -1L && actual[index] != 1L) {
            throw ComicDetectorException("TENSOR_BATCH_$name")
        }
    }
}

private const val IMAGE_INPUT = "images"
private const val SIZE_INPUT = "orig_target_sizes"
private const val LABELS_OUTPUT = "labels"
private const val BOXES_OUTPUT = "boxes"
private const val SCORES_OUTPUT = "scores"
private const val QUERY_COUNT = 300
private const val DETECTOR_MAX_INTRA_OP_THREADS = 6
