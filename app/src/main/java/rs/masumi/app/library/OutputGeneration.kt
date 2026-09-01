package rs.masumi.app.library

import rs.masumi.core.identity.SafeOpaqueId

/** Naming contract for complete, reader-visible output generations. */
internal object OutputGeneration {
    const val PUBLISHED_PREFIX = "masumi-generation-"
    const val STAGING_PREFIX = ".masumi-staging-"

    fun publishedName(startedAtEpochMillis: Long, exportKey: String): String {
        require(startedAtEpochMillis >= 0L)
        SafeOpaqueId.require(exportKey, "exportKey")
        return "$PUBLISHED_PREFIX${startedAtEpochMillis.toString().padStart(13, '0')}-${exportKey.take(16)}"
    }

    fun isPublishedDirectory(name: String): Boolean = PUBLISHED_DIRECTORY.matches(name)

    private val PUBLISHED_DIRECTORY = Regex(
        "${Regex.escape(PUBLISHED_PREFIX)}[0-9]{13}-[A-Za-z0-9][A-Za-z0-9._-]{0,15}",
    )
}
