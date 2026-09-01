package rs.masumi.app.library

/** Naming contract for complete, reader-visible output generations. */
internal object OutputGeneration {
    const val PUBLISHED_PREFIX = "masumi-generation-"
    const val STAGING_PREFIX = ".masumi-staging-"

    fun publishedName(startedAtEpochMillis: Long, exportKey: String): String {
        require(startedAtEpochMillis >= 0L)
        require(SHA256.matches(exportKey))
        return "$PUBLISHED_PREFIX${startedAtEpochMillis.toString().padStart(13, '0')}-${exportKey.take(16)}"
    }

    fun isPublishedDirectory(name: String): Boolean = PUBLISHED_DIRECTORY.matches(name)

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val PUBLISHED_DIRECTORY = Regex("${Regex.escape(PUBLISHED_PREFIX)}[0-9]{13}-[0-9a-f]{16}")
}
