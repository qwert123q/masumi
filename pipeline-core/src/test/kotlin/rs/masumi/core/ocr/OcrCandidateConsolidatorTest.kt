package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.DetectorModelRef
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.RegionProtectionPolicy
import rs.masumi.core.detection.RegionSemanticStatus
import rs.masumi.core.detection.VisibleOrientation

class OcrCandidateConsolidatorTest {
    @Test
    fun `same-class proposals merge at IoU threshold and keep sorted provenance`() {
        val first = region("z", DetectorClass.TEXT_IN_BUBBLE, PixelBox(0.0, 0.0, 100.0, 100.0), 0.8)
        val second = region("a", DetectorClass.TEXT_IN_BUBBLE, PixelBox(5.0, 5.0, 100.0, 100.0), 0.9)

        val result = OcrCandidateConsolidator().consolidate(page(text = listOf(first, second)))

        assertEquals(1, result.size)
        assertEquals(listOf("a", "z"), result.single().sourceRegionIds)
        assertEquals(first.box, result.single().box)
        assertEquals("z", result.single().representativeSourceRegionId)
    }

    @Test
    fun `same-class proposal contained at ninety percent merges despite low IoU`() {
        val outer = region("outer", DetectorClass.TEXT_FREE, PixelBox(0.0, 0.0, 100.0, 100.0), 0.8)
        val inner = region("inner", DetectorClass.TEXT_FREE, PixelBox(10.0, 10.0, 50.0, 50.0), 0.9)

        val result = OcrCandidateConsolidator().consolidate(page(text = listOf(outer, inner)))

        assertEquals(1, result.size)
        assertEquals(outer.box, result.single().box)
    }

    @Test
    fun `cross-class duplicate keeps in-bubble semantics and all provenance`() {
        val free = region("a", DetectorClass.TEXT_FREE, PixelBox(10.0, 10.0, 50.0, 90.0), 0.90)
        val inside = region("b", DetectorClass.TEXT_IN_BUBBLE, PixelBox(12.0, 12.0, 48.0, 88.0), 0.80)

        val result = OcrCandidateConsolidator().consolidate(page(text = listOf(free, inside)))

        assertEquals(1, result.size)
        assertEquals(listOf("a", "b"), result.single().sourceRegionIds)
        assertEquals(DetectorClass.TEXT_IN_BUBBLE, result.single().sourceClass)
        assertEquals(OcrSemanticStatus.REQUIRED_TEXT, result.single().semanticStatus)
        assertEquals(OcrProtectionPolicy.NONE, result.single().protectionPolicy)
    }

    @Test
    fun `nearby non-overlapping text is never merged`() {
        val result = OcrCandidateConsolidator().consolidate(
            page(
                text = listOf(
                    region("a", DetectorClass.TEXT_IN_BUBBLE, PixelBox(10.0, 10.0, 40.0, 60.0), 0.9),
                    region("b", DetectorClass.TEXT_IN_BUBBLE, PixelBox(42.0, 10.0, 72.0, 60.0), 0.9),
                ),
            ),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `bubble association requires half the text area and resolves ties stably`() {
        val text = region("text", DetectorClass.TEXT_FREE, PixelBox(20.0, 20.0, 60.0, 80.0), 0.9)
        val lowerId = region("bubble-a", DetectorClass.BUBBLE, PixelBox(10.0, 10.0, 50.0, 90.0), 0.8)
        val higherId = region("bubble-b", DetectorClass.BUBBLE, PixelBox(30.0, 10.0, 70.0, 90.0), 0.8)

        val associated = OcrCandidateConsolidator().consolidate(
            page(bubbles = listOf(higherId, lowerId), text = listOf(text)),
        ).single()

        assertEquals("bubble-a", associated.associatedBubbleRegionId)
        assertEquals(lowerId.box, associated.associatedBubbleBox)

        val insufficient = OcrCandidateConsolidator().consolidate(
            page(
                bubbles = listOf(
                    region("small", DetectorClass.BUBBLE, PixelBox(20.0, 20.0, 30.0, 30.0), 1.0),
                ),
                text = listOf(text),
            ),
        ).single()
        assertNull(insufficient.associatedBubbleRegionId)
    }

    @Test
    fun `reading order is right-to-left within bands then top-to-bottom`() {
        val left = region("left", DetectorClass.TEXT_IN_BUBBLE, PixelBox(10.0, 10.0, 30.0, 60.0), 0.9)
        val right = region("right", DetectorClass.TEXT_IN_BUBBLE, PixelBox(70.0, 20.0, 90.0, 70.0), 0.9)
        val lower = region("lower", DetectorClass.TEXT_IN_BUBBLE, PixelBox(70.0, 100.0, 90.0, 150.0), 0.9)

        val result = OcrCandidateConsolidator().consolidate(page(text = listOf(lower, left, right)))

        assertEquals(listOf("right", "left", "lower"), result.map { it.sourceRegionIds.single() })
        assertEquals(listOf(0, 1, 2), result.map { it.readingOrderRank })
    }

    @Test
    fun `only low-confidence narrow or edge free text is discarded using page-relative geometry`() {
        val result = OcrCandidateConsolidator().consolidate(
            page(
                width = 1_000,
                text = listOf(
                    region("narrow-low", DetectorClass.TEXT_FREE, PixelBox(300.0, 10.0, 349.0, 90.0), 0.49),
                    region("edge-low", DetectorClass.TEXT_FREE, PixelBox(0.0, 100.0, 80.0, 180.0), 0.49),
                    region("narrow-high", DetectorClass.TEXT_FREE, PixelBox(300.0, 200.0, 340.0, 280.0), 0.50),
                    region("interior-low", DetectorClass.TEXT_FREE, PixelBox(300.0, 300.0, 360.0, 380.0), 0.49),
                    region("required", DetectorClass.TEXT_IN_BUBBLE, PixelBox(0.0, 400.0, 40.0, 480.0), 0.10),
                ),
            ),
        )

        assertEquals(
            setOf("narrow-high", "interior-low", "required"),
            result.map { it.sourceRegionIds.single() }.toSet(),
        )
    }

    private fun page(
        bubbles: List<DetectedRegion> = emptyList(),
        text: List<DetectedRegion> = emptyList(),
        width: Int = 200,
    ) = PageDetectionArtifact(
        pageId = "page-1",
        pageArtifactKey = "detection-key",
        visibleWidth = width,
        visibleHeight = 300,
        orientation = VisibleOrientation.NORMAL,
        model = DetectorModelRef(
            modelId = "detector",
            repository = "example/detector",
            revision = "revision",
            fileName = "detector.onnx",
            byteLength = 1L,
            license = "Apache-2.0",
            opset = 17,
            runtimeRevision = "runtime",
        ),
        preprocessing = DetectionPreprocessingConfig(),
        thresholds = DetectionThresholdConfig(),
        rawQueries = emptyList(),
        bubbleCandidates = bubbles,
        textRegions = text,
    )

    private fun region(
        id: String,
        detectorClass: DetectorClass,
        box: PixelBox,
        confidence: Double,
    ) = DetectedRegion(
        regionId = id,
        queryIndex = id.hashCode(),
        detectorClass = detectorClass,
        confidence = confidence,
        box = box,
        semanticStatus = when (detectorClass) {
            DetectorClass.BUBBLE -> RegionSemanticStatus.BUBBLE_CANDIDATE
            DetectorClass.TEXT_IN_BUBBLE -> RegionSemanticStatus.TEXT_IN_BUBBLE
            DetectorClass.TEXT_FREE -> RegionSemanticStatus.UNRESOLVED_FREE_TEXT
        },
        protectionPolicy = if (detectorClass == DetectorClass.TEXT_FREE) {
            RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED
        } else {
            RegionProtectionPolicy.NONE
        },
    )
}
