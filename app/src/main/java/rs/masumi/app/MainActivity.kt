package rs.masumi.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import rs.masumi.core.importer.ProjectImportException
import rs.masumi.core.importer.ProjectImporter

class MainActivity : Activity() {
    private lateinit var importButton: Button
    private lateinit var importProgress: ProgressBar
    private lateinit var statusText: TextView
    private var importRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        importButton = findViewById(R.id.importButton)
        importProgress = findViewById(R.id.importProgress)
        statusText = findViewById(R.id.statusText)
        importButton.setOnClickListener { openChapterFolder() }
    }

    @Deprecated("Uses the platform result API to keep the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_CHAPTER || resultCode != RESULT_OK) return

        val treeUri = data?.data ?: return
        retainReadPermission(treeUri, data.flags)
        importChapter(treeUri)
    }

    private fun openChapterFolder() {
        if (importRunning) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_CHAPTER)
    }

    private fun retainReadPermission(treeUri: Uri, resultFlags: Int) {
        if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun importChapter(treeUri: Uri) {
        setImportRunning(true)
        statusText.setText(R.string.import_status_running)

        Thread({
            val result = runCatching {
                val sources = DocumentTreeReader(contentResolver).read(treeUri)
                ProjectImporter(filesDir.toPath().resolve("workspace")).importProject(sources)
            }

            runOnUiThread {
                result.fold(
                    onSuccess = { outcome ->
                        statusText.text = getString(
                            R.string.import_status_success,
                            outcome.report.importedCount,
                            outcome.report.byteCount,
                            "projects/${outcome.manifest.projectId}/reports/${outcome.report.jobId}.json",
                        )
                    },
                    onFailure = { failure ->
                        statusText.text = when (failure) {
                            is ProjectImportException -> getString(
                                R.string.import_status_failed,
                                failure.code.name,
                                failure.reportRelativePath,
                            )
                            else -> getString(R.string.import_status_failed_unknown)
                        }
                    },
                )
                setImportRunning(false)
            }
        }, IMPORT_THREAD_NAME).start()
    }

    private fun setImportRunning(running: Boolean) {
        importRunning = running
        importButton.isEnabled = !running
        importProgress.visibility = if (running) View.VISIBLE else View.GONE
    }

    private companion object {
        const val REQUEST_OPEN_CHAPTER = 1001
        const val IMPORT_THREAD_NAME = "masumi-import"
    }
}
