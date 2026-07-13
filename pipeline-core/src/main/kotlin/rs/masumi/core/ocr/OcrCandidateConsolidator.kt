package rs.masumi.core.ocr

import kotlin.math.max
import kotlin.math.min
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.detection.PixelBox

class OcrCandidateConsolidator(
    private val config: OcrConsolidationConfig = OcrConsolidationConfig(),
    private val readingOrderConfig: OcrReadingOrderConfig = OcrReadingOrderConfig(),
) {
    fun consolidate(
        detectionPage: PageDetectionArtifact,
        bubbles: List<DetectedRegion> = detectionPage.bubbleCandidates,
        textRegions: List<DetectedRegion> = detectionPage.textRegions,
    ): List<OcrCandidate> {
        val acceptedText = textRegions
            .filter { it.detectorClass == DetectorClass.TEXT_IN_BUBBLE || it.detectorClass == DetectorClass.TEXT_FREE }
            .sortedBy(DetectedRegion::regionId)
        val clusters = cluster(acceptedText) { first, second -> areDuplicate(first, second) }
        val candidates = clusters.map { members ->
            createCandidate(detectionPage, members, bubbles)
        }
        return orderForJapaneseReading(candidates).mapIndexed { rank, candidate ->
            candidate.copy(readingOrderRank = rank)
        }
    }

    private fun areDuplicate(first: DetectedRegion, second: DetectedRegion): Boolean {
        val sameClass = first.detectorClass == second.detectorClass
        val intersection = intersectionArea(first.box, second.box)
        if (intersection <= 0.0) return false
        val smallerCoverage = intersection / min(area(first.box), area(second.box))
        return if (sameClass) {
            iou(first.box, second.box) >= config.sameClassIouThreshold ||
                smallerCoverage >= config.sameClassContainmentThreshold
        } else {
            smallerCoverage >= config.crossClassSmallerCoverageThreshold
        }
    }

    private fun createCandidate(
        page: PageDetectionArtifact,
        members: List<DetectedRegion>,
        bubbles: List<DetectedRegion>,
    ): OcrCandidate {
        val sourceIds = members.map(DetectedRegion::regionId).sorted()
        val containingMembers = members.filter { candidate ->
            members.all { other -> coverageOf(other.box, candidate.box) >= FULL_CONTAINMENT_TOLERANCE }
        }
        val representative = (containingMembers.ifEmpty { members })
            .sortedWith(compareByDescending<DetectedRegion> { it.confidence }.thenBy { it.regionId })
            .first()
        val candidateBox = if (containingMembers.isNotEmpty()) {
            representative.box
        } else {
            members.map(DetectedRegion::box).reduce(::union)
        }
        val sourceClass = if (members.any { it.detectorClass == DetectorClass.TEXT_IN_BUBBLE }) {
            DetectorClass.TEXT_IN_BUBBLE
        } else {
            DetectorClass.TEXT_FREE
        }
        val semantic = if (sourceClass == DetectorClass.TEXT_IN_BUBBLE) {
            OcrSemanticStatus.REQUIRED_TEXT
        } else {
            OcrSemanticStatus.UNRESOLVED_FREE_TEXT
        }
        val association = bestBubble(candidateBox, bubbles)
        return OcrCandidate(
            ocrRegionId = OcrIdentity.regionId(page.pageId, page.pageArtifactKey, sourceIds, semantic),
            sourceRegionIds = sourceIds,
            representativeSourceRegionId = representative.regionId,
            sourceClass = sourceClass,
            detectorConfidence = members.maxOf(DetectedRegion::confidence),
            box = candidateBox,
            semanticStatus = semantic,
            protectionPolicy = if (semantic == OcrSemanticStatus.UNRESOLVED_FREE_TEXT) {
                OcrProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED
            } else {
                OcrProtectionPolicy.NONE
            },
            associatedBubbleRegionId = association?.regionId,
            associatedBubbleBox = association?.box,
            readingOrderRank = -1,
        )
    }

    private fun bestBubble(textBox: PixelBox, bubbles: List<DetectedRegion>): DetectedRegion? {
        val textArea = area(textBox)
        if (textArea <= 0.0) return null
        return bubbles
            .asSequence()
            .filter { it.detectorClass == DetectorClass.BUBBLE }
            .map { bubble -> bubble to (intersectionArea(textBox, bubble.box) / textArea) }
            .filter { (_, coverage) -> coverage >= config.bubbleAssociationCoverageThreshold }
            .sortedWith(
                compareByDescending<Pair<DetectedRegion, Double>> { it.second }
                    .thenByDescending { it.first.confidence }
                    .thenBy { it.first.regionId },
            )
            .firstOrNull()
            ?.first
    }

    private fun orderForJapaneseReading(candidates: List<OcrCandidate>): List<OcrCandidate> {
        val stableCandidates = candidates.sortedWith(
            compareBy<OcrCandidate> { it.box.top }
                .thenByDescending { it.box.right }
                .thenBy { it.ocrRegionId },
        )
        val bands = cluster(stableCandidates) { first, second ->
            verticalOverlapCoverage(first.box, second.box) >= readingOrderConfig.verticalOverlapThreshold
        }.sortedWith(
            compareBy<List<OcrCandidate>> { band -> band.minOf { it.box.top } }
                .thenByDescending { band -> band.maxOf { it.box.right } }
                .thenBy { band -> band.minOf { it.ocrRegionId } },
        )
        return bands.flatMap { band ->
            band.sortedWith(
                compareByDescending<OcrCandidate> { it.box.right }
                    .thenBy { it.box.top }
                    .thenBy { it.ocrRegionId },
            )
        }
    }

    private fun <T> cluster(items: List<T>, connected: (T, T) -> Boolean): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val parents = IntArray(items.size) { it }

        fun root(index: Int): Int {
            var cursor = index
            while (parents[cursor] != cursor) {
                parents[cursor] = parents[parents[cursor]]
                cursor = parents[cursor]
            }
            return cursor
        }

        fun union(first: Int, second: Int) {
            val firstRoot = root(first)
            val secondRoot = root(second)
            if (firstRoot != secondRoot) {
                parents[max(firstRoot, secondRoot)] = min(firstRoot, secondRoot)
            }
        }

        for (first in items.indices) {
            for (second in first + 1 until items.size) {
                if (connected(items[first], items[second])) union(first, second)
            }
        }
        return items.indices
            .groupBy(::root)
            .toSortedMap()
            .values
            .map { indexes -> indexes.map(items::get) }
    }

    private fun iou(first: PixelBox, second: PixelBox): Double {
        val intersection = intersectionArea(first, second)
        val union = area(first) + area(second) - intersection
        return if (union > 0.0) intersection / union else 0.0
    }

    private fun coverageOf(subject: PixelBox, container: PixelBox): Double {
        val subjectArea = area(subject)
        return if (subjectArea > 0.0) intersectionArea(subject, container) / subjectArea else 0.0
    }

    private fun verticalOverlapCoverage(first: PixelBox, second: PixelBox): Double {
        val overlap = (min(first.bottom, second.bottom) - max(first.top, second.top)).coerceAtLeast(0.0)
        val shorterHeight = min(first.bottom - first.top, second.bottom - second.top)
        return if (shorterHeight > 0.0) overlap / shorterHeight else 0.0
    }

    private fun intersectionArea(first: PixelBox, second: PixelBox): Double {
        val width = (min(first.right, second.right) - max(first.left, second.left)).coerceAtLeast(0.0)
        val height = (min(first.bottom, second.bottom) - max(first.top, second.top)).coerceAtLeast(0.0)
        return width * height
    }

    private fun area(box: PixelBox): Double =
        (box.right - box.left).coerceAtLeast(0.0) * (box.bottom - box.top).coerceAtLeast(0.0)

    private fun union(first: PixelBox, second: PixelBox): PixelBox = PixelBox(
        left = min(first.left, second.left),
        top = min(first.top, second.top),
        right = max(first.right, second.right),
        bottom = max(first.bottom, second.bottom),
    )

    private companion object {
        const val FULL_CONTAINMENT_TOLERANCE = 1.0 - 1e-9
    }
}
