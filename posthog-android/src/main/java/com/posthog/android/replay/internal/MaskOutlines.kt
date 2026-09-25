package com.posthog.android.replay.internal

import android.graphics.Canvas
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

    /**
     * Maps [view]'s four local corners into its root's coordinate space (the space
     * getGlobalVisibleRect reports mask rects in), applying every transform on the way up, so a
     * rotated or scaled view yields its true quad rather than a loose bounding box.
     * [out] receives x0,y0 .. x3,y3 (top-left, top-right, bottom-right, bottom-left).
     */
    fun quadInRoot(
        view: View,
        out: FloatArray,
    ) {
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
        var current: View = view
        while (true) {
            val matrix = current.matrix
            if (!matrix.isIdentity) {
                matrix.mapPoints(out)
            }
            val parent = current.parent as? View
            val dx = current.left - (parent?.scrollX ?: 0)
            val dy = current.top - (parent?.scrollY ?: 0)
            for (i in 0 until 8 step 2) {
                out[i] += dx
                out[i + 1] += dy
            }
            current = parent ?: break
        }
    }

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
