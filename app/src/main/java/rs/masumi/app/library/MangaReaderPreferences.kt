package rs.masumi.app.library

import android.content.Context

/** Reader presentation preferences and durable, per-project continuous-reader anchors. */
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

    fun readingLocation(projectId: String): ReadingLocation {
        if (!SAFE_ID.matches(projectId)) return ReadingLocation.START
        return ReadingLocation.fromStored(preferences.getString("$projectId.location", null))
            ?: ReadingLocation(
                pageIndex = preferences.getInt("$projectId.pageIndex", 0),
                intraPageFraction = preferences.getFloat("$projectId.pageFraction", 0f),
            )
    }

    /** Commit instead of apply: this is called on lifecycle exit and must survive process death. */
    fun saveReadingLocation(projectId: String, location: ReadingLocation): Boolean {
        require(SAFE_ID.matches(projectId))
        return preferences.edit()
            .putString("$projectId.location", location.toStored())
            .putInt("$projectId.pageIndex", location.pageIndex)
            .putFloat("$projectId.pageFraction", location.intraPageFraction)
            .commit()
    }

    fun clearReadingLocation(projectId: String): Boolean {
        require(SAFE_ID.matches(projectId))
        return preferences.edit()
            .remove("$projectId.location")
            .remove("$projectId.pageIndex")
            .remove("$projectId.pageFraction")
            .commit()
    }

    fun remove(projectId: String) {
        require(SAFE_ID.matches(projectId))
        preferences.edit()
            .remove("$projectId.pageIndex")
            .remove("$projectId.pageCount")
            .remove("$projectId.pageFraction")
            .remove("$projectId.location")
            .remove("$projectId.continuous")
            .remove("$projectId.rightToLeft")
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "manga_reading_progress"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
