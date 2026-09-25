package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.posthog.android.replay.PostHogRedrawOverMask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screenshot mode: renders what sits IN FRONT of the masks into a transparent bitmap laid out
 * like the captured window, for compositing over the masked screenshot.
 *
 * Which views are redrawn:
 *  - OUTSIDE a masked view's container: every view drawn after a mask (in draw order,
 *    Z-sorted like the framework) is redrawn automatically -- sheets, panels, in-layout
 *    dialogs, toolbars. This is what stops a photo mask from blacking out the UI in front.
 *  - INSIDE a masked view's container: only views marked with [PostHogRedrawOverMask]. That
 *    container is where untagged copies of the sensitive pixels live (a filtered copy, a
 *    blurred background); they are hidden today only because they fall inside the mask's
 *    rectangle, so they must never be redrawn automatically.
 *
 * Masked content inside a redrawn subtree is masked AGAIN on the overlay (the same pattern),
 * so e.g. a sheet holding one masked thumbnail still shows, with only the thumbnail hidden.
 * A subtree whose masking cannot be located (Compose) is not redrawn at all.
 *
 * `View.draw` must run on the main thread; capture and masking do not. The render is posted
 * to main and awaited briefly; on timeout the caller gets null and the frame is a plain
 * masked screenshot -- never a less private one.
 */
internal class RedrawOverlayRenderer(
    private val mainHandler: Handler,
    /** Mirrors the mask walk: true for any view the walk would mask. */
    private val isMasked: (View) -> Boolean,
    /** True for views whose masked children cannot be located (e.g. Compose): never redrawn. */
    private val isOpaqueToMasking: (View) -> Boolean = { false },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val painter = ScreenshotMaskPainter()

    fun render(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null
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
            synchronized(abandoned) {
                if (abandoned.get()) rendered?.recycle() else result = rendered
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

    private class Walk {
        val maskContainers = HashSet<ViewGroup>()
        var maskSeen = false
        val remask = mutableListOf<Rect>()
        var drew = false

        // Window position of the walk root. Re-mask rects come from getGlobalVisibleRect (window
        // coordinates) but the overlay is laid out relative to the root; for a decor view the
        // offset is zero, but never assume that.
        val rootOffset = IntArray(2)
    }

    private fun renderOnMain(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        if (!root.isAttachedToWindow) return null
        val walk = Walk()
        root.getLocationInWindow(walk.rootOffset)
        // Pre-pass: containers of every visible masked view. Needed up front so a sibling drawn
        // BEFORE the masked view (e.g. a background copy of the photo) is still classified as
        // inside the container, whatever other masks appeared earlier in the tree.
        collectMaskContainers(root, walk)
        if (walk.maskContainers.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val sx = bitmapWidth.toFloat() / sourceWidth
        val sy = bitmapHeight.toFloat() / sourceHeight
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(sx, sy)
            walk(root, canvas, walk, insideContainer = false)
            if (walk.remask.isNotEmpty()) {
                val c = Canvas(bitmap)
                c.scale(sx, sy)
                for (r in walk.remask) painter.draw(c, RectF(r), 10f, 10f, 1f)
            }
        } catch (_: Throwable) {
            bitmap.recycle()
            return null
        }
        if (!walk.drew) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun collectMaskContainers(
        view: View,
        walk: Walk,
    ) {
        if (view.visibility != View.VISIBLE) return
        if (isMasked(view)) {
            (view.parent as? ViewGroup)?.let { walk.maskContainers.add(it) }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectMaskContainers(it, walk) }
        }
    }

    private fun walk(
        view: View,
        canvas: Canvas,
        walk: Walk,
        insideContainer: Boolean,
    ) {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return
        if (isMasked(view)) {
            walk.maskSeen = true // never drawn; everything after it is "in front"
            return
        }
        val redraw = PostHogRedrawOverMask.isMarked(view) || (walk.maskSeen && !insideContainer)
        if (redraw) {
            if (isOpaqueToMasking(view) || containsOpaque(view)) return
            view.draw(canvas)
            walk.drew = true
            collectMasked(view, walk)
            return
        }
        if (view !is ViewGroup) return

        val childInside = insideContainer || view in walk.maskContainers
        for (child in zOrderedChildren(view)) {
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
                walk(child, canvas, walk, childInside)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    // Framework draw order: index order, stably re-sorted by Z (elevation + translationZ).
    private fun zOrderedChildren(group: ViewGroup): List<View> {
        val children = (0 until group.childCount).mapNotNull { group.getChildAt(it) }
        return if (children.any { it.z != 0f }) children.sortedBy { it.z } else children
    }

    private fun collectMasked(
        view: View,
        walk: Walk,
    ) {
        if (view.visibility != View.VISIBLE) return
        if (isMasked(view)) {
            val r = Rect()
            if (view.getGlobalVisibleRect(r)) {
                r.offset(-walk.rootOffset[0], -walk.rootOffset[1])
                walk.remask.add(r)
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectMasked(it, walk) }
        }
    }

    private fun containsOpaque(view: View): Boolean {
        if (isOpaqueToMasking(view)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i) ?: continue
                if (containsOpaque(c)) return true
            }
        }
        return false
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 250L
    }
}
