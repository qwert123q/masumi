package rs.masumi.core.exporting

object ExportIdentity {
    fun outputName(
        pageOrder: Int,
        totalPageCount: Int,
        policy: ExportPolicy,
        imageExtension: String = "png",
    ): String {
        require(pageOrder >= 0 && totalPageCount > pageOrder)
        require(imageExtension in SUPPORTED_IMAGE_EXTENSIONS)
        val digits = maxOf(policy.minimumPageNumberDigits, totalPageCount.toString().length)
        return "${(pageOrder + 1).toString().padStart(digits, '0')}.$imageExtension"
    }

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "webp")
}
