package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.ocr.OcrCandidate
import rs.masumi.core.ocr.OcrDependencies
import rs.masumi.core.ocr.OcrProtectionPolicy
import rs.masumi.core.ocr.OcrQualityRecord
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrSemanticStatus
import rs.masumi.core.ocr.PageOcrArtifact

@RunWith(AndroidJUnit4::class)
class OcrPreviewRendererTest {
    @Test
    fun previewKeepsSourceSizeAndDrawsEveryTerminalStateAndProtectionColor() {
        val page = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val before = IntArray(page.width * page.height).also {
            page.getPixels(it, 0, page.width, 0, 0, page.width, page.height)
        }
        val artifact = artifact(
            region(0, OcrRegionState.RECOGNIZED),
            region(1, OcrRegionState.NEEDS_FALLBACK),
            region(2, OcrRegionState.NO_TEXT_CONFIRMED),
            region(3, OcrRegionState.PRESERVED_SOURCE),
            region(
                4,
                OcrRegionState.RECOGNIZED,
                OcrProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED,
            ),
        )

        val bytes = OcrPreviewRenderer().renderPng(page, artifact)
        val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertEquals(page.width, preview.width)
        assertEquals(page.height, preview.height)
        val colors = IntArray(preview.width * preview.height).also {
            preview.getPixels(it, 0, preview.width, 0, 0, preview.width, preview.height)
        }.toSet()
        assertTrue(OcrPreviewRenderer.RECOGNIZED_COLOR in colors)
        assertTrue(OcrPreviewRenderer.NEEDS_FALLBACK_COLOR in colors)
        assertTrue(OcrPreviewRenderer.NO_TEXT_COLOR in colors)
        assertTrue(OcrPreviewRenderer.PRESERVED_COLOR in colors)
        assertTrue(OcrPreviewRenderer.PROTECTED_COLOR in colors)
        val after = IntArray(page.width * page.height).also {
            page.getPixels(it, 0, page.width, 0, 0, page.width, page.height)
        }
        assertArrayEquals(before, after)
        preview.recycle()
        page.recycle()
    }

    private fun artifact(vararg regions: OcrRegionArtifact): PageOcrArtifact {
        val descriptor = PinnedPaddleOcrVl.descriptor
        return PageOcrArtifact(
            pageId = "page",
            sourceSha256 = "a".repeat(64),
            detectionPageArtifactKey = "b".repeat(64),
            pageArtifactKey = "c".repeat(64),
            visibleWidth = 180,
            visibleHeight = 120,
            orientation = VisibleOrientation.NORMAL,
            dependencies = OcrDependencies(
                modelPackage = descriptor.toRef(),
                runtime = descriptor.runtime.toRef(),
            ),
            regions = regions.toList(),
        )
    }

    private fun region(
        index: Int,
        state: OcrRegionState,
        protectionPolicy: OcrProtectionPolicy = OcrProtectionPolicy.NONE,
    ): OcrRegionArtifact {
        val left = 8.0 + index * 34.0
        return OcrRegionArtifact(
            candidate = OcrCandidate(
                ocrRegionId = index.toString(16).padStart(64, '0'),
                sourceRegionIds = listOf("source-$index"),
                representativeSourceRegionId = "source-$index",
                sourceClass = if (protectionPolicy == OcrProtectionPolicy.NONE) {
                    DetectorClass.TEXT_IN_BUBBLE
                } else {
                    DetectorClass.TEXT_FREE
                },
                detectorConfidence = 0.8,
                box = PixelBox(left, 24.0, left + 24.0, 84.0),
                semanticStatus = if (protectionPolicy == OcrProtectionPolicy.NONE) {
                    OcrSemanticStatus.REQUIRED_TEXT
                } else {
                    OcrSemanticStatus.UNRESOLVED_FREE_TEXT
                },
                protectionPolicy = protectionPolicy,
                readingOrderRank = index,
            ),
            attempts = emptyList(),
            selectedAttemptIndex = null,
            quality = quality(),
            state = state,
        )
    }

    private fun quality(): OcrQualityRecord = OcrQualityRecord(
        geometricMeanTokenProbability = 0.8,
        detectorConfidence = 0.8,
        maximumAttemptSimilarity = 1.0,
        scriptCounts = emptyMap(),
        emptyOutput = false,
        repeatedUnit = false,
        forcedTruncation = false,
        invalidUtf8 = false,
        abnormalLength = false,
        aggregateScore = 0.8,
        decisionReason = "test",
    )
}
