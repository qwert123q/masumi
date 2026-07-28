package rs.masumi.app.cleanup

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupPreserveReason
import rs.masumi.core.cleanup.CleanupRegionArtifact
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.detection.PixelBox
import rs.masumi.app.pipeline.PipelineThreading

data class CleanupTarget(
    val translationRegionId: String,
    val ocrRegionId: String,
    val box: PixelBox,
    val strategy: CleanupStrategy,
    val expectedGlyphCount: Int? = null,
)

data class CleanedPage(
    val bitmap: Bitmap,
    val regions: List<CleanupRegionArtifact>,
)

class SourceCleanupEngine(
    private val neuralInpainter: NeuralInpainter? = null,
) {
    fun clean(
        source: Bitmap,
        targets: List<CleanupTarget>,
        policy: CleanupPolicy,
        cancellation: () -> Boolean = { false },
        recycleSourceAfterCopy: Boolean = false,
    ): CleanedPage {
        require(!source.isRecycled)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("source bitmap could not be copied")
        if (recycleSourceAfterCopy) source.recycle()
        try {
            val pixels = IntArray(Math.multiplyExact(output.width, output.height))
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            val artifacts = cleanTargets(pixels, output.width, output.height, targets, policy, cancellation)
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            return CleanedPage(output, artifacts)
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    /**
     * Targets whose regions of interest do not overlap read and write disjoint
     * pixels, so they run concurrently on one shared pixel array. Overlapping
     * targets are grouped and processed sequentially inside one task to keep
     * the output identical to a fully sequential pass.
     */
    private fun cleanTargets(
        pixels: IntArray,
        width: Int,
        height: Int,
        targets: List<CleanupTarget>,
        policy: CleanupPolicy,
        cancellation: () -> Boolean,
    ): List<CleanupRegionArtifact> {
        val artifacts = arrayOfNulls<CleanupRegionArtifact>(targets.size)
        val groups = groupByRoiOverlap(targets, policy, width, height)
        val parallelism = min(cleanupParallelism(), groups.size)
        if (parallelism <= 1) {
            targets.forEachIndexed { index, target ->
                if (cancellation()) throw CleanupCancellationSignal()
                artifacts[index] = cleanTarget(pixels, width, height, target, policy, cancellation)
            }
        } else {
            val executor = Executors.newFixedThreadPool(
                parallelism,
                PipelineThreading.factory(CLEANUP_WORKER_THREAD_NAME, numbered = true),
            )
            try {
                val futures = groups.map { group ->
                    executor.submit(
                        Callable {
                            group.forEach { index ->
                                if (cancellation()) throw CleanupCancellationSignal()
                                artifacts[index] =
                                    cleanTarget(pixels, width, height, targets[index], policy, cancellation)
                            }
                        },
                    )
                }
                futures.forEach { future ->
                    try {
                        future.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }
        return artifacts.map(::requireNotNull)
    }

    private fun cleanupParallelism(): Int =
        (Runtime.getRuntime().availableProcessors() - RESERVED_INTERACTIVE_PROCESSORS)
            .coerceIn(1, MAXIMUM_CLEANUP_WORKERS)

    private fun groupByRoiOverlap(
        targets: List<CleanupTarget>,
        policy: CleanupPolicy,
        width: Int,
        height: Int,
    ): List<List<Int>> {
        val rois = targets.map { target -> targetRoi(target, policy, width, height) }
        val parent = IntArray(targets.size) { it }
        fun find(value: Int): Int {
            var root = value
            while (parent[root] != root) root = parent[root]
            var current = value
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        for (first in targets.indices) {
            val firstRoi = rois[first] ?: continue
            for (second in first + 1 until targets.size) {
                val secondRoi = rois[second] ?: continue
                if (firstRoi.intersects(secondRoi)) parent[find(second)] = find(first)
            }
        }
        return targets.indices.groupBy(::find).values.toList()
    }

    private fun targetRoi(
        target: CleanupTarget,
        policy: CleanupPolicy,
        width: Int,
        height: Int,
    ): IntBox? {
        val core = target.box.toIntBox(width, height) ?: return null
        val padding = max(
            policy.minimumPaddingPixels,
            (max(core.width, core.height) * policy.boxPaddingFraction).roundToInt(),
        )
        return core.expand(padding, width, height)
    }

    private fun cleanTarget(
        pixels: IntArray,
        width: Int,
        height: Int,
        target: CleanupTarget,
        policy: CleanupPolicy,
        cancellation: () -> Boolean,
    ): CleanupRegionArtifact {
        val core = target.box.toIntBox(width, height)
            ?: return target.preserved(CleanupPreserveReason.MASK_EMPTY)
        val padding = max(
            policy.minimumPaddingPixels,
            (max(core.width, core.height) * policy.boxPaddingFraction).roundToInt(),
        )
        val roi = core.expand(padding, width, height)
        val roiPixelCount = roi.width * roi.height
        if (roiPixelCount <= 0) return target.preserved(CleanupPreserveReason.MASK_EMPTY)
        val background: Int
        val dilated: BooleanArray
        val useBoundaryInpaint: Boolean
        var relaxedGlyphSelection = false
        when (target.strategy) {
            CleanupStrategy.FLAT_LOCAL_FILL -> {
                // The box majority color is the ink itself whenever lettering
                // fills a tight detector box, so estimate the background from
                // the ROI perimeter: for bubble text that ring lies on the
                // bubble interior around the glyphs.
                //
                // Reverse-video panels (white lettering on a black balloon) are
                // the one case where that ring lies: the padded ROI spills onto
                // the page around the panel, the median comes back white, and
                // every mask downstream inverts. When the core itself is
                // clearly dark-dominated with light lettering on it, take the
                // background from the dark core pixels instead — the flat fill
                // then paints the light glyphs with the panel color.
                val perimeter = darkCoreBackground(pixels, width, core)
                    ?: estimatePerimeterMedian(pixels, width, roi)
                val flatMask = colorDifferenceMask(
                    pixels = pixels,
                    stride = width,
                    core = core,
                    roi = roi,
                    background = perimeter,
                    threshold = policy.colorDistanceThreshold,
                    cancellation = cancellation,
                )
                val corePixelCount = core.width * core.height
                val flatCoverage = flatMask.count { it }.toDouble() / corePixelCount
                val dilatedFlat = dilate(flatMask, roi.width, roi.height, policy.dilationRadiusPixels)
                val dilatedFlatCoreCoverage = coreCoverage(dilatedFlat, core, roi)
                if (
                    flatCoverage < SOLID_REGION_COVERAGE &&
                    !flatBackgroundIsTextured(pixels, width, core, roi, flatMask) &&
                    dilatedFlatCoreCoverage <= MAXIMUM_FLAT_COLOR_MASK_COVERAGE
                ) {
                    background = perimeter
                    dilated = dilatedFlat
                    useBoundaryInpaint = false
                } else {
                    // Colored, gradient, or halftone interiors make the flat
                    // color-difference mask meaningless (on full-color pages it
                    // routinely covers the whole box). Fall back to the
                    // glyph-shaped ink mask and let the texture-synthesizing
                    // inpainter reconstruct the background. Only when no glyph
                    // component exists either is the box treated as artwork.
                    background = perimeter
                    val ink = freeTextInkMask(
                        pixels,
                        width,
                        core,
                        roi,
                        background,
                        target.expectedGlyphCount,
                        cancellation,
                    )
                    if (ink.mask.none { it }) {
                        if (
                            flatCoverage >= SOLID_REGION_COVERAGE ||
                            dilatedFlatCoreCoverage > MAXIMUM_FLAT_COLOR_MASK_COVERAGE
                        ) {
                            // A color-difference mask that swallows nearly the
                            // whole box is not letter-shaped; with no glyph
                            // evidence either, repainting it would erase whatever
                            // art the box overlaps.
                            return target.preserved(
                                CleanupPreserveReason.MASK_UNSAFE,
                                roiPixelCount,
                                flatMask.count { it },
                            )
                        }
                        // No glyph component survived, yet the box holds
                        // letter-shaped foreground on a textured background (the
                        // only way here with a sane mask is the texture guard).
                        // Falling through to an empty mask used to leave the
                        // original lettering untouched, so reuse the
                        // color-difference mask and let the inpainter rebuild the
                        // texture. No glyph was vetted, so the plain core
                        // coverage cap stays in force.
                        dilated = dilatedFlat
                        useBoundaryInpaint = true
                    } else {
                        relaxedGlyphSelection = ink.relaxedSelection
                        dilated = dilate(ink.mask, roi.width, roi.height, freeTextDilationRadius(core, policy))
                        useBoundaryInpaint = true
                    }
                }
            }
            CleanupStrategy.LOCAL_BOUNDARY_INPAINT -> {
                background = estimatePerimeterMedian(pixels, width, roi)
                val ink = freeTextInkMask(
                    pixels,
                    width,
                    core,
                    roi,
                    background,
                    target.expectedGlyphCount,
                    cancellation,
                )
                relaxedGlyphSelection = ink.relaxedSelection
                dilated = dilate(ink.mask, roi.width, roi.height, freeTextDilationRadius(core, policy))
                useBoundaryInpaint = true
            }
        }
        val maskCount = dilated.count { it }
        var coreMaskCount = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            if (dilated[(y - roi.top) * roi.width + (x - roi.left)]) coreMaskCount += 1
        }
        val coverage = coreMaskCount.toDouble() / (core.width * core.height)
        if (maskCount == 0 || coverage < policy.minimumMaskCoverage) {
            return target.preserved(CleanupPreserveReason.MASK_EMPTY, roiPixelCount, maskCount)
        }
        // A vetted single dominant glyph legitimately fills its tight text box,
        // so judge safety by how much of the padded ROI stays available as
        // inpainting boundary instead of by core coverage.
        val coverageForLimit = if (useBoundaryInpaint && relaxedGlyphSelection) {
            maskCount.toDouble() / roiPixelCount
        } else {
            coverage
        }
        val maximumCoverage = when {
            !useBoundaryInpaint -> policy.maximumMaskCoverage
            relaxedGlyphSelection -> min(policy.maximumMaskCoverage, MAXIMUM_RELAXED_ROI_COVERAGE)
            else -> min(policy.maximumMaskCoverage, MAXIMUM_FREE_TEXT_MASK_COVERAGE)
        }
        if (coverageForLimit > maximumCoverage) {
            return target.preserved(CleanupPreserveReason.MASK_UNSAFE, roiPixelCount, maskCount)
        }
        val maskedLocals = IntArray(maskCount)
        val before = IntArray(maskCount)
        var maskedIndex = 0
        for (local in dilated.indices) {
            if (!dilated[local]) continue
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            maskedLocals[maskedIndex] = local
            before[maskedIndex] = pixels[y * width + x]
            maskedIndex += 1
        }
        if (useBoundaryInpaint) {
            val inpainter = neuralInpainter
            val neuralApplied = inpainter != null && runCatching {
                inpainter.inpaint(
                    pixels = pixels,
                    pageWidth = width,
                    pageHeight = height,
                    roiLeft = roi.left,
                    roiTop = roi.top,
                    roiRight = roi.right,
                    roiBottom = roi.bottom,
                    roiMask = dilated,
                )
            }.getOrDefault(false)
            if (!neuralApplied) inpaintBidirectional(pixels, width, roi, dilated, cancellation)
        } else {
            fillFlat(pixels, width, roi, dilated, background)
        }
        var changed = 0
        for (index in maskedLocals.indices) {
            val local = maskedLocals[index]
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            if (pixels[y * width + x] != before[index]) changed += 1
        }
        if (changed == 0) return target.preserved(CleanupPreserveReason.ENGINE_FAILED, roiPixelCount, maskCount)
        return CleanupRegionArtifact(
            translationRegionId = target.translationRegionId,
            ocrRegionId = target.ocrRegionId,
            box = target.box,
            strategy = target.strategy,
            state = CleanupRegionState.CLEANED,
            roiPixelCount = roiPixelCount,
            maskPixelCount = maskCount,
            changedPixelCount = changed,
        )
    }

    private fun colorDifferenceMask(
        pixels: IntArray,
        stride: Int,
        core: IntBox,
        roi: IntBox,
        background: Int,
        threshold: Int,
        cancellation: () -> Boolean,
    ): BooleanArray {
        val mask = BooleanArray(roi.width * roi.height)
        for (y in core.top until core.bottom) {
            if (cancellation()) throw CleanupCancellationSignal()
            for (x in core.left until core.right) {
                if (colorDistance(pixels[y * stride + x], background) >= threshold) {
                    mask[(y - roi.top) * roi.width + (x - roi.left)] = true
                }
            }
        }
        return mask
    }

    private fun freeTextDilationRadius(core: IntBox, policy: CleanupPolicy): Int = max(
        policy.dilationRadiusPixels,
        (min(core.width, core.height) * FREE_TEXT_DILATION_FRACTION)
            .roundToInt()
            .coerceIn(MINIMUM_FREE_TEXT_DILATION, MAXIMUM_FREE_TEXT_DILATION),
    )

    private class InkMaskResult(val mask: BooleanArray, val relaxedSelection: Boolean)

    /**
     * Flat filling is only safe when the bubble interior around the glyphs is
     * genuinely uniform. Measure the per-channel standard deviation of the
     * unmasked pixels inside the text box against their median: screentone and
     * art texture produce a high deviation even when the color-difference mask
     * misses it. Thresholds follow koharu's bubble-fill fast path.
     */
    private fun flatBackgroundIsTextured(
        pixels: IntArray,
        stride: Int,
        core: IntBox,
        roi: IntBox,
        mask: BooleanArray,
    ): Boolean {
        var count = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            if (!mask[(y - roi.top) * roi.width + (x - roi.left)]) count += 1
        }
        if (count == 0) return true
        val reds = IntArray(count)
        val greens = IntArray(count)
        val blues = IntArray(count)
        var index = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            if (mask[(y - roi.top) * roi.width + (x - roi.left)]) continue
            val color = pixels[y * stride + x]
            reds[index] = Color.red(color)
            greens[index] = Color.green(color)
            blues[index] = Color.blue(color)
            index += 1
        }
        fun deviation(values: IntArray): Double {
            values.sort()
            val median = values[values.size / 2].toDouble()
            var sumSquares = 0.0
            values.forEach { value ->
                val difference = value - median
                sumSquares += difference * difference
            }
            return sqrt(sumSquares / values.size)
        }
        val deviations = listOf(deviation(reds), deviation(greens), deviation(blues))
        val mean = deviations.average()
        val channelSpread = sqrt(deviations.sumOf { (it - mean) * (it - mean) } / deviations.size)
        val threshold = if (channelSpread > FLAT_CHANNEL_SPREAD_SWITCH) {
            FLAT_TEXTURE_DEVIATION_COLOR
        } else {
            FLAT_TEXTURE_DEVIATION_MONO
        }
        return deviations.max() >= threshold
    }

    private fun coreCoverage(mask: BooleanArray, core: IntBox, roi: IntBox): Double {
        var count = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            if (mask[(y - roi.top) * roi.width + (x - roi.left)]) count += 1
        }
        return count.toDouble() / (core.width * core.height)
    }

    /**
     * The median color of the dark core pixels when the box is a reverse-video
     * panel — dominated by dark background with light lettering on it — or
     * null for every ordinary light-background box. A box that is dark but has
     * no light content (a solid ink mass) also returns null: there is nothing
     * to erase there and the regular guards must keep treating it as artwork.
     */
    private fun darkCoreBackground(pixels: IntArray, stride: Int, core: IntBox): Int? {
        val corePixelCount = core.width * core.height
        if (corePixelCount == 0) return null
        var dark = 0
        var light = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            val value = luminance(pixels[y * stride + x])
            if (value < REVERSE_VIDEO_LUMINANCE) dark += 1
            if (value >= REVERSE_VIDEO_LIGHT_LUMINANCE) light += 1
        }
        if (dark.toDouble() / corePixelCount < REVERSE_VIDEO_MINIMUM_DARK_FRACTION) return null
        if (light.toDouble() / corePixelCount < REVERSE_VIDEO_MINIMUM_LIGHT_FRACTION) return null
        val reds = IntArray(dark)
        val greens = IntArray(dark)
        val blues = IntArray(dark)
        var index = 0
        for (y in core.top until core.bottom) for (x in core.left until core.right) {
            val color = pixels[y * stride + x]
            if (luminance(color) >= REVERSE_VIDEO_LUMINANCE) continue
            reds[index] = Color.red(color)
            greens[index] = Color.green(color)
            blues[index] = Color.blue(color)
            index += 1
        }
        reds.sort()
        greens.sort()
        blues.sort()
        return Color.rgb(reds[dark / 2], greens[dark / 2], blues[dark / 2])
    }

    /**
     * Free-standing manga lettering often sits over line art. Comparing every
     * pixel with one background color erases the illustration inside a large
     * rectangular detector box. Keep only compact, genuinely thick ink
     * components; nearby thin drawing strokes and large dark picture regions
     * are deliberately excluded. When the strict pass finds nothing and the
     * OCR text says the box holds only one or two glyphs, retry with a relaxed
     * size cap: a single oversized sound-effect glyph legitimately dominates
     * its box.
     */
    private fun freeTextInkMask(
        pixels: IntArray,
        stride: Int,
        core: IntBox,
        roi: IntBox,
        background: Int,
        expectedGlyphCount: Int?,
        cancellation: () -> Boolean,
    ): InkMaskResult {
        val corePixelCount = core.width * core.height
        val luminanceHistogram = IntArray(256)
        val luminances = IntArray(corePixelCount)
        var index = 0
        for (y in core.top until core.bottom) {
            if (cancellation()) throw CleanupCancellationSignal()
            for (x in core.left until core.right) {
                val value = luminance(pixels[y * stride + x])
                luminances[index++] = value
                luminanceHistogram[value] += 1
            }
        }
        // Ink is only ink relative to what it sits on: on a light bubble the
        // absolute ceiling lets grey and anti-aliased strokes through, but in a
        // box whose surroundings are themselves mid-tone, an unclamped Otsu cut
        // would declare half of the artwork "dark". Whatever Otsu picks must
        // keep clear contrast against the local background.
        val threshold = otsuThreshold(luminanceHistogram, corePixelCount)
            .coerceAtMost(luminance(background) - FREE_TEXT_BACKGROUND_CONTRAST)
            .coerceIn(MINIMUM_FREE_TEXT_LUMINANCE, MAXIMUM_FREE_TEXT_LUMINANCE)
        val dark = BooleanArray(corePixelCount) { luminances[it] <= threshold }
        val visited = BooleanArray(corePixelCount)
        val components = mutableListOf<InkComponent>()
        val queue = IntArray(corePixelCount)

        for (start in dark.indices) {
            if (!dark[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            val members = mutableListOf<Int>()
            var left = core.width
            var top = core.height
            var right = 0
            var bottom = 0
            var touchesBoundary = false
            while (head < tail) {
                val local = queue[head++]
                members += local
                val x = local % core.width
                val y = local / core.width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
                if (x == 0 || y == 0 || x == core.width - 1 || y == core.height - 1) {
                    touchesBoundary = true
                }
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until core.width || ny !in 0 until core.height) continue
                    val neighbor = ny * core.width + nx
                    if (dark[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }
            val memberArray = members.toIntArray()
            val thickPixelCount = memberArray.count { local ->
                darkNeighborCount(local, dark, core.width, core.height) >= THICK_INK_NEIGHBOR_COUNT
            }
            components += InkComponent(
                members = memberArray,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                thickPixelCount = thickPixelCount,
                touchesBoundary = touchesBoundary,
            )
        }

        val strictCandidates = components.filter { component ->
            component.isLikelyGlyph(corePixelCount, MAXIMUM_GLYPH_COMPONENT_FRACTION)
        }
        var relaxedSelection = false
        // The strict cap protects long lines of text from swallowing artwork.
        // When it finds nothing at all the box is either a short line or one
        // oversized display glyph, and giving up entirely leaves the original
        // Japanese lettering on the page. Always retry with the relaxed cap.
        val primaryCandidates = if (strictCandidates.isNotEmpty()) {
            strictCandidates
        } else if (expectedGlyphCount == null || expectedGlyphCount <= RELAXED_GLYPH_COUNT_LIMIT) {
            relaxedSelection = true
            components.filter { component ->
                component.isLikelyGlyph(corePixelCount, RELAXED_GLYPH_COMPONENT_FRACTION)
            }
        } else {
            emptyList()
        }
        if (primaryCandidates.isEmpty()) {
            return InkMaskResult(BooleanArray(roi.width * roi.height), false)
        }
        // A glyph is not a connected component. Japanese fonts commonly
        // render one character as several disconnected bold strokes, and the
        // previous one-component-per-OCR-codepoint limit left every component
        // after that count visible on the page. Keep a small bounded component
        // budget per expected glyph instead. The glyph-shape and area guards
        // above still reject the large artwork masses that share free-text
        // detector boxes.
        val primaryLimit = expectedGlyphCount
            ?.coerceAtLeast(1)
            ?.let { glyphCount ->
                min(
                    primaryCandidates.size.toLong(),
                    glyphCount.toLong() * MAXIMUM_COMPONENTS_PER_GLYPH,
                ).toInt()
            }
            ?: primaryCandidates.size
        val primary = primaryCandidates
            .sortedByDescending(InkComponent::thickPixelCount)
            .take(primaryLimit)
        val typicalExtent = primary.map { max(it.width, it.height) }.sorted()
            .let { it[it.size / 2] }
        val typicalArea = primary.map { it.members.size }.sorted()
            .let { it[it.size / 2] }
        val satelliteDistance = max(MINIMUM_SATELLITE_DISTANCE, typicalExtent / 2)
        val selected = components.filter { component ->
            component in primary ||
                component.isGlyphSatellite(
                    primary,
                    typicalExtent,
                    typicalArea,
                    satelliteDistance,
                )
        }
        return InkMaskResult(
            BooleanArray(roi.width * roi.height).also { mask ->
                selected.forEach { component ->
                    component.members.forEach { coreLocal ->
                        val x = core.left + coreLocal % core.width
                        val y = core.top + coreLocal / core.width
                        mask[(y - roi.top) * roi.width + (x - roi.left)] = true
                    }
                }
            },
            relaxedSelection,
        )
    }

    private fun InkComponent.isLikelyGlyph(corePixelCount: Int, componentFractionCap: Double): Boolean {
        if (members.size < MINIMUM_GLYPH_AREA || width < 2 || height < 2) return false
        val boxArea = width * height
        if (members.size.toDouble() / boxArea < MINIMUM_GLYPH_DENSITY) return false
        val aspect = max(width, height).toDouble() / min(width, height)
        if (aspect > MAXIMUM_GLYPH_ASPECT_RATIO) return false
        if (members.size.toDouble() / corePixelCount > componentFractionCap) return false
        if (thickPixelCount < max(1, members.size / MINIMUM_THICK_PIXEL_DIVISOR)) return false
        if (touchesBoundary &&
            members.size.toDouble() / corePixelCount > componentFractionCap * BOUNDARY_COMPONENT_FRACTION_RATIO
        ) {
            return false
        }
        return true
    }

    private fun InkComponent.isGlyphSatellite(
        primary: List<InkComponent>,
        typicalExtent: Int,
        typicalArea: Int,
        maximumDistance: Int,
    ): Boolean {
        if (touchesBoundary || members.isEmpty()) return false
        if (width > typicalExtent * MAXIMUM_SATELLITE_EXTENT_NUMERATOR /
            MAXIMUM_SATELLITE_EXTENT_DENOMINATOR ||
            height > typicalExtent * MAXIMUM_SATELLITE_EXTENT_NUMERATOR /
            MAXIMUM_SATELLITE_EXTENT_DENOMINATOR
        ) {
            return false
        }
        if (members.size > typicalArea * MAXIMUM_SATELLITE_AREA_NUMERATOR /
            MAXIMUM_SATELLITE_AREA_DENOMINATOR
        ) {
            return false
        }
        if (thickPixelCount.toDouble() / members.size < MINIMUM_SATELLITE_THICK_FRACTION) {
            return false
        }
        val aspect = max(width, height).toDouble() / min(width, height).coerceAtLeast(1)
        if (aspect > MAXIMUM_SATELLITE_ASPECT_RATIO) return false
        return primary.any { distanceTo(it) <= maximumDistance }
    }

    private fun darkNeighborCount(
        local: Int,
        dark: BooleanArray,
        width: Int,
        height: Int,
    ): Int {
        val x = local % width
        val y = local / width
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until width && ny in 0 until height && dark[ny * width + nx]) {
                count += 1
            }
        }
        return count
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        if (total <= 0) return MAXIMUM_FREE_TEXT_LUMINANCE
        var weightedTotal = 0L
        histogram.indices.forEach { weightedTotal += it.toLong() * histogram[it] }
        var backgroundWeight = 0
        var backgroundWeighted = 0L
        var bestVariance = -1.0
        var bestThreshold = MAXIMUM_FREE_TEXT_LUMINANCE
        histogram.indices.forEach { value ->
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0) return@forEach
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) return@forEach
            backgroundWeighted += value.toLong() * histogram[value]
            val backgroundMean = backgroundWeighted.toDouble() / backgroundWeight
            val foregroundMean = (weightedTotal - backgroundWeighted).toDouble() / foregroundWeight
            val difference = backgroundMean - foregroundMean
            val variance = backgroundWeight.toDouble() * foregroundWeight * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = value
            }
        }
        return bestThreshold
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun fillFlat(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        mask: BooleanArray,
        background: Int,
    ) {
        mask.indices.forEach { local ->
            if (!mask[local]) return@forEach
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            pixels[y * stride + x] = background
        }
    }

    /**
     * Manga display text usually has a bright outline around dark glyphs.
     * Diffusing inward from that outline creates large white scars. Interpolate
     * across each masked run, perpendicular to the reading flow, so samples
     * come from the actual illustration on both sides of the lettering.
     */
    private fun inpaintBidirectional(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        mask: BooleanArray,
        cancellation: () -> Boolean,
    ) {
        val replacement = IntArray(mask.size)
        val score = IntArray(mask.size) { Int.MAX_VALUE }
        for (localY in 0 until roi.height) {
            if (cancellation()) throw CleanupCancellationSignal()
            var x = 0
            while (x < roi.width) {
                val local = localY * roi.width + x
                if (!mask[local]) {
                    x += 1
                    continue
                }
                val start = x
                while (x < roi.width && mask[localY * roi.width + x]) x += 1
                horizontalRunCandidate(
                    pixels,
                    stride,
                    roi,
                    localY,
                    start,
                    x,
                    replacement,
                    score,
                )
            }
        }
        for (localX in 0 until roi.width) {
            if (cancellation()) throw CleanupCancellationSignal()
            var y = 0
            while (y < roi.height) {
                val local = y * roi.width + localX
                if (!mask[local]) {
                    y += 1
                    continue
                }
                val start = y
                while (y < roi.height && mask[y * roi.width + localX]) y += 1
                verticalRunCandidate(
                    pixels,
                    stride,
                    roi,
                    localX,
                    start,
                    y,
                    replacement,
                    score,
                )
            }
        }
        val fallback = estimatePerimeterMedian(pixels, stride, roi)
        mask.indices.forEach { local ->
            if (!mask[local]) return@forEach
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            pixels[y * stride + x] =
                if (score[local] == Int.MAX_VALUE) fallback else replacement[local]
        }
    }

    private fun horizontalRunCandidate(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        localY: Int,
        start: Int,
        endExclusive: Int,
        replacement: IntArray,
        score: IntArray,
    ) {
        val left = (start - 1).takeIf { it >= 0 }?.let { x ->
            pixels[(roi.top + localY) * stride + roi.left + x]
        }
        val right = endExclusive.takeIf { it < roi.width }?.let { x ->
            pixels[(roi.top + localY) * stride + roi.left + x]
        }
        if (left == null && right == null) return
        val candidateScore = boundaryScore(left, right, endExclusive - start)
        for (x in start until endExclusive) {
            val fraction = (x - start + 1).toDouble() / (endExclusive - start + 1)
            val local = localY * roi.width + x
            replacement[local] =
                if (left != null && right != null) blend(left, right, fraction) else requireNotNull(left ?: right)
            score[local] = candidateScore
        }
    }

    private fun verticalRunCandidate(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        localX: Int,
        start: Int,
        endExclusive: Int,
        replacement: IntArray,
        score: IntArray,
    ) {
        val top = (start - 1).takeIf { it >= 0 }?.let { y ->
            pixels[(roi.top + y) * stride + roi.left + localX]
        }
        val bottom = endExclusive.takeIf { it < roi.height }?.let { y ->
            pixels[(roi.top + y) * stride + roi.left + localX]
        }
        if (top == null && bottom == null) return
        val candidateScore = boundaryScore(top, bottom, endExclusive - start)
        for (y in start until endExclusive) {
            val local = y * roi.width + localX
            if (candidateScore >= score[local]) continue
            val fraction = (y - start + 1).toDouble() / (endExclusive - start + 1)
            replacement[local] =
                if (top != null && bottom != null) blend(top, bottom, fraction) else requireNotNull(top ?: bottom)
            score[local] = candidateScore
        }
    }

    private fun boundaryScore(first: Int?, second: Int?, span: Int): Int =
        if (first != null && second != null) {
            colorDistance(first, second) * BOUNDARY_COLOR_SCORE_WEIGHT + span
        } else {
            SINGLE_BOUNDARY_SCORE + span
        }

    private fun blend(first: Int, second: Int, fraction: Double): Int {
        fun component(start: Int, end: Int): Int =
            (start + (end - start) * fraction).roundToInt().coerceIn(0, 255)
        return Color.argb(
            component(Color.alpha(first), Color.alpha(second)),
            component(Color.red(first), Color.red(second)),
            component(Color.green(first), Color.green(second)),
            component(Color.blue(first), Color.blue(second)),
        )
    }

    /**
     * Approximate circular dilation via a two-pass chamfer-3-4 distance
     * transform: O(pixels) regardless of radius, versus the naive
     * O(pixels x radius^2) stamp which dominated free-text cleanup time.
     */
    private fun dilate(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return source
        val distance = IntArray(source.size) { if (source[it]) 0 else DISTANCE_INFINITY }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                var value = distance[index]
                if (value == 0) continue
                if (x > 0) value = min(value, distance[index - 1] + CHAMFER_ORTHOGONAL)
                if (y > 0) {
                    value = min(value, distance[index - width] + CHAMFER_ORTHOGONAL)
                    if (x > 0) value = min(value, distance[index - width - 1] + CHAMFER_DIAGONAL)
                    if (x < width - 1) value = min(value, distance[index - width + 1] + CHAMFER_DIAGONAL)
                }
                distance[index] = value
            }
        }
        val limit = radius * CHAMFER_ORTHOGONAL
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val index = row + x
                var value = distance[index]
                if (value == 0) continue
                if (x < width - 1) value = min(value, distance[index + 1] + CHAMFER_ORTHOGONAL)
                if (y < height - 1) {
                    value = min(value, distance[index + width] + CHAMFER_ORTHOGONAL)
                    if (x < width - 1) value = min(value, distance[index + width + 1] + CHAMFER_DIAGONAL)
                    if (x > 0) value = min(value, distance[index + width - 1] + CHAMFER_DIAGONAL)
                }
                distance[index] = value
            }
        }
        return BooleanArray(source.size) { distance[it] <= limit }
    }

    private fun estimatePerimeterMedian(pixels: IntArray, stride: Int, box: IntBox): Int {
        val colors = mutableListOf<Int>()
        for (x in box.left until box.right) {
            colors += pixels[box.top * stride + x]
            if (box.bottom - 1 != box.top) colors += pixels[(box.bottom - 1) * stride + x]
        }
        for (y in box.top + 1 until box.bottom - 1) {
            colors += pixels[y * stride + box.left]
            if (box.right - 1 != box.left) colors += pixels[y * stride + box.right - 1]
        }
        if (colors.isEmpty()) return Color.WHITE
        fun median(component: (Int) -> Int): Int = colors.map(component).sorted()[colors.size / 2]
        return Color.argb(median(Color::alpha), median(Color::red), median(Color::green), median(Color::blue))
    }

    private fun colorDistance(first: Int, second: Int): Int {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return sqrt((red * red + green * green + blue * blue).toDouble()).roundToInt()
    }

    private fun PixelBox.toIntBox(width: Int, height: Int): IntBox? {
        val left = floor(left).toInt().coerceIn(0, width)
        val top = floor(top).toInt().coerceIn(0, height)
        val right = ceil(right).toInt().coerceIn(0, width)
        val bottom = ceil(bottom).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        return IntBox(left, top, right, bottom)
    }

    private fun CleanupTarget.preserved(
        reason: CleanupPreserveReason,
        roiPixelCount: Int = 0,
        maskPixelCount: Int = 0,
    ) = CleanupRegionArtifact(
        translationRegionId = translationRegionId,
        ocrRegionId = ocrRegionId,
        box = box,
        strategy = strategy,
        state = CleanupRegionState.PRESERVED_SOURCE,
        preserveReason = reason,
        roiPixelCount = roiPixelCount,
        maskPixelCount = maskPixelCount,
    )

    private data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun expand(padding: Int, width: Int, height: Int) = IntBox(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (right + padding).coerceAtMost(width),
            (bottom + padding).coerceAtMost(height),
        )

        fun intersects(other: IntBox): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    private data class InkComponent(
        val members: IntArray,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val thickPixelCount: Int,
        val touchesBoundary: Boolean,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun distanceTo(other: InkComponent): Int {
            val horizontal = max(0, max(other.left - right, left - other.right))
            val vertical = max(0, max(other.top - bottom, top - other.bottom))
            return max(horizontal, vertical)
        }
    }

    private companion object {
        const val BOUNDARY_COLOR_SCORE_WEIGHT = 4
        const val SINGLE_BOUNDARY_SCORE = 10_000
        const val CLEANUP_WORKER_THREAD_NAME = "masumi-cleanup-worker"
        const val MAXIMUM_CLEANUP_WORKERS = 4
        const val RESERVED_INTERACTIVE_PROCESSORS = 2
        const val DISTANCE_INFINITY = Int.MAX_VALUE / 4
        const val CHAMFER_ORTHOGONAL = 3
        const val CHAMFER_DIAGONAL = 4
        const val SOLID_REGION_COVERAGE = 0.95
        // A detector box normally holds a handful of glyphs, so the relaxed pass
        // has to engage for short lines too, not only for one or two characters.
        const val RELAXED_GLYPH_COUNT_LIMIT = 32
        const val RELAXED_GLYPH_COMPONENT_FRACTION = 0.72
        const val MAXIMUM_RELAXED_ROI_COVERAGE = 0.95
        const val FLAT_CHANNEL_SPREAD_SWITCH = 1.0
        // Scanned pages are JPEG compressed, so the near-white bubble interior
        // around dark lettering always carries ringing noise. The old limits
        // classified almost every real bubble as textured and gave up on the
        // deterministic flat fill, which is the highest quality path we have.
        // The sample is pre-clipped by colorDistanceThreshold to the median
        // ±11 luminance levels, which bounds the attainable deviation at
        // ~15.6 on monochrome pages (±19 per channel, ~26.9, on color pages).
        // The thresholds must stay inside those ranges to keep the guard live.
        const val FLAT_TEXTURE_DEVIATION_COLOR = 16.0
        const val FLAT_TEXTURE_DEVIATION_MONO = 12.0
        const val MAXIMUM_FLAT_COLOR_MASK_COVERAGE = 0.88
        // A bubble core that is mostly this dark, yet still carries light
        // pixels, is white-on-black lettering rather than ink on paper.
        const val REVERSE_VIDEO_LUMINANCE = 64
        const val REVERSE_VIDEO_LIGHT_LUMINANCE = 128
        const val REVERSE_VIDEO_MINIMUM_DARK_FRACTION = 0.55
        const val REVERSE_VIDEO_MINIMUM_LIGHT_FRACTION = 0.02
        const val MINIMUM_FREE_TEXT_LUMINANCE = 32
        // Let the adaptive Otsu threshold stand for anti-aliased and grey ink
        // instead of clamping it down to a hard black-only cut-off. The
        // background-contrast ceiling below keeps this from classifying
        // mid-tone artwork as ink in boxes that hold no dark lettering.
        const val MAXIMUM_FREE_TEXT_LUMINANCE = 170
        const val FREE_TEXT_BACKGROUND_CONTRAST = 32
        const val THICK_INK_NEIGHBOR_COUNT = 6
        const val MINIMUM_GLYPH_AREA = 6
        const val MINIMUM_GLYPH_DENSITY = 0.10
        const val MAXIMUM_GLYPH_ASPECT_RATIO = 12.0
        const val MAXIMUM_COMPONENTS_PER_GLYPH = 4L
        // Deliberately strict: it keeps a large artwork mass sharing the box with
        // the lettering from being mistaken for the glyph. Tight boxes where the
        // glyphs legitimately dominate are handled by the relaxed retry below,
        // which only runs once this pass has found nothing at all.
        const val MAXIMUM_GLYPH_COMPONENT_FRACTION = 0.08
        // Tight boxes mean glyphs routinely touch the border, so a flat cutoff
        // cannot work: anything below the strict fraction cap would be dead
        // code there, anything above lets art bleed in. A border-touching
        // component instead has to stay clearly smaller than the largest
        // interior component the same pass would accept.
        const val BOUNDARY_COMPONENT_FRACTION_RATIO = 0.5
        const val MINIMUM_THICK_PIXEL_DIVISOR = 80
        const val MINIMUM_SATELLITE_DISTANCE = 3
        const val MAXIMUM_SATELLITE_EXTENT_NUMERATOR = 4
        const val MAXIMUM_SATELLITE_EXTENT_DENOMINATOR = 5
        const val MAXIMUM_SATELLITE_AREA_NUMERATOR = 1
        const val MAXIMUM_SATELLITE_AREA_DENOMINATOR = 2
        const val MINIMUM_SATELLITE_THICK_FRACTION = 0.15
        const val MAXIMUM_SATELLITE_ASPECT_RATIO = 8.0
        const val FREE_TEXT_DILATION_FRACTION = 0.025
        const val MINIMUM_FREE_TEXT_DILATION = 3
        const val MAXIMUM_FREE_TEXT_DILATION = 12
        const val MAXIMUM_FREE_TEXT_MASK_COVERAGE = 0.92
    }
}

class CleanupCancellationSignal : RuntimeException()
