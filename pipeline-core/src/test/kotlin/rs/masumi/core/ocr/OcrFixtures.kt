package rs.masumi.core.ocr

import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation

internal object OcrFixtures {
    fun modelPackage() = OcrModelPackageRef(
        packageId = "paddleocr-vl-1.6-gguf",
        repository = "example/model",
        revision = "revision",
        model = OcrModelFileRef("model.gguf", 11L),
        projector = OcrModelFileRef("projector.gguf", 12L),
        license = "Apache-2.0",
    )

    fun dependencies() = OcrDependencies(
        modelPackage = modelPackage(),
        runtime = OcrRuntimeRef(
            llamaTag = "b8935",
            llamaCommit = "runtime-commit",
            abi = "arm64-v8a",
            backend = "vulkan-preferred-cpu-fallback",
            buildContract = "mtmd-vulkan-pref-t6-image-default-v1",
        ),
    )

    fun candidate() = OcrCandidate(
        ocrRegionId = "ocr-region-1",
        sourceRegionIds = listOf("source-region-1"),
        representativeSourceRegionId = "source-region-1",
        sourceClass = DetectorClass.TEXT_IN_BUBBLE,
        detectorConfidence = 0.91,
        box = PixelBox(10.0, 20.0, 70.0, 100.0),
        semanticStatus = OcrSemanticStatus.REQUIRED_TEXT,
        protectionPolicy = OcrProtectionPolicy.NONE,
        associatedBubbleRegionId = "bubble-1",
        associatedBubbleBox = PixelBox(5.0, 10.0, 80.0, 110.0),
        readingOrderRank = 0,
    )

    fun attempt(
        rawText: String = "縦書きです",
        normalizedText: String = rawText,
    ) = OcrAttemptArtifact(
        executionBackend = OcrExecutionBackend.VULKAN,
        strategy = OcrCropStrategy.PADDED_TEXT,
        cropBox = PixelBox(6.0, 12.0, 74.0, 108.0),
        rawText = rawText,
        normalizedText = normalizedText,
        tokenIds = listOf(11, 12, 13),
        tokenProbabilities = listOf(0.9, 0.8, 0.85),
        sourceWidth = 68,
        sourceHeight = 96,
        processedWidth = 448,
        processedHeight = 448,
        visualTokenCount = 64,
        generatedTokenCount = 3,
        reachedEos = true,
        truncated = false,
        repetitionStopped = false,
        invalidUtf8 = false,
        promptEvaluationMillis = 10L,
        generationMillis = 20L,
        error = null,
    )

    fun pageArtifact(
        state: OcrRegionState = OcrRegionState.RECOGNIZED,
        rawText: String = "縦書きです",
        normalizedText: String = rawText,
    ): PageOcrArtifact {
        val attempts = listOf(attempt(rawText, normalizedText))
        return PageOcrArtifact(
            pageId = "page-1",
            detectionPageArtifactKey = "detection-key",
            pageArtifactKey = "ocr-page-key",
            visibleWidth = 1200,
            visibleHeight = 1800,
            orientation = VisibleOrientation.NORMAL,
            dependencies = dependencies(),
            regions = listOf(
                OcrRegionArtifact(
                    candidate = candidate(),
                    attempts = attempts,
                    selectedAttemptIndex = 0,
                    quality = OcrQualityRecord(
                        geometricMeanTokenProbability = 0.85,
                        detectorConfidence = 0.91,
                        maximumAttemptSimilarity = null,
                        scriptCounts = mapOf("HAN" to 4, "HIRAGANA" to 2),
                        emptyOutput = false,
                        repeatedUnit = false,
                        forcedTruncation = false,
                        invalidUtf8 = false,
                        abnormalLength = false,
                        aggregateScore = 0.85,
                        decisionReason = "PRIMARY_TOKEN_PROBABILITY",
                    ),
                    state = state,
                    error = null,
                ),
            ),
        )
    }
}
