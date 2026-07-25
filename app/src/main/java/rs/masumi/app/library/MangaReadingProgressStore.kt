package rs.masumi.app.library

import android.content.Context

data class MangaReadingProgress(
    val pageIndex: Int,
    val pageCount: Int,
)

class MangaReadingProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(projectId: String): MangaReadingProgress? {
        if (!SAFE_ID.matches(projectId)) return null
        val pageCount = preferences.getInt("$projectId.pageCount", 0)
        if (pageCount <= 0) return null
        val pageIndex = preferences.getInt("$projectId.pageIndex", 0).coerceIn(0, pageCount - 1)
        return MangaReadingProgress(pageIndex, pageCount)
    }

    fun save(projectId: String, pageIndex: Int, pageCount: Int) {
        require(SAFE_ID.matches(projectId))
        require(pageCount > 0)
        require(pageIndex in 0 until pageCount)
        preferences.edit()
            .putInt("$projectId.pageIndex", pageIndex)
            .putInt("$projectId.pageCount", pageCount)
            .apply()
    }

    /** Japanese manga defaults to right-to-left page order. */
    fun readsRightToLeft(projectId: String): Boolean =
        !SAFE_ID.matches(projectId) || preferences.getBoolean("$projectId.rightToLeft", true)

    fun saveReadingDirection(projectId: String, rightToLeft: Boolean) {
        require(SAFE_ID.matches(projectId))
        preferences.edit().putBoolean("$projectId.rightToLeft", rightToLeft).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "manga_reading_progress"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
