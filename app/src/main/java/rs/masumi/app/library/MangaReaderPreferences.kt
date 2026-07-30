package rs.masumi.app.library

import android.content.Context

/** Reader presentation preferences; the current page is deliberately transient. */
class MangaReaderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Continuous vertical scrolling is the default browsing mode. */
    fun readsContinuously(projectId: String): Boolean =
        !SAFE_ID.matches(projectId) || preferences.getBoolean("$projectId.continuous", true)

    fun saveReadingMode(projectId: String, continuous: Boolean) {
        require(SAFE_ID.matches(projectId))
        preferences.edit().putBoolean("$projectId.continuous", continuous).apply()
    }

    /** Japanese manga defaults to right-to-left page order. */
    fun readsRightToLeft(projectId: String): Boolean =
        !SAFE_ID.matches(projectId) || preferences.getBoolean("$projectId.rightToLeft", true)

    fun saveReadingDirection(projectId: String, rightToLeft: Boolean) {
        require(SAFE_ID.matches(projectId))
        preferences.edit().putBoolean("$projectId.rightToLeft", rightToLeft).apply()
    }

    fun remove(projectId: String) {
        require(SAFE_ID.matches(projectId))
        preferences.edit()
            // Remove legacy progress keys as well. New reader sessions always
            // open at page one and never recreate these values.
            .remove("$projectId.pageIndex")
            .remove("$projectId.pageCount")
            .remove("$projectId.continuous")
            .remove("$projectId.rightToLeft")
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "manga_reading_progress"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
