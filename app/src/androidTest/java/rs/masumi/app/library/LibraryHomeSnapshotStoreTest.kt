package rs.masumi.app.library

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryHomeSnapshotStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun legacySnapshotFingerprintIsIgnoredAndNotWrittenAgain() {
        val root = Uri.parse("content://library/root")
        val project = JSONObject()
            .put("schema_version", 3)
            .put("project_id", "legacy-project")
            .put("title", "旧项目")
            .put("created_at", 123L)
            .put("source_tree_uri", "content://library/source")
            .put("source_fingerprint", "a".repeat(64))
            .put("directory_uri", "content://library/project")
            .put("source_directory_uri", JSONObject.NULL)
            .put("output_directory_uri", JSONObject.NULL)
            .put("output_page_count", 7)
        val snapshot = JSONObject()
            .put("version", 1)
            .put("root_uri", root.toString())
            .put("root_name", "Manga")
            .put("projects", JSONArray().put(project))
            .toString()
        preferences.edit().putString(KEY_SNAPSHOT, snapshot).commit()

        val store = LibraryHomeSnapshotStore(context)
        val loaded = requireNotNull(store.load(root))
        assertEquals("legacy-project", loaded.projects.single().metadata.projectId)

        store.save(root, loaded.rootDisplayName, loaded.projects)
        val rewritten = JSONObject(requireNotNull(preferences.getString(KEY_SNAPSHOT, null)))
            .getJSONArray("projects")
            .getJSONObject(0)
        assertNull(rewritten.opt("source_fingerprint"))
    }

    private companion object {
        const val PREFERENCES_NAME = "library_home_snapshot"
        const val KEY_SNAPSHOT = "snapshot_v1"
    }
}
