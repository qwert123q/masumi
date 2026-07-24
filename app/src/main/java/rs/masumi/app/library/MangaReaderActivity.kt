package rs.masumi.app.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import rs.masumi.app.R

class MangaReaderActivity : Activity() {
    private lateinit var titleView: TextView
    private lateinit var imageView: ImageView
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var readingProgressStore: MangaReadingProgressStore
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "masumi-reader") }
    private val loadGeneration = AtomicInteger()
    private var pages: List<MangaLibraryPage> = emptyList()
    private var currentIndex = 0
    private var displayedBitmap: Bitmap? = null
    private lateinit var projectId: String
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_manga_reader)

        titleView = findViewById(R.id.readerTitle)
        imageView = findViewById(R.id.readerImage)
        scrollView = findViewById(R.id.readerScroll)
        statusView = findViewById(R.id.readerStatus)
        previousButton = findViewById(R.id.readerPreviousButton)
        nextButton = findViewById(R.id.readerNextButton)
        readingProgressStore = MangaReadingProgressStore(this)
        findViewById<Button>(R.id.readerCloseButton).setOnClickListener { finish() }
        previousButton.setOnClickListener { showPage(currentIndex - 1) }
        nextButton.setOnClickListener { showPage(currentIndex + 1) }
        installSwipeNavigation()

        val rootUri = intent.getStringExtra(EXTRA_LIBRARY_ROOT)?.let(Uri::parse)
        val requestedProjectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (
            rootUri == null || requestedProjectId == null ||
            rootUri.scheme != "content" || !SAFE_ID.matches(requestedProjectId)
        ) {
            finish()
            return
        }
        projectId = requestedProjectId
        titleView.text = title
        currentIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX)
            ?: readingProgressStore.load(projectId)?.pageIndex
            ?: 0
        loadProject(rootUri, projectId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        executor.shutdownNow()
        displayedBitmap?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }

    private fun loadProject(rootUri: Uri, projectId: String) {
        statusView.setText(R.string.reader_loading)
        val generation = loadGeneration.incrementAndGet()
        executor.execute {
            val loaded = runCatching {
                MangaLibraryStore(contentResolver, rootUri).outputPages(projectId)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (generation != loadGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                pages = loaded
                if (pages.isEmpty()) {
                    statusView.setText(R.string.reader_empty)
                    previousButton.isEnabled = false
                    nextButton.isEnabled = false
                } else {
                    showPage(currentIndex.coerceIn(0, pages.lastIndex))
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        previousButton.isEnabled = index > 0
        nextButton.isEnabled = index < pages.lastIndex
        statusView.text = getString(R.string.reader_page_indicator, index + 1, pages.size)
        readingProgressStore.save(projectId, index, pages.size)
        scrollView.scrollTo(0, 0)
        val generation = loadGeneration.incrementAndGet()
        val page = pages[index]
        executor.execute {
            val bitmap = decodePage(page.uri)
            runOnUiThread {
                if (generation != loadGeneration.get() || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                val previous = displayedBitmap
                displayedBitmap = bitmap
                imageView.setImageBitmap(bitmap)
                previous?.takeIf { it !== bitmap }?.recycle()
                if (bitmap == null) {
                    Toast.makeText(this, R.string.preview_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodePage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= targetWidth ||
            sampledPixelCount(bounds.outWidth, bounds.outHeight, sampleSize) > MAX_BITMAP_PIXELS
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long =
        (width.toLong() / sampleSize.coerceAtLeast(1)) * (height.toLong() / sampleSize.coerceAtLeast(1))

    @Suppress("ClickableViewAccessibility")
    private fun installSwipeNavigation() {
        val threshold = 72f * resources.displayMetrics.density
        scrollView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) >= threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        if (dx < 0f) showPage(currentIndex + 1) else showPage(currentIndex - 1)
                    }
                }
            }
            false
        }
    }

    companion object {
        private const val EXTRA_LIBRARY_ROOT = "library_root"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PAGE_INDEX = "page_index"
        private const val MAX_BITMAP_PIXELS = 8_000_000L
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun intent(context: Context, rootUri: Uri, project: MangaLibraryProject): Intent =
            Intent(context, MangaReaderActivity::class.java)
                .putExtra(EXTRA_LIBRARY_ROOT, rootUri.toString())
                .putExtra(EXTRA_PROJECT_ID, project.metadata.projectId)
                .putExtra(EXTRA_TITLE, project.metadata.title)
    }
}
