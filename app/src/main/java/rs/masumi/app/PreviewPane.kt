package rs.masumi.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Owns and asynchronously decodes the bitmap shown in one pipeline preview.
 * A generation counter discards stale work when the user changes pages while
 * decoding, and the displayed path is cached to avoid redundant full-page
 * decodes during durable-state refreshes.
 */
internal class PreviewPane(
    private val activity: Activity,
    private val imageView: ImageView,
    private val executor: Executor,
) {
    private var generation = 0
    private var displayedPath: Path? = null
    private var bitmap: Bitmap? = null

    fun render(requestedPath: Path?, onApplied: (Bitmap?) -> Unit) {
        generation += 1
        val current = generation
        if (requestedPath == null) {
            clearImage()
            onApplied(null)
            return
        }
        val existing = bitmap
        if (requestedPath == displayedPath && existing != null && !existing.isRecycled) {
            imageView.setImageBitmap(existing)
            imageView.visibility = View.VISIBLE
            onApplied(existing)
            return
        }
        executor.execute {
            val decoded = decode(requestedPath)
            activity.runOnUiThread {
                if (current != generation || activity.isFinishing || activity.isDestroyed) {
                    decoded?.recycle()
                    return@runOnUiThread
                }
                recycleBitmap()
                if (decoded != null) {
                    displayedPath = requestedPath
                    bitmap = decoded
                    imageView.setImageBitmap(decoded)
                    imageView.visibility = View.VISIBLE
                } else {
                    imageView.visibility = View.GONE
                }
                onApplied(decoded)
            }
        }
    }

    fun clear() {
        generation += 1
        clearImage()
    }

    private fun decode(path: Path): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1) * 2
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > target || bounds.outHeight / sampleSize > target * 3) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path.toString(),
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun clearImage() {
        recycleBitmap()
        imageView.visibility = View.GONE
    }

    private fun recycleBitmap() {
        imageView.setImageDrawable(null)
        displayedPath = null
        bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        bitmap = null
    }
}
