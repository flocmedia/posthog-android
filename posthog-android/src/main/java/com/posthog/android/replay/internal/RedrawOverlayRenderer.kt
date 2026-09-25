package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.posthog.android.replay.PostHogRedrawOverMask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders the views marked with [PostHogRedrawOverMask] into a transparent bitmap laid out
 * exactly like the captured window, for compositing over a masked screenshot.
 *
 * `View.draw` must run on the main thread, but screenshot capture and masking run off it. So
 * the render is posted to main and awaited briefly; on timeout the caller gets null and the
 * frame is simply masked as usual. The bitmap is owned by whichever side finishes last, so
 * it is never recycled while the main thread is still drawing into it.
 */
internal class RedrawOverlayRenderer(
    private val mainHandler: Handler,
    private val isNoCapture: (View) -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun render(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        if (PostHogRedrawOverMask.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0 ||
            sourceWidth <= 0 || sourceHeight <= 0
        ) {
            return null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return renderOnMain(root, bitmapWidth, bitmapHeight, sourceWidth, sourceHeight)
        }

        val latch = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        var result: Bitmap? = null
        mainHandler.post {
            val rendered =
                try {
                    renderOnMain(root, bitmapWidth, bitmapHeight, sourceWidth, sourceHeight)
                } catch (_: Throwable) {
                    null
                }
            // Whoever loses the race owns cleanup: if the waiter already gave up, nobody
            // else will ever read this bitmap.
            synchronized(abandoned) {
                if (abandoned.get()) {
                    rendered?.recycle()
                } else {
                    result = rendered
                }
            }
            latch.countDown()
        }
        val finished =
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        synchronized(abandoned) {
            if (!finished) {
                abandoned.set(true)
                return null
            }
            return result
        }
    }

    private fun renderOnMain(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        if (!root.isAttachedToWindow) return null
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmapWidth.toFloat() / sourceWidth, bitmapHeight.toFloat() / sourceHeight)
        var drewAnything = false
        try {
            drewAnything = walk(root, canvas)
        } catch (_: Throwable) {
            drewAnything = false
        }
        if (!drewAnything) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /**
     * Depth-first in drawing order, carrying the same transform the framework applies:
     * each child is translated by its layout position minus the parent's scroll, then by its
     * own matrix (translation / scale / rotation about its pivot), and clipped to the parent
     * when the parent clips its children. A marked view draws its whole subtree and stops the
     * descent; nothing unmarked is ever drawn.
     */
    private fun walk(
        view: View,
        canvas: Canvas,
    ): Boolean {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return false
        if (PostHogRedrawOverMask.isMarked(view)) {
            // Fail closed: a marked view must never smuggle a masked view back into the frame.
            if (containsNoCapture(view)) return false
            view.draw(canvas)
            return true
        }
        if (view !is ViewGroup) return false

        var drew = false
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            val save = canvas.save()
            try {
                if (view.clipChildren) {
                    canvas.clipRect(view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height)
                }
                canvas.translate((child.left - view.scrollX).toFloat(), (child.top - view.scrollY).toFloat())
                val matrix = child.matrix
                if (!matrix.isIdentity) canvas.concat(matrix)
                if (child.alpha < 1f) {
                    canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), (child.alpha * 255).toInt())
                }
                if (walk(child, canvas)) drew = true
            } finally {
                canvas.restoreToCount(save)
            }
        }
        return drew
    }

    private fun containsNoCapture(view: View): Boolean {
        if (isNoCapture(view)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                if (containsNoCapture(child)) return true
            }
        }
        return false
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 250L
    }
}
