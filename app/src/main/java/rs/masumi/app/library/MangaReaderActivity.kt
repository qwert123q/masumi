package rs.masumi.app.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var jumpInput: EditText
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var directionButton: Button
    private lateinit var readerPreferences: MangaReaderPreferences
    private val metadataExecutor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory("masumi-reader-metadata"),
    )
    private val pageExecutor = Executors.newFixedThreadPool(
        PAGE_DECODE_WORKERS,
        PipelineThreading.factory("masumi-reader-page", numbered = true),
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
    private var pageAspects: MutableList<Float> = mutableListOf()
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
        jumpInput = findViewById(R.id.readerJumpInput)
        topBar = findViewById(R.id.readerTopBar)
        bottomBar = findViewById(R.id.readerBottomBar)
        seekBar = findViewById(R.id.readerSeekBar)
        directionButton = findViewById(R.id.readerDirectionButton)
        readerPreferences = MangaReaderPreferences(this)
        findViewById<Button>(R.id.readerCloseButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.readerJumpButton).setOnClickListener { jumpToEnteredPage() }
        jumpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                jumpToEnteredPage()
                true
            } else {
                false
            }
        }

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
        rightToLeft = readerPreferences.readsRightToLeft(projectId)
        continuousMode = readerPreferences.readsContinuously(projectId)
        currentIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX) ?: 0

        readerView.onTap = ::handleTap
        readerView.onHorizontalSwipe = ::handleSwipe
        continuousView.onTap = { setChromeVisible(!chromeVisible) }
        continuousView.onNeedPage = { index -> ensurePageLoaded(index) }
        continuousView.onVisiblePageChanged = { index ->
            currentIndex = index
            statusView.text = pageIndicator(index)
            if (!seekBarDragging) seekBar.progress = index
            evictDistantPages()
            prefetchPages(index)
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
        metadataExecutor.shutdownNow()
        pageExecutor.shutdownNow()
        readerView.setImageDrawable(null)
        displayedBitmap = null
        pageCache.values.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        pageCache.clear()
        pendingLoads.clear()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the currently displayed bitmap during rotation. Recreating the
        // activity used to discard the cache and rescan every page, producing
        // a long black screen. Reposition first, then replace the current page
        // at the new viewport size in the background.
        enterImmersiveMode()
        continuousView.post {
            if (continuousMode) continuousView.scrollToPage(currentIndex)
            ensurePageLoaded(currentIndex, replaceExisting = true)
        }
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
        readerPreferences.saveReadingMode(projectId, continuousMode)
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
    }

    private fun toggleDirection() {
        rightToLeft = !rightToLeft
        readerPreferences.saveReadingDirection(projectId, rightToLeft)
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
        metadataExecutor.execute {
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
                    // Bind immediately with a stable provisional aspect ratio.
                    // Actual ratios are corrected as pages are decoded; opening
                    // a chapter no longer waits for a bounds pass over every
                    // file in the chapter.
                    pageAspects = MutableList(pages.size) { DEFAULT_PAGE_ASPECT }
                    statusView.text = pageIndicator(currentIndex)
                    applyReadingMode()
                    prefetchPages(currentIndex)
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        statusView.text = pageIndicator(index)
        if (!seekBarDragging) seekBar.progress = index
        pageCache[index]?.let(::display)
        prefetchPages(index)
        evictDistantPages()
    }

    private fun prefetchPages(index: Int) {
        ensurePageLoaded(index)
        ensurePageLoaded(index - PREFETCH_BACKWARD_PAGE_COUNT)
        for (offset in 1..PREFETCH_FORWARD_PAGE_COUNT) {
            ensurePageLoaded(index + offset)
        }
    }

    private fun shouldRetainPage(index: Int): Boolean =
        index >= currentIndex - PREFETCH_BACKWARD_PAGE_COUNT &&
            index <= currentIndex + PREFETCH_FORWARD_PAGE_COUNT

    private fun display(bitmap: Bitmap) {
        displayedBitmap = bitmap
        readerView.setImageBitmap(bitmap)
    }

    private fun ensurePageLoaded(index: Int, replaceExisting: Boolean = false) {
        if (
            index !in pages.indices ||
            (!replaceExisting && pageCache.containsKey(index)) ||
            !pendingLoads.add(index)
        ) {
            return
        }
        val generation = loadGeneration.get()
        val page = pages[index]
        pageExecutor.execute {
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
                if (!replaceExisting && !shouldRetainPage(index)) {
                    bitmap.recycle()
                    return@runOnUiThread
                }
                val oldBitmap = pageCache.put(index, bitmap)
                val aspect = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                if (kotlin.math.abs(pageAspects[index] - aspect) >= ASPECT_UPDATE_EPSILON) {
                    pageAspects[index] = aspect
                    continuousView.updatePageAspect(index, aspect)
                }
                if (continuousMode) {
                    continuousView.invalidate()
                } else if (index == currentIndex) {
                    display(bitmap)
                }
                oldBitmap?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
            }
        }
    }

    private fun evictDistantPages() {
        val iterator = pageCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!shouldRetainPage(entry.key)) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) {
                    decoder, info, _ ->
                    val target = targetDecodeSize(info.size.width, info.size.height)
                    decoder.setTargetSize(target.first, target.second)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = targetDecodeSize(bounds.outWidth, bounds.outHeight).first
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        while (
            sampledPixelCount(bounds.outWidth, bounds.outHeight, sampleSize) > MAX_BITMAP_PIXELS &&
            bounds.outWidth / (sampleSize * 2) >= resources.displayMetrics.widthPixels
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun targetDecodeSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0)
        val viewportWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var targetWidth = minOf(
            sourceWidth,
            (viewportWidth * READER_DETAIL_WIDTH_MULTIPLIER).toInt().coerceAtLeast(1),
        )
        var targetHeight = (sourceHeight.toLong() * targetWidth / sourceWidth)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        val pixels = targetWidth.toLong() * targetHeight
        if (pixels > MAX_BITMAP_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / pixels)
            targetWidth = (targetWidth * scale).toInt().coerceAtLeast(1)
            targetHeight = (targetHeight * scale).toInt().coerceAtLeast(1)
        }
        return targetWidth to targetHeight
    }

    private fun jumpToEnteredPage() {
        if (pages.isEmpty()) return
        val requested = jumpInput.text.toString().trim().toIntOrNull()
        if (requested == null || requested !in 1..pages.size) {
            Toast.makeText(
                this,
                getString(R.string.reader_jump_invalid, pages.size),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (continuousMode) {
            jumpToPage(requested - 1)
            prefetchPages(requested - 1)
            evictDistantPages()
        } else {
            showPage(requested - 1)
        }
        jumpInput.text.clear()
        jumpInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(jumpInput.windowToken, 0)
    }

    private fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long =
        (width.toLong() / sampleSize.coerceAtLeast(1)) * (height.toLong() / sampleSize.coerceAtLeast(1))

    companion object {
        private const val EXTRA_LIBRARY_ROOT = "library_root"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PAGE_INDEX = "page_index"
        private const val MAX_BITMAP_PIXELS = 6_000_000L
        private const val READER_DETAIL_WIDTH_MULTIPLIER = 1.25f
        private const val PAGE_DECODE_WORKERS = 2
        private const val PREFETCH_BACKWARD_PAGE_COUNT = 5
        private const val PREFETCH_FORWARD_PAGE_COUNT = 5
        private const val DEFAULT_PAGE_ASPECT = 1.45f
        private const val ASPECT_UPDATE_EPSILON = 0.002f
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
