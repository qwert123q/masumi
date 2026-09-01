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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import rs.masumi.app.R
import rs.masumi.app.pipeline.ReaderForegroundState

internal inline fun <T> runReaderPageLoadAttempt(
    isStale: () -> Boolean,
    load: () -> T?,
    deliver: (T?) -> Unit,
    discard: (T?) -> Unit,
    onFailure: () -> Unit,
    onFinished: () -> Unit,
) {
    var loaded = false
    var delivered = false
    var result: T? = null
    try {
        if (isStale()) return
        result = load()
        loaded = true
        if (isStale()) return
        deliver(result)
        delivered = true
    } catch (_: Exception) {
        onFailure()
    } finally {
        try {
            if (loaded && !delivered) discard(result)
        } finally {
            onFinished()
        }
    }
}

class MangaReaderActivity : Activity() {
    private lateinit var titleView: TextView
    private lateinit var continuousView: ContinuousReaderView
    private lateinit var statusView: TextView
    private lateinit var jumpInput: EditText
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var readerPreferences: MangaReaderPreferences
    private val metadataExecutor = Executors.newSingleThreadExecutor(
        ReaderThreading.factory("masumi-reader-metadata"),
    )
    private val pageExecutor = ThreadPoolExecutor(
        PAGE_DECODE_WORKERS,
        PAGE_DECODE_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
        ReaderThreading.factory("masumi-reader-page"),
    )
    private val loadGeneration = AtomicInteger()
    private val pageLoadGeneration = AtomicInteger()
    private val decodeSequence = AtomicLong()
    private var pages: List<MangaLibraryPage> = emptyList()
    private var currentIndex = 0
    private val pageCache = mutableMapOf<Int, Bitmap>()
    private val pendingLoads = mutableMapOf<Int, Int>()
    private var chromeVisible = false
    private var pageAspects: MutableList<Float> = mutableListOf()
    private var seekBarDragging = false
    private lateinit var projectId: String
    private lateinit var imageDiskCache: LibraryImageDiskCache
    private lateinit var pageCacheWrites: ReaderPageCacheWriteQueue<Bitmap>
    private var readerCacheVersion = 0L
    private var pendingLocation = ReadingLocation.START
    private var pendingLocationRestoreApplied = false
    private var locationRestoreGeneration = 0
    private var readerForegroundRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_manga_reader)
        enterImmersiveMode()

        titleView = findViewById(R.id.readerTitle)
        continuousView = findViewById(R.id.readerContinuous)
        statusView = findViewById(R.id.readerStatus)
        jumpInput = findViewById(R.id.readerJumpInput)
        topBar = findViewById(R.id.readerTopBar)
        bottomBar = findViewById(R.id.readerBottomBar)
        seekBar = findViewById(R.id.readerSeekBar)
        readerPreferences = MangaReaderPreferences(this)
        imageDiskCache = LibraryImageDiskCache(cacheDir.toPath().resolve("library-image-cache"))
        pageCacheWrites = ReaderPageCacheWriteQueue(
            maxPendingWrites = PAGE_CACHE_WRITE_QUEUE_CAPACITY,
            threadFactory = ReaderThreading.lowPriorityFactory("masumi-reader-cache-write"),
            write = ::writePageToDiskCache,
            dispose = { bitmap -> bitmap.takeUnless(Bitmap::isRecycled)?.recycle() },
        )
        findViewById<Button>(R.id.readerCloseButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.readerJumpButton).setOnClickListener { jumpToEnteredPage() }
        findViewById<Button>(R.id.readerRestartButton).setOnClickListener { restartFromBeginning() }
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
        readerCacheVersion = intent.getLongExtra(EXTRA_CACHE_VERSION, 0L)
        titleView.text = title
        val storedLocation = readerPreferences.readingLocation(projectId)
        pendingLocation = ReadingLocation(
            pageIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX) ?: storedLocation.pageIndex,
            intraPageFraction = savedInstanceState?.getFloat(STATE_PAGE_FRACTION)
                ?: storedLocation.intraPageFraction,
        )
        currentIndex = pendingLocation.pageIndex

        continuousView.onTap = { setChromeVisible(!chromeVisible) }
        continuousView.onNeedPage = { index -> ensurePageLoaded(index) }
        continuousView.onVisiblePageChanged = { index ->
            currentIndex = index
            statusView.text = pageIndicator(index)
            if (!seekBarDragging) seekBar.progress = index
            evictDistantPages()
            cancelQueuedPageLoads()
            prefetchPages(index)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) statusView.text = pageIndicator(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                seekBarDragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                seekBarDragging = false
                bar?.let { jumpToPage(it.progress) }
            }
        })
        setChromeVisible(false)
        loadProject(rootUri, projectId)
    }

    override fun onStart() {
        super.onStart()
        if (!readerForegroundRegistered) {
            ReaderForegroundState.enter()
            readerForegroundRegistered = true
        }
    }

    override fun onPause() {
        persistReadingLocation()
        super.onPause()
    }

    override fun onStop() {
        if (readerForegroundRegistered) {
            ReaderForegroundState.exit()
            readerForegroundRegistered = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val location = ReaderLocationLifecycle.locationForSave(
            pagesBound = pages.isNotEmpty(),
            readerLaidOut = continuousView.isLaidOut && continuousView.width > 0 && continuousView.height > 0,
            pendingRestoreApplied = pendingLocationRestoreApplied,
            pending = pendingLocation,
            captureBoundView = continuousView::captureReadingLocation,
        )
        outState.putInt(STATE_PAGE_INDEX, location.pageIndex)
        outState.putFloat(STATE_PAGE_FRACTION, location.intraPageFraction)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        pageLoadGeneration.incrementAndGet()
        metadataExecutor.shutdownNow()
        pageExecutor.shutdownNow()
        pageCacheWrites.shutdownNow()
        pageCache.values.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
        pageCache.clear()
        pendingLoads.clear()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val location = ReaderLocationLifecycle.locationForSave(
            pagesBound = pages.isNotEmpty(),
            readerLaidOut = continuousView.isLaidOut && continuousView.width > 0 && continuousView.height > 0,
            pendingRestoreApplied = pendingLocationRestoreApplied,
            pending = pendingLocation,
            captureBoundView = continuousView::captureReadingLocation,
        )
        super.onConfigurationChanged(newConfig)
        // Keep the currently displayed bitmap during rotation. Recreating the
        // activity used to discard the cache and rescan every page, producing
        // a long black screen. Reposition first, then replace the current page
        // at the new viewport size in the background.
        enterImmersiveMode()
        restorePendingLocation(location, deferUntilNextLoop = true) {
            pageLoadGeneration.incrementAndGet()
            cancelQueuedPageLoads()
            pendingLoads.clear()
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

    private fun jumpToPage(index: Int) {
        if (index !in pages.indices) return
        currentIndex = index
        restorePendingLocation(ReadingLocation(index, 0f))
        statusView.text = pageIndicator(index)
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
                pageCache.values.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
                pageCache.clear()
                pendingLoads.clear()
                pageLoadGeneration.incrementAndGet()
                pages = loaded
                if (pages.isEmpty()) {
                    statusView.setText(R.string.reader_empty)
                    setChromeVisible(true)
                } else {
                    seekBar.max = pages.lastIndex
                    pendingLocation = pendingLocation.clamped(pages.size)
                    currentIndex = pendingLocation.pageIndex
                    // Bind immediately with a stable provisional aspect ratio.
                    // Actual ratios are corrected as pages are decoded; opening
                    // a chapter no longer waits for a bounds pass over every
                    // file in the chapter.
                    pageAspects = MutableList(pages.size) { DEFAULT_PAGE_ASPECT }
                    statusView.text = pageIndicator(currentIndex)
                    continuousView.visibility = View.VISIBLE
                    continuousView.bind(pageAspects) { index -> pageCache[index] }
                    restorePendingLocation(pendingLocation)
                    prefetchPages(currentIndex)
                }
            }
        }
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

    private fun ensurePageLoaded(index: Int, replaceExisting: Boolean = false) {
        val pageGeneration = pageLoadGeneration.get()
        if (
            pageExecutor.isShutdown ||
            index !in pages.indices ||
            (!replaceExisting && pageCache.containsKey(index)) ||
            pendingLoads[index] == pageGeneration
        ) {
            return
        }
        val generation = loadGeneration.get()
        val page = pages[index]
        pendingLoads[index] = pageGeneration
        val decodeProfile = ReaderThreading.decodeProfile(
            viewportWidth = resources.displayMetrics.widthPixels,
            maximumPixels = MAX_BITMAP_PIXELS,
            detailWidthMultiplier = READER_DETAIL_WIDTH_MULTIPLIER,
        )
        val task = PageLoadTask(
            index = index,
            generation = generation,
            pageGeneration = pageGeneration,
            page = page,
            replaceExisting = replaceExisting,
            cacheKey = "page:$projectId:$readerCacheVersion:$index:${page.uri}:$decodeProfile",
            sequence = decodeSequence.incrementAndGet(),
            scheduledCurrentIndex = currentIndex,
        )
        try {
            pageExecutor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (pendingLoads[index] == pageGeneration) pendingLoads.remove(index)
        }
    }

    private inner class PageLoadTask(
        val index: Int,
        private val generation: Int,
        val pageGeneration: Int,
        private val page: MangaLibraryPage,
        private val replaceExisting: Boolean,
        private val cacheKey: String,
        private val sequence: Long,
        scheduledCurrentIndex: Int,
    ) : Runnable, Comparable<PageLoadTask> {
        private val priorityGroup = when {
            index == scheduledCurrentIndex -> 0
            index > scheduledCurrentIndex -> 1
            else -> 2
        }
        private val distance = kotlin.math.abs(index - scheduledCurrentIndex)

        override fun compareTo(other: PageLoadTask): Int =
            compareValuesBy(this, other, PageLoadTask::priorityGroup, PageLoadTask::distance, PageLoadTask::sequence)

        override fun run() {
            runReaderPageLoadAttempt(
                isStale = ::isStale,
                load = { decodePage(page.uri, cacheKey) },
                deliver = ::deliver,
                discard = { decoded ->
                    decoded?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                },
                onFailure = { deliver(null) },
                onFinished = {
                    runOnUiThread {
                        if (pendingLoads[index] == pageGeneration) pendingLoads.remove(index)
                    }
                },
            )
        }

        private fun isStale(): Boolean =
            Thread.currentThread().isInterrupted ||
                generation != loadGeneration.get() ||
                pageGeneration != pageLoadGeneration.get()

        private fun deliver(decoded: DecodedReaderPage?) {
            val bitmap = decoded?.bitmap
            // The page becomes drawable after decode, without waiting for a
            // lossless WebP/PNG encode. Cache a private immutable copy so UI
            // eviction can recycle the displayed bitmap independently.
            val cacheCopy = if (decoded?.needsCacheWrite == true && bitmap != null) {
                runCatching {
                    bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            } else {
                null
            }
            runOnUiThread {
                if (pendingLoads[index] == pageGeneration) pendingLoads.remove(index)
                if (
                    generation != loadGeneration.get() ||
                    pageGeneration != pageLoadGeneration.get() ||
                    isFinishing ||
                    isDestroyed
                ) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    if (index == currentIndex) {
                        Toast.makeText(
                            this@MangaReaderActivity,
                            R.string.preview_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
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
                continuousView.invalidate()
                oldBitmap?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
                trimPageCacheToMemoryBudget()
            }
            if (cacheCopy != null) {
                if (isStale()) {
                    cacheCopy.recycle()
                } else {
                    // Ownership transfers to the bounded writer even when it
                    // rejects the task; every terminal path recycles the copy.
                    pageCacheWrites.submit(cacheKey, cacheCopy)
                }
            }
        }
    }

    private fun cancelQueuedPageLoads() {
        pageExecutor.queue.toList().filterIsInstance<PageLoadTask>().forEach { task ->
            if (pageExecutor.remove(task) && pendingLoads[task.index] == task.pageGeneration) {
                pendingLoads.remove(task.index)
            }
        }
    }

    private fun evictDistantPages() {
        val iterator = pageCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!shouldRetainPage(entry.key)) {
                iterator.remove()
                entry.value.recycle()
            }
        }
        trimPageCacheToMemoryBudget()
    }

    private fun trimPageCacheToMemoryBudget() {
        var total = pageCache.values.sumOf { it.allocationByteCount.toLong() }
        if (total <= PAGE_BITMAP_MEMORY_BUDGET_BYTES) return
        pageCache.keys
            .filter { it != currentIndex }
            .sortedByDescending { kotlin.math.abs(it - currentIndex) }
            .forEach { index ->
                if (total <= PAGE_BITMAP_MEMORY_BUDGET_BYTES) return@forEach
                val bitmap = pageCache.remove(index) ?: return@forEach
                total -= bitmap.allocationByteCount.toLong()
                bitmap.recycle()
            }
    }

    private fun decodePage(uri: Uri, cacheKey: String): DecodedReaderPage? {
        imageDiskCache.read(cacheKey)?.let { encoded ->
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size)?.let {
                return DecodedReaderPage(it, needsCacheWrite = false)
            }
        }
        val decoded = decodeSourcePage(uri) ?: return null
        return DecodedReaderPage(decoded, needsCacheWrite = true)
    }

    private fun writePageToDiskCache(cacheKey: String, bitmap: Bitmap) {
        runCatching {
            java.io.ByteArrayOutputStream().use { output ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    Bitmap.CompressFormat.PNG
                }
                check(bitmap.compress(format, PAGE_CACHE_QUALITY, output))
                imageDiskCache.write(cacheKey, LibraryImageAssetKind.READER_PAGE, output.toByteArray())
            }
        }
    }

    private fun decodeSourcePage(uri: Uri): Bitmap? {
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
        jumpToPage(requested - 1)
        prefetchPages(requested - 1)
        evictDistantPages()
        jumpInput.text.clear()
        jumpInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(jumpInput.windowToken, 0)
    }

    private fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long =
        (width.toLong() / sampleSize.coerceAtLeast(1)) * (height.toLong() / sampleSize.coerceAtLeast(1))

    private fun restartFromBeginning() {
        currentIndex = 0
        readerPreferences.clearReadingLocation(projectId)
        restorePendingLocation(ReadingLocation.START)
        statusView.text = pageIndicator(0)
        seekBar.progress = 0
        prefetchPages(0)
        evictDistantPages()
    }

    private fun persistReadingLocation() {
        if (!::projectId.isInitialized || pages.isEmpty()) return
        pendingLocation = ReaderLocationLifecycle.locationForSave(
            pagesBound = true,
            readerLaidOut = continuousView.isLaidOut && continuousView.width > 0 && continuousView.height > 0,
            pendingRestoreApplied = pendingLocationRestoreApplied,
            pending = pendingLocation,
            captureBoundView = continuousView::captureReadingLocation,
        ).clamped(pages.size)
        currentIndex = pendingLocation.pageIndex
        readerPreferences.saveReadingLocation(projectId, pendingLocation)
    }

    private fun restorePendingLocation(
        location: ReadingLocation,
        deferUntilNextLoop: Boolean = false,
        afterRequest: () -> Unit = {},
    ) {
        pendingLocation = location
        pendingLocationRestoreApplied = false
        val generation = ++locationRestoreGeneration
        val apply = {
            continuousView.restoreReadingLocation(location) {
                if (generation == locationRestoreGeneration) {
                    pendingLocationRestoreApplied = true
                }
            }
            afterRequest()
        }
        if (deferUntilNextLoop) continuousView.post(apply) else apply()
    }

    private data class DecodedReaderPage(
        val bitmap: Bitmap,
        val needsCacheWrite: Boolean,
    )

    companion object {
        private const val EXTRA_LIBRARY_ROOT = "library_root"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CACHE_VERSION = "cache_version"
        private const val STATE_PAGE_INDEX = "page_index"
        private const val STATE_PAGE_FRACTION = "page_fraction"
        private const val MAX_BITMAP_PIXELS = 4_000_000L
        private const val PAGE_BITMAP_MEMORY_BUDGET_BYTES = 80L * 1024L * 1024L
        private const val PAGE_CACHE_WRITE_QUEUE_CAPACITY = 1
        private const val READER_DETAIL_WIDTH_MULTIPLIER = 1.25f
        private const val PAGE_DECODE_WORKERS = 2
        private const val PREFETCH_BACKWARD_PAGE_COUNT = 2
        private const val PREFETCH_FORWARD_PAGE_COUNT = 3
        private const val DEFAULT_PAGE_ASPECT = 1.45f
        private const val ASPECT_UPDATE_EPSILON = 0.002f
        private const val PAGE_CACHE_QUALITY = 100
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun intent(context: Context, rootUri: Uri, project: MangaLibraryProject): Intent =
            Intent(context, MangaReaderActivity::class.java)
                .putExtra(EXTRA_LIBRARY_ROOT, rootUri.toString())
                .putExtra(EXTRA_PROJECT_ID, project.metadata.projectId)
                .putExtra(EXTRA_TITLE, project.metadata.title)
                .putExtra(EXTRA_CACHE_VERSION, project.metadata.createdAtEpochMillis)
    }
}
