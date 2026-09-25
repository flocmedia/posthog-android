package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pattern must hide exactly what the old solid mask hid, and look like the spec.
 *
 * MUTATION EVIDENCE — each edit applied to ScreenshotMaskPainter.kt, test watched go red:
 *  M1  delete the opaque base `drawRoundRect(rect, ..., base)`
 *      => noPixelOfTheSourceSurvivesInsideTheMask FAILS: the even rows expose the source.
 *  M2  start the band loop at `rect.top` and step by `band` (every row blue)
 *      => rowsAlternateOrangeAndBlue FAILS.
 *  M3  drop the drawCircle call  => theCentreIsYellow FAILS.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ScreenshotMaskPainterTest {
    private val source = Color.rgb(0x12, 0x34, 0x56) // stands in for the photo

    private fun masked(rect: RectF): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(source)
        ScreenshotMaskPainter().draw(Canvas(bmp), rect, 10f, 10f, 1f)
        return bmp
    }

    @Test
    fun noPixelOfTheSourceSurvivesInsideTheMask() {
        val rect = RectF(50f, 50f, 350f, 350f)
        val bmp = masked(rect)
        // Everything inside the rounded rect, away from the 10px corners, must be covered.
        var leaked = 0
        for (y in 55 until 345) for (x in 55 until 345) if (bmp.getPixel(x, y) == source) leaked++
        assertEquals(0, leaked, "source pixels visible inside the mask")
        assertEquals(source, bmp.getPixel(10, 10), "and nothing painted outside it")
    }

    @Test
    fun rowsAlternateOrangeAndBlue() {
        val bmp = masked(RectF(0f, 0f, 400f, 400f))
        assertEquals(ScreenshotMaskPainter.ORANGE, bmp.getPixel(20, 12), "row 0 is orange")
        assertEquals(ScreenshotMaskPainter.BLUE, bmp.getPixel(20, 36), "row 1 is blue")
        assertEquals(ScreenshotMaskPainter.ORANGE, bmp.getPixel(20, 60), "row 2 is orange")
    }

    @Test
    fun theCentreIsYellow() {
        val bmp = masked(RectF(0f, 0f, 400f, 400f))
        assertEquals(ScreenshotMaskPainter.YELLOW, bmp.getPixel(200, 200))
        assertTrue(bmp.getPixel(20, 200) != ScreenshotMaskPainter.YELLOW, "circle, not a fill")
    }
}
