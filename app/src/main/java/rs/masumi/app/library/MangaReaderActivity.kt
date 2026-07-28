package rs.masumi.app.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import rs.masumi.app.R
import rs.masumi.app.pipeline.PipelineThreading

class MangaReaderActivity : Activity() {
    private lateinit var titleView: TextView
    private lateinit var readerView: ZoomableReaderView
    private lateinit var continuousView: ContinuousReaderView
    private lateinit var modeButton: Button
    private lateinit var statusView: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var directionButton: Button
    private lateinit var readingProgressStore: MangaReadingProgressStore
    private val executor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory("masumi-reader"),
    )
    private val loadGeneration = AtomicInteger()
    private var pages: List<MangaLibraryPage> = emptyList()
    private var currentIndex = 0
    private val pageCache = mutableMapOf<Int, Bitmap>()
    private val pendingLoads = mutableSetOf<Int>()
    private var displayedBitmap: Bitmap? = null
    private var chromeVisible = false
    private var rightToLeft = true
    private var continuousMode = true
    private var pageAspects: List<Float> = emptyList()
    private var seekBarDragging = false
    private lateinit var projectId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_manga_reader)
        enterImmersiveMode()

        titleView = findViewById(R.id.readerTitle)
        readerView = findViewById(R.id.readerImage)
        continuousView = findViewById(R.id.readerContinuous)
        modeButton = findViewById(R.id.readerModeButton)
        statusView = findViewById(R.id.readerStatus)
        topBar = findViewById(R.id.readerTopBar)
        bottomBar = findViewById(R.id.readerBottomBar)
        seekBar = findViewById(R.id.readerSeekBar)
        directionButton = findViewById(R.id.readerDirectionButton)
        readingProgressStore = MangaReadingProgressStore(this)
        findViewById<Button>(R.id.readerCloseButton).setOnClickListener { finish() }

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
        rightToLeft = readingProgressStore.readsRightToLeft(projectId)
        continuousMode = readingProgressStore.readsContinuously(projectId)
        currentIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX)
            ?: readingProgressStore.load(projectId)?.pageIndex
            ?: 0

        readerView.onTap = ::handleTap
        readerView.onHorizontalSwipe = ::handleSwipe
        continuousView.onTap = { setChromeVisible(!chromeVisible) }
        continuousView.onNeedPage = { index -> ensurePageLoaded(index) }
        continuousView.onVisiblePageChanged = { index ->
            currentIndex = index
            statusView.text = pageIndicator(index)
            if (!seekBarDragging) seekBar.progress = index
            if (pages.isNotEmpty()) readingProgressStore.save(projectId, index, pages.size)
            evictDistantPages()
        }
        modeButton.setOnClickListener { toggleReadingMode() }
        directionButton.setOnClickListener { toggleDirection() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) statusView.text = pageIndicator(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                seekBarDragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                seekBarDragging = false
                bar?.let { if (continuousMode) jumpToPage(it.progress) else showPage(it.progress) }
            }
        })
        applyDirectionLabel()
        applyReadingMode()
        setChromeVisible(false)
        loadProject(rootUri, projectId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        executor.shutdownNow()
        readerView.setImageDrawable(null)
        displayedBitmap = null
        pageCache.values.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        pageCache.clear()
        pendingLoads.clear()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !chromeVisible) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun handleTap(fraction: Float) {
        when {
            fraction <= PREVIOUS_TAP_ZONE -> turnPage(forward = rightToLeft)
            fraction >= NEXT_TAP_ZONE -> turnPage(forward = !rightToLeft)
            else -> setChromeVisible(!chromeVisible)
        }
    }

    private fun handleSwipe(direction: ZoomableReaderView.SwipeDirection) {
        val towardStart = direction == ZoomableReaderView.SwipeDirection.RIGHT
        // Swiping toward the page you came from goes back; in RTL the previous
        // page sits to the left, so the mapping flips with the direction.
        turnPage(forward = if (rightToLeft) towardStart else !towardStart)
    }

    private fun turnPage(forward: Boolean) {
        showPage(currentIndex + if (forward) 1 else -1)
    }

    private fun toggleReadingMode() {
        continuousMode = !continuousMode
        readingProgressStore.saveReadingMode(projectId, continuousMode)
        applyReadingMode()
    }

    private fun applyReadingMode() {
        modeButton.setText(if (continuousMode) R.string.reader_mode_continuous else R.string.reader_mode_paged)
        directionButton.visibility = if (continuousMode) View.GONE else View.VISIBLE
        continuousView.visibility = if (continuousMode) View.VISIBLE else View.GONE
        readerView.visibility = if (continuousMode) View.GONE else View.VISIBLE
        if (pages.isEmpty()) return
        if (continuousMode) {
            readerView.setImageDrawable(null)
            displayedBitmap = null
            continuousView.bind(pageAspects) { index -> pageCache[index] }
            continuousView.scrollToPage(currentIndex)
        } else {
            showPage(currentIndex)
        }
    }

    private fun jumpToPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        continuousView.scrollToPage(index)
        statusView.text = pageIndicator(index)
        readingProgressStore.save(projectId, index, pages.size)
    }

    private fun toggleDirection() {
        rightToLeft = !rightToLeft
        readingProgressStore.saveReadingDirection(projectId, rightToLeft)
        applyDirectionLabel()
    }

    private fun applyDirectionLabel() {
        directionButton.setText(
            if (rightToLeft) R.string.reader_direction_rtl else R.string.reader_direction_ltr,
        )
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) exitImmersiveMode() else enterImmersiveMode()
    }

    private fun pageIndicator(index: Int): String =
        getString(R.string.reader_page_indicator, index + 1, pages.size)

    private fun loadProject(rootUri: Uri, projectId: String) {
        statusView.setText(R.string.reader_loading)
        val generation = loadGeneration.incrementAndGet()
        executor.execute {
            val loaded = runCatching {
                MangaLibraryStore(contentResolver, rootUri).outputPages(projectId)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (generation != loadGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                readerView.setImageDrawable(null)
                displayedBitmap = null
                pageCache.values.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
                pageCache.clear()
                pendingLoads.clear()
                pages = loaded
                if (pages.isEmpty()) {
                    statusView.setText(R.string.reader_empty)
                    setChromeVisible(true)
                } else {
                    seekBar.max = pages.lastIndex
                    currentIndex = currentIndex.coerceIn(0, pages.lastIndex)
                    loadPageAspects(rootUri, generation)
                }
            }
        }
    }

    /**
     * One cheap bounds-only decode per page gives the continuous layout a
     * stable total height before any full page is decoded.
     */
    private fun loadPageAspects(rootUri: Uri, generation: Int) {
        val snapshot = pages
        executor.execute {
            val aspects = snapshot.map { page ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching {
                    contentResolver.openInputStream(page.uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    bounds.outHeight.toFloat() / bounds.outWidth
                } else {
                    DEFAULT_PAGE_ASPECT
                }
            }
            runOnUiThread {
                if (generation != loadGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                pageAspects = aspects
                applyReadingMode()
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        statusView.text = pageIndicator(index)
        if (!seekBarDragging) seekBar.progress = index
        readingProgressStore.save(projectId, index, pages.size)
        pageCache[index]?.let(::display)
        ensurePageLoaded(index)
        ensurePageLoaded(index + 1)
        ensurePageLoaded(index - 1)
        evictDistantPages()
    }

    private fun cacheRadius(): Int = if (continuousMode) CONTINUOUS_CACHE_RADIUS else CACHE_RADIUS

    private fun display(bitmap: Bitmap) {
        displayedBitmap = bitmap
        readerView.setImageBitmap(bitmap)
    }

    private fun ensurePageLoaded(index: Int) {
        if (index !in pages.indices || pageCache.containsKey(index) || !pendingLoads.add(index)) return
        val generation = loadGeneration.get()
        val page = pages[index]
        executor.execute {
            val bitmap = decodePage(page.uri)
            runOnUiThread {
                pendingLoads.remove(index)
                if (generation != loadGeneration.get() || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    if (index == currentIndex) {
                        Toast.makeText(this, R.string.preview_unavailable, Toast.LENGTH_SHORT).show()
                    }
                    return@runOnUiThread
                }
                if (kotlin.math.abs(index - currentIndex) > cacheRadius()) {
                    bitmap.recycle()
                    return@runOnUiThread
                }
                pageCache[index] = bitmap
                if (continuousMode) {
                    continuousView.invalidate()
                } else if (index == currentIndex) {
                    display(bitmap)
                }
            }
        }
    }

    private fun evictDistantPages() {
        val iterator = pageCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (kotlin.math.abs(entry.key - currentIndex) > cacheRadius()) {
                iterator.remove()
                if (entry.value === displayedBitmap) {
                    readerView.setImageDrawable(null)
                    displayedBitmap = null
                }
                entry.value.recycle()
            }
        }
    }

    private fun decodePage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val detailWidth = (targetWidth.toLong() * READER_DETAIL_WIDTH_MULTIPLIER)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= detailWidth) {
            sampleSize *= 2
        }
        // Memory pressure may require another step down, but never decode a
        // page narrower than the screen and then upscale it. That was the
        // second source of visible jagged text on very tall pages.
        while (
            sampledPixelCount(bounds.outWidth, bounds.outHeight, sampleSize) > MAX_BITMAP_PIXELS &&
            bounds.outWidth / (sampleSize * 2) >= targetWidth
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long =
        (width.toLong() / sampleSize.coerceAtLeast(1)) * (height.toLong() / sampleSize.coerceAtLeast(1))

    companion object {
        private const val EXTRA_LIBRARY_ROOT = "library_root"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PAGE_INDEX = "page_index"
        private const val MAX_BITMAP_PIXELS = 12_000_000L
        private const val READER_DETAIL_WIDTH_MULTIPLIER = 2
        private const val CACHE_RADIUS = 1
        private const val CONTINUOUS_CACHE_RADIUS = 1
        private const val DEFAULT_PAGE_ASPECT = 1.45f
        private const val PREVIOUS_TAP_ZONE = 0.32f
        private const val NEXT_TAP_ZONE = 0.68f
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun intent(context: Context, rootUri: Uri, project: MangaLibraryProject): Intent =
            Intent(context, MangaReaderActivity::class.java)
                .putExtra(EXTRA_LIBRARY_ROOT, rootUri.toString())
                .putExtra(EXTRA_PROJECT_ID, project.metadata.projectId)
                .putExtra(EXTRA_TITLE, project.metadata.title)
    }
}
