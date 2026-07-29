package rs.masumi.app.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal class MangaLibraryOrderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun orderedProjectIds(rootUri: Uri, defaultProjectIds: List<String>): List<String> =
        mergeLibraryOrder(defaultProjectIds, read(rootUri))

    fun save(rootUri: Uri, orderedProjectIds: List<String>) {
        val uniqueIds = orderedProjectIds
            .asSequence()
            .filter(SAFE_PROJECT_ID::matches)
            .distinct()
            .take(MAXIMUM_PROJECTS)
            .toList()
        val value = JSONObject()
            .put(JSON_VERSION, STORE_VERSION)
            .put(JSON_ROOT_URI, rootUri.toString())
            .put(JSON_PROJECT_IDS, JSONArray(uniqueIds))
            .toString()
        preferences.edit().putString(KEY_ORDER, value).apply()
    }

    private fun read(rootUri: Uri): List<String> = runCatching {
        val value = JSONObject(requireNotNull(preferences.getString(KEY_ORDER, null)))
        require(value.getInt(JSON_VERSION) == STORE_VERSION)
        require(value.getString(JSON_ROOT_URI) == rootUri.toString())
        val ids = value.getJSONArray(JSON_PROJECT_IDS)
        require(ids.length() <= MAXIMUM_PROJECTS)
        buildList(ids.length()) {
            repeat(ids.length()) { index ->
                ids.getString(index).takeIf(SAFE_PROJECT_ID::matches)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFERENCES_NAME = "manga_library_order"
        const val KEY_ORDER = "finished_order_v1"
        const val STORE_VERSION = 1
        const val MAXIMUM_PROJECTS = 500
        const val JSON_VERSION = "version"
        const val JSON_ROOT_URI = "root_uri"
        const val JSON_PROJECT_IDS = "project_ids"
        val SAFE_PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

internal fun mergeLibraryOrder(
    defaultProjectIds: List<String>,
    savedProjectIds: List<String>,
): List<String> {
    val defaults = defaultProjectIds.distinct()
    val available = defaults.toSet()
    val saved = savedProjectIds.distinct().filter(available::contains)
    val manuallyPositioned = saved.toSet()
    return defaults.filterNot(manuallyPositioned::contains) + saved
}
