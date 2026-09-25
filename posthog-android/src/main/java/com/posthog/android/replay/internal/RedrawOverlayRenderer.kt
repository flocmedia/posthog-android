package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.posthog.android.replay.PostHogRedrawOverMask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** The rendered overlay: [bitmap] belongs at ([left], [top]) in the captured bitmap. */
internal class RedrawOverlay(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    private val owner: RedrawOverlayRenderer,
) {
    /** Hands the bitmap back for reuse. Call exactly once, when the capture is finished with it. */
    fun release() = owner.releaseBitmap(bitmap)
}

/**
 * Screenshot mode: renders what sits IN FRONT of the masks, for compositing over the masked
 * screenshot.
 *
 * Which views are redrawn:
 *  - OUTSIDE a masked view's container: every view drawn after a mask (draw order, Z-sorted
 *    like the framework) is redrawn automatically -- sheets, panels, in-layout dialogs.
 *  - INSIDE a masked view's container: only views marked with [PostHogRedrawOverMask]. That
 *    container holds untagged copies of the sensitive pixels, hidden today only because they
 *    fall inside the mask's rectangle, so they are never redrawn automatically.
 * Masked content inside redrawn UI is masked again with the same pattern. A subtree whose
 * masks cannot be located (Compose) is not redrawn at all.
 *
 * COST CONTROL -- this runs on the MAIN thread, and was measured at p50 18.5 ms / p90 63 ms on
 * a flagship before these three measures (budget phones are several times slower):
 *  1. Clip: the overlay is only ever composited inside the mask rectangles, so the canvas is
 *     clipped to them and any view whose bounds fall outside is skipped before it is drawn.
 *  2. Right-size + reuse: the bitmap covers only the union of the mask rectangles, not the
 *     window, and one spare is recycled between captures instead of allocating per frame.
 *  3. Back off: every render is timed. Over [budgetMs] the next 2^streak captures skip the
 *     redraw; after [disableAfterSlow] slow renders in a row it is off for the session. Skipped
 *     frames fall back to the plain mask -- stickers briefly hidden, never a stall.
 *
 * `View.draw` must run on the main thread; capture and masking do not. The render is posted to
 * main and awaited briefly; on timeout the caller gets null (a plain masked frame).
 */
internal class RedrawOverlayRenderer(
    private val mainHandler: Handler,
    /** Mirrors the mask walk: true for any view the walk would mask. */
    private val isMasked: (View) -> Boolean,
    /** True for views whose masked children cannot be located (e.g. Compose): never redrawn. */
    private val isOpaqueToMasking: (View) -> Boolean = { false },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val budgetMs: Double = DEFAULT_BUDGET_MS,
    private val disableAfterSlow: Int = DEFAULT_DISABLE_AFTER_SLOW,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val painter = ScreenshotMaskPainter()

    private val budgetLock = Any()
    private var slowStreak = 0
    private var skipRemaining = 0
    @Volatile var disabledForSession: Boolean = false
        private set

    private val poolLock = Any()
    private var spare: Bitmap? = null

    fun render(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): RedrawOverlay? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null
        if (disabledForSession) return null
        synchronized(budgetLock) {
            if (skipRemaining > 0) {
                skipRemaining--
                return null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return renderTimed(root, bitmapWidth, bitmapHeight, sourceWidth, sourceHeight)
        }
        val latch = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        var result: RedrawOverlay? = null
        mainHandler.post {
            val rendered =
                try {
                    renderTimed(root, bitmapWidth, bitmapHeight, sourceWidth, sourceHeight)
                } catch (_: Throwable) {
                    null
                }
            synchronized(abandoned) {
                if (abandoned.get()) rendered?.release() else result = rendered
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

    private fun renderTimed(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): RedrawOverlay? {
        val start = nanoTime()
        try {
            return renderOnMain(root, bitmapWidth, bitmapHeight, sourceWidth, sourceHeight)
        } finally {
            recordCost((nanoTime() - start) / 1_000_000.0)
        }
    }

    private fun recordCost(ms: Double) {
        synchronized(budgetLock) {
            if (ms > budgetMs) {
                slowStreak++
                skipRemaining = 1 shl min(slowStreak, MAX_BACKOFF_SHIFT)
                if (slowStreak >= disableAfterSlow) {
                    disabledForSession = true
                    log("Session Replay redraw-over-mask disabled for the session: $slowStreak renders over ${budgetMs}ms in a row.")
                }
            } else {
                slowStreak = 0
            }
        }
        log("Session Replay redraw-over-mask took ${"%.2f".format(ms)}ms.")
    }

    internal fun releaseBitmap(bitmap: Bitmap) {
        synchronized(poolLock) {
            if (spare == null && !bitmap.isRecycled) {
                spare = bitmap
                return
            }
        }
        bitmap.recycle()
    }

    private fun acquireBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        synchronized(poolLock) {
            val s = spare
            if (s != null && !s.isRecycled && s.width == width && s.height == height) {
                spare = null
                s.eraseColor(Color.TRANSPARENT)
                return s
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private class Walk {
        val maskContainers = HashSet<ViewGroup>()
        val maskRects = mutableListOf<Rect>()
        var maskSeen = false
        val remask = mutableListOf<Rect>()
        var drew = false

        // Window position of the walk root: rects come from getGlobalVisibleRect (window
        // coordinates) but the overlay is laid out relative to the root.
        val rootOffset = IntArray(2)
    }

    private fun renderOnMain(
        root: View,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): RedrawOverlay? {
        if (!root.isAttachedToWindow) return null
        val walk = Walk()
        root.getLocationInWindow(walk.rootOffset)
        // Pre-pass: every visible masked view's container AND rectangle. Containers are needed up
        // front so a sibling drawn BEFORE the masked view (a background copy of the photo) is still
        // classified as inside; rectangles bound and clip all the drawing that follows.
        collectMasks(root, walk)
        if (walk.maskContainers.isEmpty() || walk.maskRects.isEmpty()) return null

        val union = Rect(walk.maskRects[0])
        for (r in walk.maskRects) union.union(r)
        if (!union.intersect(0, 0, sourceWidth, sourceHeight)) return null

        val sx = bitmapWidth.toFloat() / sourceWidth
        val sy = bitmapHeight.toFloat() / sourceHeight
        val left = floor(union.left * sx)
        val top = floor(union.top * sy)
        val w = (ceil(union.right * sx) - left).toInt().coerceAtLeast(1)
        val h = (ceil(union.bottom * sy) - top).toInt().coerceAtLeast(1)

        val bitmap = acquireBitmap(w, h)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-left, -top)
            canvas.scale(sx, sy)
            val clip = Region()
            for (r in walk.maskRects) clip.op(r, Region.Op.UNION)
            canvas.clipPath(clip.boundaryPath)
            walk(root, canvas, walk, insideContainer = false)
            for (r in walk.remask) painter.draw(canvas, RectF(r), 10f, 10f, 1f)
        } catch (e: Throwable) {
            releaseBitmap(bitmap)
            log("Session Replay redraw-over-mask render failed: $e.")
            return null
        }
        if (!walk.drew) {
            releaseBitmap(bitmap)
            return null
        }
        return RedrawOverlay(bitmap, left, top, this)
    }

    private fun collectMasks(
        view: View,
        walk: Walk,
    ) {
        if (view.visibility != View.VISIBLE) return
        if (isMasked(view)) {
            (view.parent as? ViewGroup)?.let { walk.maskContainers.add(it) }
            val r = Rect()
            if (view.getGlobalVisibleRect(r)) {
                r.offset(-walk.rootOffset[0], -walk.rootOffset[1])
                walk.maskRects.add(r)
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectMasks(it, walk) }
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
                // Cull before any work: a child that overlaps no mask rectangle contributes nothing.
                // quickReject alone is not enough -- it tests the clip's BOUNDING BOX, so with two
                // masks apart everything between them would still be fully drawn, then clipped.
                @Suppress("DEPRECATION")
                val outsideClipBounds =
                    canvas.quickReject(0f, 0f, child.width.toFloat(), child.height.toFloat(), Canvas.EdgeType.BW)
                if (outsideClipBounds || !overlapsAnyMask(child, walk)) {
                    // Still has to be accounted for in draw order if it IS a mask.
                    if (isMasked(child)) walk.maskSeen = true
                    continue
                }
                if (child.alpha < 1f) {
                    canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), (child.alpha * 255).toInt())
                }
                walk(child, canvas, walk, childInside)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    private val scratch = Rect()

    private fun overlapsAnyMask(
        view: View,
        walk: Walk,
    ): Boolean {
        if (!view.getGlobalVisibleRect(scratch)) return false
        scratch.offset(-walk.rootOffset[0], -walk.rootOffset[1])
        for (r in walk.maskRects) if (Rect.intersects(r, scratch)) return true
        return false
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

    internal companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 250L

        /** One frame at 120 Hz: a redraw must not cost a frame even on a fast display. */
        const val DEFAULT_BUDGET_MS: Double = 8.0
        const val DEFAULT_DISABLE_AFTER_SLOW: Int = 4
        const val MAX_BACKOFF_SHIFT: Int = 5
    }
}
