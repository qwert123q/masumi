package rs.masumi.core.detection

import kotlin.math.max
import kotlin.math.min

data class ModelQuery(
    val queryIndex: Int,
    val label: Long,
    val score: Float,
    val box: FloatArray,
)

data class ProcessedDetections(
    val rawQueries: List<RawQueryRecord>,
    val bubbles: List<DetectedRegion>,
    val textRegions: List<DetectedRegion>,
)

object DetectionPostProcessor {
    fun process(
        pageId: String,
        pageArtifactKey: String,
        pageWidth: Int,
        pageHeight: Int,
        thresholds: DetectionThresholdConfig,
        queries: List<ModelQuery>,
    ): ProcessedDetections {
        require(pageWidth > 0) { "pageWidth must be positive" }
        require(pageHeight > 0) { "pageHeight must be positive" }
        require(queries.size == EXPECTED_QUERY_COUNT) { "detector must return 300 queries" }
        require(queries.map { it.queryIndex }.sorted() == (0 until EXPECTED_QUERY_COUNT).toList()) {
            "detector query indexes must contain every value from 0 through 299 exactly once"
        }

        val rawQueries = ArrayList<RawQueryRecord>(EXPECTED_QUERY_COUNT)
        val bubbles = mutableListOf<DetectedRegion>()
        val textRegions = mutableListOf<DetectedRegion>()

        queries.sortedBy(ModelQuery::queryIndex).forEach { query ->
            require(query.box.size == BOX_COORDINATE_COUNT) { "detector box must contain four values" }
            val nonFiniteIndexes = query.box.indices.filter { !query.box[it].isFinite() }
            val rawBox = query.box.map { coordinate ->
                coordinate.takeIf(Float::isFinite)?.toDouble()
            }
            val detectorClass = query.label.toDetectorClass()
            val validation = when {
                !query.score.isFinite() -> RawQueryValidation.NON_FINITE_SCORE
                detectorClass == null -> RawQueryValidation.UNKNOWN_CLASS
                nonFiniteIndexes.isNotEmpty() -> RawQueryValidation.NON_FINITE_BOX
                query.score.toDouble() < thresholds.forClass(detectorClass) -> {
                    RawQueryValidation.BELOW_THRESHOLD
                }
                else -> null
            }

            if (validation != null) {
                rawQueries += query.toRawRecord(rawBox, nonFiniteIndexes, validation)
                return@forEach
            }

            val clipped = query.box.toClippedBox(pageWidth, pageHeight)
            if (clipped.right <= clipped.left || clipped.bottom <= clipped.top) {
                rawQueries += query.toRawRecord(
                    rawBox,
                    nonFiniteIndexes,
                    RawQueryValidation.EMPTY_AFTER_CLIP,
                )
                return@forEach
            }

            rawQueries += query.toRawRecord(rawBox, nonFiniteIndexes, RawQueryValidation.ACCEPTED)
            val acceptedClass = requireNotNull(detectorClass)
            val region = DetectedRegion(
                regionId = DetectionIdentity.regionId(
                    pageId = pageId,
                    pageArtifactKey = pageArtifactKey,
                    queryIndex = query.queryIndex,
                    detectorClass = acceptedClass,
                ),
                queryIndex = query.queryIndex,
                detectorClass = acceptedClass,
                confidence = query.score.toDouble(),
                box = clipped,
                semanticStatus = acceptedClass.semanticStatus(),
                protectionPolicy = acceptedClass.protectionPolicy(),
            )
            if (acceptedClass == DetectorClass.BUBBLE) {
                bubbles += region
            } else {
                textRegions += region
            }
        }

        return ProcessedDetections(
            rawQueries = rawQueries,
            bubbles = bubbles,
            textRegions = textRegions,
        )
    }

    private fun ModelQuery.toRawRecord(
        rawBox: List<Double?>,
        nonFiniteIndexes: List<Int>,
        validation: RawQueryValidation,
    ): RawQueryRecord = RawQueryRecord(
        queryIndex = queryIndex,
        label = label,
        score = score.takeIf(Float::isFinite)?.toDouble(),
        rawBox = rawBox,
        nonFiniteCoordinateIndexes = nonFiniteIndexes,
        validation = validation,
    )

    private fun FloatArray.toClippedBox(pageWidth: Int, pageHeight: Int): PixelBox {
        val left = min(this[0], this[2]).toDouble().coerceIn(0.0, pageWidth.toDouble())
        val top = min(this[1], this[3]).toDouble().coerceIn(0.0, pageHeight.toDouble())
        val right = max(this[0], this[2]).toDouble().coerceIn(0.0, pageWidth.toDouble())
        val bottom = max(this[1], this[3]).toDouble().coerceIn(0.0, pageHeight.toDouble())
        return PixelBox(left, top, right, bottom)
    }

    private fun Long.toDetectorClass(): DetectorClass? = when (this) {
        0L -> DetectorClass.BUBBLE
        1L -> DetectorClass.TEXT_IN_BUBBLE
        2L -> DetectorClass.TEXT_FREE
        else -> null
    }

    private fun DetectionThresholdConfig.forClass(detectorClass: DetectorClass): Double =
        when (detectorClass) {
            DetectorClass.BUBBLE -> bubble
            DetectorClass.TEXT_IN_BUBBLE -> textInBubble
            DetectorClass.TEXT_FREE -> textFree
        }

    private fun DetectorClass.semanticStatus(): RegionSemanticStatus = when (this) {
        DetectorClass.BUBBLE -> RegionSemanticStatus.BUBBLE_CANDIDATE
        DetectorClass.TEXT_IN_BUBBLE -> RegionSemanticStatus.TEXT_IN_BUBBLE
        DetectorClass.TEXT_FREE -> RegionSemanticStatus.UNRESOLVED_FREE_TEXT
    }

    private fun DetectorClass.protectionPolicy(): RegionProtectionPolicy = when (this) {
        DetectorClass.TEXT_FREE -> RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED
        DetectorClass.BUBBLE,
        DetectorClass.TEXT_IN_BUBBLE,
        -> RegionProtectionPolicy.NONE
    }

    private const val EXPECTED_QUERY_COUNT = 300
    private const val BOX_COORDINATE_COUNT = 4
}
