package rs.masumi.app.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import rs.masumi.app.MainActivity
import rs.masumi.app.R
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.core.quality.QualityArtifactStore
import rs.masumi.core.quality.QualityIssueCode
import rs.masumi.core.quality.QualityPageVerdict
import rs.masumi.core.typesetting.TypesettingPageState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class IssueReviewActivity : Activity() {
    private lateinit var imageView: ImageView
    private lateinit var indicatorView: TextView
    private lateinit var issueView: TextView
    private lateinit var toggleButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var projectId: String
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "masumi-issue-review") }
    private val generation = AtomicInteger()
    private var pages: List<ReviewPage> = emptyList()
    private var currentIndex = 0
    private var showOriginal = false
    private var displayedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_issue_review)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        if (!SAFE_ID.matches(projectId)) {
            finish()
            return
        }
        findViewById<TextView>(R.id.reviewTitle).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.review_title) }
        imageView = findViewById(R.id.reviewImage)
        indicatorView = findViewById(R.id.reviewIndicator)
        issueView = findViewById(R.id.reviewIssueDetail)
        toggleButton = findViewById(R.id.reviewToggle)
        previousButton = findViewById(R.id.reviewPrevious)
        nextButton = findViewById(R.id.reviewNext)
        findViewById<Button>(R.id.reviewBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.reviewOpenProject).setOnClickListener {
            startActivity(MainActivity.projectIntent(this, projectId, showDetails = true))
        }
        toggleButton.setOnClickListener {
            showOriginal = !showOriginal
            renderPage()
        }
        previousButton.setOnClickListener { showPage(currentIndex - 1) }
        nextButton.setOnClickListener { showPage(currentIndex + 1) }
        loadIssues()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        executor.shutdownNow()
        displayedBitmap?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }

    private fun loadIssues() {
        val request = generation.incrementAndGet()
        executor.execute {
            val loaded = runCatching {
                val catalog = ProjectCatalog(filesDir.toPath().resolve("workspace"))
                val project = requireNotNull(catalog.openProject(projectId))
                val quality = requireNotNull(catalog.latestPublishedQualityRun(projectId))
                val typesetting = requireNotNull(catalog.publishedTypesettingRun(
                    projectId,
                    quality.artifact.dependencies.typesettingRunArtifactKey,
                ))
                val qualityStore = QualityArtifactStore(project.directory)
                quality.artifact.entries
                    .filter { it.verdict != QualityPageVerdict.PASS }
                    .sortedBy { it.pageOrder }
                    .mapNotNull { entry ->
                        val qualityPage = qualityStore.readPublishedPage(quality.artifact.runArtifactKey, entry)
                            ?: return@mapNotNull null
                        val typesettingEntry = typesetting.artifact.entries
                            .singleOrNull { it.pageOrder == entry.pageOrder }
                        val finalPath = typesettingEntry
                            ?.takeIf { it.state == TypesettingPageState.COMMITTED }
                            ?.imagePath
                            ?.let { resolveInside(typesetting.directory, it) }
                        val originalPath = project.manifest.pages
                            .singleOrNull { it.order == entry.pageOrder }
                            ?.storedPath
                            ?.let { resolveInside(project.directory, it) }
                        ReviewPage(
                            pageOrder = entry.pageOrder,
                            originalPath = originalPath,
                            finalPath = finalPath ?: originalPath,
                            issueLabels = qualityPage.issues.map { issueLabel(it.code) }.distinct(),
                        )
                    }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (request != generation.get() || isFinishing || isDestroyed) return@runOnUiThread
                pages = loaded
                if (pages.isEmpty()) {
                    indicatorView.setText(R.string.review_empty)
                    issueView.setText(R.string.review_empty_description)
                    toggleButton.isEnabled = false
                    previousButton.isEnabled = false
                    nextButton.isEnabled = false
                } else {
                    showPage(0)
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        showOriginal = false
        renderPage()
    }

    private fun renderPage() {
        val page = pages.getOrNull(currentIndex) ?: return
        indicatorView.text = getString(
            R.string.review_page_indicator,
            currentIndex + 1,
            pages.size,
            page.pageOrder + 1,
        )
        issueView.text = page.issueLabels.joinToString(separator = "\n") { "• $it" }
        toggleButton.setText(if (showOriginal) R.string.review_show_result else R.string.review_show_original)
        previousButton.isEnabled = currentIndex > 0
        nextButton.isEnabled = currentIndex < pages.lastIndex
        val path = if (showOriginal) page.originalPath else page.finalPath
        val request = generation.incrementAndGet()
        executor.execute {
            val bitmap = path?.let(::decodePage)
            runOnUiThread {
                if (request != generation.get() || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                val previous = displayedBitmap
                displayedBitmap = bitmap
                imageView.setImageBitmap(bitmap)
                imageView.visibility = if (bitmap == null) View.GONE else View.VISIBLE
                previous?.takeIf { it !== bitmap }?.recycle()
            }
        }
    }

    private fun decodePage(path: Path): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Files.newInputStream(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidth ||
            sampledPixelCount(bounds.outWidth, bounds.outHeight, sample) > MAX_BITMAP_PIXELS
        ) {
            sample *= 2
        }
        return Files.newInputStream(path).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }

    private fun sampledPixelCount(width: Int, height: Int, sample: Int): Long =
        (width.toLong() / sample.coerceAtLeast(1)) * (height.toLong() / sample.coerceAtLeast(1))

    private fun resolveInside(root: Path, relative: String): Path? = runCatching {
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root.normalize()) && Files.isRegularFile(resolved))
        resolved
    }.getOrNull()

    private fun issueLabel(code: QualityIssueCode): String = getString(
        when (code) {
            QualityIssueCode.PAGE_PRESERVED_CLEANED -> R.string.review_issue_page_preserved
            QualityIssueCode.REGION_PRESERVED -> R.string.review_issue_region_preserved
            QualityIssueCode.RENDERED_DIMENSION_MISMATCH -> R.string.review_issue_dimensions
            QualityIssueCode.LAYOUT_OUT_OF_BOUNDS -> R.string.review_issue_layout_bounds
            QualityIssueCode.TYPESET_PIXELS_MISSING -> R.string.review_issue_missing_text
            QualityIssueCode.CHANGED_PIXELS_OUTSIDE_LAYOUT -> R.string.review_issue_outside_change
        },
    )

    private data class ReviewPage(
        val pageOrder: Int,
        val originalPath: Path?,
        val finalPath: Path?,
        val issueLabels: List<String>,
    )

    companion object {
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_TITLE = "title"
        private const val MAX_BITMAP_PIXELS = 8_000_000L
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun intent(context: Context, projectId: String, title: String): Intent =
            Intent(context, IssueReviewActivity::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_TITLE, title)
    }
}
