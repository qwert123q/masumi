package rs.masumi.core.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectionPostProcessorTest {
    @Test
    fun `retains raw queries and separates accepted region classes`() {
        val queries = defaultQueries().toMutableList().apply {
            this[0] = query(0, 0, 0.8f, floatArrayOf(60f, 70f, -10f, -20f))
            this[1] = query(1, 1, 0.9f, floatArrayOf(10f, 11f, 20f, 21f))
            this[2] = query(2, 2, 0.7f, floatArrayOf(1f, 2f, 30f, 40f))
            this[3] = query(3, 99, 0.9f, floatArrayOf(1f, 2f, 3f, 4f))
            this[4] = query(4, 0, Float.NaN, floatArrayOf(1f, 2f, 3f, 4f))
            this[5] = query(5, 0, 0.9f, floatArrayOf(1f, Float.POSITIVE_INFINITY, 3f, 4f))
            this[6] = query(6, 0, 0.9f, floatArrayOf(-10f, -10f, -5f, -5f))
            this[7] = query(7, 0, 0.24f, floatArrayOf(1f, 2f, 3f, 4f))
        }

        val result = DetectionPostProcessor.process(
            pageId = "a".repeat(64),
            pageArtifactKey = "b".repeat(64),
            pageWidth = 50,
            pageHeight = 50,
            thresholds = DetectionThresholdConfig(),
            queries = queries,
        )

        assertEquals(300, result.rawQueries.size)
        assertEquals(listOf(0), result.bubbles.map { it.queryIndex })
        assertEquals(listOf(1, 2), result.textRegions.map { it.queryIndex })
        assertEquals(PixelBox(0.0, 0.0, 50.0, 50.0), result.bubbles.single().box)
        assertEquals(
            RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED,
            result.textRegions.last().protectionPolicy,
        )
        assertEquals(
            RegionSemanticStatus.UNRESOLVED_FREE_TEXT,
            result.textRegions.last().semanticStatus,
        )
        assertEquals(RawQueryValidation.UNKNOWN_CLASS, result.rawQueries[3].validation)
        assertEquals(RawQueryValidation.NON_FINITE_SCORE, result.rawQueries[4].validation)
        assertEquals(null, result.rawQueries[4].score)
        assertEquals(RawQueryValidation.NON_FINITE_BOX, result.rawQueries[5].validation)
        assertEquals(listOf(1), result.rawQueries[5].nonFiniteCoordinateIndexes)
        assertEquals(null, result.rawQueries[5].rawBox[1])
        assertEquals(RawQueryValidation.EMPTY_AFTER_CLIP, result.rawQueries[6].validation)
        assertEquals(RawQueryValidation.BELOW_THRESHOLD, result.rawQueries[7].validation)
    }

    @Test
    fun `requires exactly one query for every model query index`() {
        assertFailsWith<IllegalArgumentException> {
            DetectionPostProcessor.process(
                pageId = "a".repeat(64),
                pageArtifactKey = "b".repeat(64),
                pageWidth = 50,
                pageHeight = 50,
                thresholds = DetectionThresholdConfig(),
                queries = defaultQueries().dropLast(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DetectionPostProcessor.process(
                pageId = "a".repeat(64),
                pageArtifactKey = "b".repeat(64),
                pageWidth = 50,
                pageHeight = 50,
                thresholds = DetectionThresholdConfig(),
                queries = defaultQueries().toMutableList().apply { this[299] = this[298] },
            )
        }
    }

    private fun defaultQueries(): List<ModelQuery> = List(300) { index ->
        query(index, 0, 0f, floatArrayOf(1f, 2f, 3f, 4f))
    }

    private fun query(index: Int, label: Long, score: Float, box: FloatArray): ModelQuery =
        ModelQuery(index, label, score, box)
}
