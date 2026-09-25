package com.posthog.android.replay.internal

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup

/**
 * Outlines of the views drawn in front of a screenshot mask.
 *
 * A mask is painted over the flat PixelCopy bitmap, so it also hides every view in front of the
 * masked one (stickers over a photo, a drawer sliding over it). Redrawing those views costs a
 * main-thread View.draw per capture; an outline costs a few float ops per view, reads no pixels,
 * and paints only strokes, so it can never reveal what the mask covers.
 */
internal object MaskOutlines {
    // A screen with more in-front views than this still gets masked; the rest go unoutlined.
    const val MAX_OUTLINES = 256

    /** Whether [view] paints anything of its own: a leaf, or a container with a background. */
    fun drawsItself(view: View): Boolean = view !is ViewGroup || view.background != null

    fun intersectsAny(
        quad: FloatArray,
        rects: List<Rect>,
    ): Boolean {
        var left = quad[0]
        var right = quad[0]
        var top = quad[1]
        var bottom = quad[1]
        for (i in 2 until 8 step 2) {
            left = minOf(left, quad[i])
            right = maxOf(right, quad[i])
            top = minOf(top, quad[i + 1])
            bottom = maxOf(bottom, quad[i + 1])
        }
        for (rect in rects) {
            if (left < rect.right && right > rect.left && top < rect.bottom && bottom > rect.top) {
                return true
            }
        }
        return false
    }
}

/**
 * Collects outline quads during ONE mask walk. The walk tells it when it enters and leaves each
 * view, so it keeps the current root-to-view path and caches each depth's accumulated transform:
 * a view's quad costs one concat + map instead of a climb to the root (two native Matrix calls
 * per ancestor), which dominated on a drawer full of grid cells.
 *
 * It also culls: a clipping ViewGroup that misses every mask cannot have a descendant drawn over
 * one, so nothing under it is examined (the walk still descends it looking for masks). A mask
 * found later invalidates the cull, since it could lie inside that subtree.
 */
internal class OutlineCollector(
    private val isStable: (View) -> Boolean,
    private val isOpaque: (View) -> Boolean,
) {
    val outlines: MutableList<FloatArray> = mutableListOf()
    var nanos: Long = 0L
        private set

    private val path = ArrayList<View>()
    private val matrices = ArrayList<Matrix>()
    private var validDepth = -1
    private var cullDepth = -1
    private var cullRectCount = 0
    private val scratch = FloatArray(8)
    private val chain = ArrayList<View>()

    fun enter(view: View) {
        path.add(view)
        val depth = path.size - 1
        if (validDepth >= depth) validDepth = depth - 1
        // A view at or above the culled group's depth means the walk has left that subtree.
        if (cullDepth >= depth) cullDepth = -1
    }

    fun exit() {
        path.removeAt(path.size - 1)
        if (validDepth >= path.size) validDepth = path.size - 1
    }

    /**
     * Called for the view at the top of the path once the walk has classified it.
     * [rectsBefore] is the mask count when the walk reached it: zero means nothing is behind it
     * yet, and a grown count means the view masked itself.
     */
    fun consider(
        view: View,
        rectsBefore: Int,
        rects: List<Rect>,
    ) {
        if (rectsBefore == 0 || rects.size != rectsBefore || outlines.size >= MaskOutlines.MAX_OUTLINES) {
            return
        }
        val depth = path.size - 1
        if (cullDepth in 0 until depth && rects.size == cullRectCount) {
            return
        }
        val started = System.nanoTime()
        try {
            if (view.width <= 0 || view.height <= 0 || isOpaque(view)) {
                return
            }
            val clips = view is ViewGroup && view.clipChildren
            val draws = MaskOutlines.drawsItself(view)
            if (!draws && !clips) {
                return
            }
            if (!isStable(view)) {
                return
            }
            quad(depth, scratch)
            if (MaskOutlines.intersectsAny(scratch, rects)) {
                if (draws) outlines.add(scratch.copyOf())
            } else if (clips) {
                cullDepth = depth
                cullRectCount = rects.size
            }
        } finally {
            nanos += System.nanoTime() - started
        }
    }

    // The view's four corners (TL, TR, BR, BL) in the root's space, the space
    // getGlobalVisibleRect reports mask rects in.
    private fun quad(
        depth: Int,
        out: FloatArray,
    ) {
        ensureMatrices(depth)
        val view = path[depth]
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        out[0] = 0f
        out[1] = 0f
        out[2] = w
        out[3] = 0f
        out[4] = w
        out[5] = h
        out[6] = 0f
        out[7] = h
        matrices[depth].mapPoints(out)
    }

    // matrices[i] maps path[i]'s local space to the root: parent's matrix, then this view's
    // offset in the parent (minus the parent's scroll), then its own transform.
    private fun ensureMatrices(depth: Int) {
        for (i in validDepth + 1..depth) {
            while (matrices.size <= i) matrices.add(Matrix())
            val m = matrices[i]
            val view = path[i]
            if (i == 0) {
                rootMatrix(view, m)
            } else {
                m.set(matrices[i - 1])
                val parent = path[i - 1]
                m.preTranslate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
                val own = view.matrix
                if (!own.isIdentity) m.preConcat(own)
            }
        }
        validDepth = depth
    }

    // The walk may start below the window root; climb once so path[0] is in root space too.
    private fun rootMatrix(
        view: View,
        m: Matrix,
    ) {
        chain.clear()
        var current: View? = view
        while (current != null) {
            chain.add(current)
            current = current.parent as? View
        }
        m.reset()
        for (j in chain.indices.reversed()) {
            val v = chain[j]
            val parent = chain.getOrNull(j + 1)
            m.preTranslate((v.left - (parent?.scrollX ?: 0)).toFloat(), (v.top - (parent?.scrollY ?: 0)).toFloat())
            val own = v.matrix
            if (!own.isIdentity) m.preConcat(own)
        }
        chain.clear()
    }
}

/**
 * Strokes outlines over already-painted masks, clipped to the masks: outside them the
 * screenshot shows the real view already. A dark halo under a white line reads on every colour
 * of the mask pattern. Not thread-safe; one capture paints at a time, like [ScreenshotMaskPainter].
 */
internal class MaskOutlinePainter {
    private val clip = Path()
    private val outline = Path()
    private val maskRect = RectF()

    private val halo =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = HALO
            strokeJoin = Paint.Join.ROUND
        }
    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = LINE
            strokeJoin = Paint.Join.ROUND
        }

    /**
     * [maskRects] and [quads] are in source (view) pixels; [scaleX]/[scaleY] map them onto the
     * canvas. [strokePx] is the line width in canvas pixels.
     */
    fun draw(
        canvas: Canvas,
        maskRects: List<Rect>,
        quads: List<FloatArray>,
        scaleX: Float,
        scaleY: Float,
        strokePx: Float,
    ) {
        if (maskRects.isEmpty() || quads.isEmpty()) {
            return
        }
        clip.rewind()
        for (rect in maskRects) {
            maskRect.set(rect.left * scaleX, rect.top * scaleY, rect.right * scaleX, rect.bottom * scaleY)
            clip.addRect(maskRect, Path.Direction.CW)
        }
        outline.rewind()
        for (quad in quads) {
            outline.moveTo(quad[0] * scaleX, quad[1] * scaleY)
            outline.lineTo(quad[2] * scaleX, quad[3] * scaleY)
            outline.lineTo(quad[4] * scaleX, quad[5] * scaleY)
            outline.lineTo(quad[6] * scaleX, quad[7] * scaleY)
            outline.close()
        }
        halo.strokeWidth = strokePx * 2.5f
        line.strokeWidth = strokePx
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawPath(outline, halo)
        canvas.drawPath(outline, line)
        canvas.restoreToCount(save)
    }

    internal companion object {
        const val HALO = 0x99111827.toInt()
        const val LINE = 0xFFFFFFFF.toInt()
    }
}
