package rs.masumi.core.ocr

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

data class OcrQualityDecision(
    val state: OcrRegionState,
    val selectedAttemptIndex: Int?,
    val quality: OcrQualityRecord,
)

class OcrQualityEvaluator(
    private val config: OcrQualityConfig = OcrQualityConfig(),
) {
    fun normalize(rawText: String): String {
        val normalizedNewlines = rawText.replace("\r\n", "\n").replace('\r', '\n').trim()
        val withoutFence = CODE_FENCE.matchEntire(normalizedNewlines)?.groupValues?.get(1)
            ?.trim()
            ?: normalizedNewlines
        return Normalizer.normalize(withoutFence, Normalizer.Form.NFC)
    }

    fun evaluate(
        detectorConfidence: Double,
        attempts: List<OcrAttemptArtifact>,
    ): OcrQualityDecision {
        require(attempts.isNotEmpty()) { "attempts must not be empty" }
        val evaluated = attempts.mapIndexed { index, attempt ->
            val normalizedText = normalize(attempt.rawText)
            EvaluatedAttempt(
                index = index,
                attempt = attempt,
                normalizedText = normalizedText,
                tokenProbability = geometricMean(attempt.tokenProbabilities),
                repeated = attempt.repetitionStopped || hasPathologicalRepetition(normalizedText),
                abnormalLength = normalizedText.codePointCount(0, normalizedText.length) >
                    MAX_REASONABLE_CODE_POINTS,
            )
        }
        val validNonEmpty = evaluated.filter(EvaluatedAttempt::isValidNonEmpty)
        val similarities = validNonEmpty.indices.flatMap { first ->
            (first + 1 until validNonEmpty.size).map { second ->
                similarity(validNonEmpty[first].normalizedText, validNonEmpty[second].normalizedText)
            }
        }
        val maximumSimilarity = similarities.maxOrNull()
        val selected = validNonEmpty.maxWithOrNull(
            compareBy<EvaluatedAttempt> { it.tokenProbability ?: Double.NEGATIVE_INFINITY }
                .thenBy { -it.index },
        )
        val cleanEmptyCount = evaluated.count { it.isCleanEmpty() }
        val hasAgreement = maximumSimilarity != null && maximumSimilarity >= config.agreementSimilarityThreshold
        val primary = evaluated.first()
        val primaryPasses = primary.isValidNonEmpty() &&
            (primary.tokenProbability ?: Double.NEGATIVE_INFINITY) >= config.primaryTokenProbabilityThreshold
        val state = when {
            validNonEmpty.isEmpty() && cleanEmptyCount >= config.emptyConfirmationAttemptCount -> {
                OcrRegionState.NO_TEXT_CONFIRMED
            }
            hasAgreement || primaryPasses -> OcrRegionState.RECOGNIZED
            else -> OcrRegionState.NEEDS_FALLBACK
        }
        val selectedForQuality = selected ?: evaluated.first()
        val quality = OcrQualityRecord(
            geometricMeanTokenProbability = selected?.tokenProbability,
            detectorConfidence = detectorConfidence,
            maximumAttemptSimilarity = maximumSimilarity,
            scriptCounts = scriptCounts(selected?.normalizedText.orEmpty()),
            emptyOutput = validNonEmpty.isEmpty(),
            repeatedUnit = evaluated.any(EvaluatedAttempt::repeated),
            forcedTruncation = evaluated.any { it.attempt.truncated },
            invalidUtf8 = evaluated.any { it.attempt.invalidUtf8 },
            abnormalLength = evaluated.any(EvaluatedAttempt::abnormalLength),
            aggregateScore = selected?.tokenProbability ?: maximumSimilarity,
            decisionReason = when {
                state == OcrRegionState.NO_TEXT_CONFIRMED -> "TWO_CLEAN_EMPTY_ATTEMPTS"
                hasAgreement -> "ATTEMPT_AGREEMENT"
                primaryPasses -> "PRIMARY_TOKEN_PROBABILITY"
                selectedForQuality.attempt.error != null -> "ATTEMPT_ERROR"
                else -> "QUALITY_BELOW_THRESHOLD"
            },
        )
        return OcrQualityDecision(
            state = state,
            selectedAttemptIndex = if (state == OcrRegionState.NO_TEXT_CONFIRMED) null else selected?.index,
            quality = quality,
        )
    }

    private fun geometricMean(probabilities: List<Double>): Double? {
        if (probabilities.isEmpty() || probabilities.any { !it.isFinite() || it <= 0.0 || it > 1.0 }) {
            return null
        }
        return exp(probabilities.sumOf(::ln) / probabilities.size)
    }

    private fun similarity(first: String, second: String): Double {
        val firstPoints = first.codePoints().toArray()
        val secondPoints = second.codePoints().toArray()
        val maximumLength = maxOf(firstPoints.size, secondPoints.size)
        if (maximumLength == 0) return 1.0
        var previous = IntArray(secondPoints.size + 1) { it }
        firstPoints.forEachIndexed { firstIndex, firstPoint ->
            val current = IntArray(secondPoints.size + 1)
            current[0] = firstIndex + 1
            secondPoints.forEachIndexed { secondIndex, secondPoint ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstPoint == secondPoint) 0 else 1,
                )
            }
            previous = current
        }
        return 1.0 - previous.last().toDouble() / maximumLength
    }

    private fun hasPathologicalRepetition(text: String): Boolean {
        val points = text.codePoints().toArray()
        if (points.size < MIN_REPETITION_CODE_POINTS) return false
        for (unitLength in 1..minOf(MAX_REPEATED_UNIT_CODE_POINTS, points.size / MIN_REPETITIONS)) {
            if (points.size % unitLength != 0 || points.size / unitLength < MIN_REPETITIONS) continue
            if (points.indices.all { points[it] == points[it % unitLength] }) return true
        }
        return false
    }

    private fun scriptCounts(text: String): Map<String, Int> = buildMap {
        text.codePoints().forEach { codePoint ->
            val name = Character.UnicodeScript.of(codePoint).name
            put(name, getOrDefault(name, 0) + 1)
        }
    }.toSortedMap()

    private data class EvaluatedAttempt(
        val index: Int,
        val attempt: OcrAttemptArtifact,
        val normalizedText: String,
        val tokenProbability: Double?,
        val repeated: Boolean,
        val abnormalLength: Boolean,
    ) {
        fun isValidNonEmpty(): Boolean = normalizedText.isNotEmpty() &&
            attempt.error == null &&
            !attempt.invalidUtf8 &&
            !attempt.truncated &&
            !repeated &&
            !abnormalLength

        fun isCleanEmpty(): Boolean = normalizedText.isEmpty() &&
            attempt.error == null &&
            !attempt.invalidUtf8 &&
            !attempt.truncated &&
            !repeated
    }

    private companion object {
        val CODE_FENCE = Regex("(?s)^```[^\\n]*\\n(.*)\\n```$")
        const val MAX_REASONABLE_CODE_POINTS = 4_096
        const val MIN_REPETITION_CODE_POINTS = 8
        const val MAX_REPEATED_UNIT_CODE_POINTS = 8
        const val MIN_REPETITIONS = 4
    }
}
