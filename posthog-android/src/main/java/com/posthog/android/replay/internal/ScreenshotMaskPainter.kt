package com.posthog.android.replay.internal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Paints the screenshot-mode mask: an orange base, blue bands on alternate rows, and a
 * yellow circle in the centre.
 *
 * Deliberately loud, so a masked region reads as "redacted" at a glance and is never
 * mistaken for dark content -- black stickers vanished into the previous solid black mask.
 *
 * PRIVACY INVARIANT: coverage is exactly the old solid rounded rectangle. The opaque orange
 * base is drawn over the whole rounded rect FIRST; the bands and circle are then drawn
 * clipped inside it, so no pattern gap can ever expose the pixels beneath.
 *
 * Confined to the PixelCopy handler thread, like the rest of mask painting.
 */
internal class ScreenshotMaskPainter {
    private val base = Paint().apply { color = ORANGE }
    private val stripe = Paint().apply { color = BLUE }
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
    private val clip = Path()

    fun draw(
        canvas: Canvas,
        rect: RectF,
        radiusX: Float,
        radiusY: Float,
        scaleY: Float,
    ) {
        canvas.drawRoundRect(rect, radiusX, radiusY, base)
        val save = canvas.save()
        try {
            clip.reset()
            clip.addRoundRect(rect, radiusX, radiusY, Path.Direction.CW)
            canvas.clipPath(clip)
            val band = (STRIPE_PX * scaleY).coerceAtLeast(1f)
            var top = rect.top + band // even rows keep the orange base, odd rows are blue
            while (top < rect.bottom) {
                canvas.drawRect(rect.left, top, rect.right, minOf(top + band, rect.bottom), stripe)
                top += band * 2
            }
            val radius = minOf(rect.width(), rect.height()) * CIRCLE_FRACTION
            if (radius >= 1f) canvas.drawCircle(rect.centerX(), rect.centerY(), radius, circle)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    internal companion object {
        val ORANGE: Int = Color.rgb(0xF9, 0x73, 0x16)
        val BLUE: Int = Color.rgb(0x25, 0x63, 0xEB)
        val YELLOW: Int = Color.rgb(0xFA, 0xCC, 0x15)
        const val STRIPE_PX: Float = 24f
        const val CIRCLE_FRACTION: Float = 0.2f
    }
}
