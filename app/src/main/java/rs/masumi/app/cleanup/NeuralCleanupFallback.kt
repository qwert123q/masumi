package rs.masumi.app.cleanup

/**
 * Keeps simple backgrounds on the deterministic path. Geometry alone is not
 * evidence of complexity; a precise audited residual on a textured background
 * is the only region-level eligibility signal available to cleanup.
 */
internal object NeuralCleanupFallback {
    fun isPotentiallyEligible(
        corePixelCount: Int,
        longestCoreSide: Int,
    ): Boolean =
        corePixelCount >= MINIMUM_CORE_PIXEL_COUNT &&
            longestCoreSide >= MINIMUM_LONGEST_CORE_SIDE

    fun isEligible(
        complexBackground: Boolean,
        residualPixelCount: Int,
        corePixelCount: Int,
        longestCoreSide: Int,
    ): Boolean =
        complexBackground &&
            residualPixelCount > 0 &&
            isPotentiallyEligible(corePixelCount, longestCoreSide)

    private const val MINIMUM_CORE_PIXEL_COUNT = 100_000
    private const val MINIMUM_LONGEST_CORE_SIDE = 1_200
}
