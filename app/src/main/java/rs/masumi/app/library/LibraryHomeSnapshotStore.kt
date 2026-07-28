package rs.masumi.app.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class LibraryHomeSnapshot(
    val rootDisplayName: String?,
    val projects: List<MangaLibraryProject>,
)

/**
 * A small metadata-only shelf snapshot. It makes a cold library launch useful
 * immediately while the Storage Access Framework tree is reconciled in the
 * background. Source images and provider credentials never enter this cache.
 */
internal class LibraryHomeSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(rootUri: Uri): LibraryHomeSnapshot? = runCatching {
        val root = JSONObject(requireNotNull(preferences.getString(KEY_SNAPSHOT, null)))
        require(root.getInt(JSON_VERSION) == SNAPSHOT_VERSION)
        require(root.getString(JSON_ROOT_URI) == rootUri.toString())
        val values = root.getJSONArray(JSON_PROJECTS)
        require(values.length() <= MAXIMUM_PROJECTS)
        val projects = buildList(values.length()) {
            repeat(values.length()) { index ->
                add(decodeProject(values.getJSONObject(index)))
            }
        }
        LibraryHomeSnapshot(
            rootDisplayName = root.optString(JSON_ROOT_NAME).takeIf(String::isNotBlank),
            projects = projects,
        )
    }.getOrNull()

    fun save(rootUri: Uri, rootDisplayName: String?, projects: List<MangaLibraryProject>) {
        val encoded = JSONObject()
            .put(JSON_VERSION, SNAPSHOT_VERSION)
            .put(JSON_ROOT_URI, rootUri.toString())
            .put(JSON_ROOT_NAME, rootDisplayName)
            .put(
                JSON_PROJECTS,
                JSONArray().apply {
                    projects.take(MAXIMUM_PROJECTS).forEach { put(encodeProject(it)) }
                },
            )
            .toString()
        preferences.edit().putString(KEY_SNAPSHOT, encoded).apply()
    }

    private fun encodeProject(project: MangaLibraryProject): JSONObject = JSONObject()
        .put(JSON_SCHEMA_VERSION, project.metadata.schemaVersion)
        .put(JSON_PROJECT_ID, project.metadata.projectId)
        .put(JSON_TITLE, project.metadata.title)
        .put(JSON_CREATED_AT, project.metadata.createdAtEpochMillis)
        .put(JSON_SOURCE_TREE_URI, project.metadata.sourceTreeUri)
        .put(JSON_SOURCE_FINGERPRINT, project.metadata.sourceFingerprint)
        .put(JSON_DIRECTORY_URI, project.directoryUri.toString())
        .put(JSON_SOURCE_DIRECTORY_URI, project.sourceDirectoryUri?.toString())
        .put(JSON_OUTPUT_DIRECTORY_URI, project.outputDirectoryUri?.toString())
        .put(JSON_OUTPUT_PAGE_COUNT, project.outputPageCount)

    private fun decodeProject(value: JSONObject): MangaLibraryProject {
        val projectId = value.getString(JSON_PROJECT_ID)
        val title = value.getString(JSON_TITLE)
        val fingerprint = value.getString(JSON_SOURCE_FINGERPRINT)
        val outputPageCount = value.getInt(JSON_OUTPUT_PAGE_COUNT)
        require(SAFE_PROJECT_ID.matches(projectId))
        require(title.isNotBlank() && title.length <= MAXIMUM_TITLE_LENGTH)
        require(SHA256.matches(fingerprint))
        require(outputPageCount in 0..MAXIMUM_PAGE_COUNT)
        val directory = contentUri(value.getString(JSON_DIRECTORY_URI))
        return MangaLibraryProject(
            metadata = MangaLibraryProjectMetadata(
                schemaVersion = value.getInt(JSON_SCHEMA_VERSION),
                projectId = projectId,
                title = title,
                createdAtEpochMillis = value.getLong(JSON_CREATED_AT),
                sourceTreeUri = value.getString(JSON_SOURCE_TREE_URI),
                sourceFingerprint = fingerprint,
            ),
            directoryUri = directory,
            mangaDirectoryUri = directory,
            sourceDirectoryUri = nullableContentUri(value, JSON_SOURCE_DIRECTORY_URI),
            outputDirectoryUri = nullableContentUri(value, JSON_OUTPUT_DIRECTORY_URI),
            outputPageCount = outputPageCount,
        )
    }

    private fun contentUri(raw: String): Uri = Uri.parse(raw).also {
        require(it.scheme == "content")
    }

    private fun nullableContentUri(value: JSONObject, key: String): Uri? =
        if (value.isNull(key)) null else contentUri(value.getString(key))

    private companion object {
        const val PREFERENCES_NAME = "library_home_snapshot"
        const val KEY_SNAPSHOT = "snapshot_v1"
        const val SNAPSHOT_VERSION = 1
        const val MAXIMUM_PROJECTS = 500
        const val MAXIMUM_PAGE_COUNT = 100_000
        const val MAXIMUM_TITLE_LENGTH = 80
        const val JSON_VERSION = "version"
        const val JSON_ROOT_URI = "root_uri"
        const val JSON_ROOT_NAME = "root_name"
        const val JSON_PROJECTS = "projects"
        const val JSON_SCHEMA_VERSION = "schema_version"
        const val JSON_PROJECT_ID = "project_id"
        const val JSON_TITLE = "title"
        const val JSON_CREATED_AT = "created_at"
        const val JSON_SOURCE_TREE_URI = "source_tree_uri"
        const val JSON_SOURCE_FINGERPRINT = "source_fingerprint"
        const val JSON_DIRECTORY_URI = "directory_uri"
        const val JSON_SOURCE_DIRECTORY_URI = "source_directory_uri"
        const val JSON_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val JSON_OUTPUT_PAGE_COUNT = "output_page_count"
        val SAFE_PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
