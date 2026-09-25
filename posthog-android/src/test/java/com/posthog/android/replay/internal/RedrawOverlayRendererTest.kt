package com.posthog.android.replay.internal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.replay.PostHogRedrawOverMask
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screen, back to front:
 *   behind  (outside the photo's container, drawn BEFORE the mask)        -> never redrawn
 *   canvas  = photo(masked) + photoCopy(unmarked) + sticker(marked)        -> only the sticker
 *   sheet   (outside the container, drawn AFTER the mask) + thumb(masked)  -> redrawn, thumb re-masked
 *
 * MUTATION EVIDENCE — each edit applied to RedrawOverlayRenderer.kt, test watched go red:
 *  M1  `val redraw = isMarked || (maskSeen && !insideContainer)` -> drop `&& !insideContainer`
 *      => anUnmarkedCopyInsideThePhotosContainerIsNeverRedrawn FAILS: the photo copy leaks.
 *  M2  skip the pre-pass (collectMaskContainers) -> containers learned only as the walk meets
 *      the mask => aBackgroundCopyDrawnBeforeThePhotoIsNeverRedrawn FAILS once an earlier mask
 *      has set maskSeen.
 *  M3  drop `collectMasked(view, walk.remask)` => aMaskedThumbnailInsideRedrawnUiIsMaskedAgain
 *      FAILS: the thumbnail's own pixels come back.
 *  M4  drop the `walk.maskSeen = true` line => uiInFrontOfTheMaskIsRedrawnAutomatically FAILS.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class RedrawOverlayRendererTest {
    private val marked = mutableListOf<View>()
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @After
    fun tearDown() = marked.forEach { PostHogRedrawOverMask.unmark(it) }

    private val PHOTO = Color.BLUE
    private val COPY = Color.GREEN
    private val STICKER = Color.RED
    private val SHEET = Color.MAGENTA
    private val THUMB = Color.CYAN
    private val BEHIND = Color.DKGRAY

    private fun box(color: Int, l: Int, t: Int, w: Int, h: Int, masked: Boolean = false) =
        View(activity).apply {
            setBackgroundColor(color)
            layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = l; topMargin = t }
            if (masked) tag = "ph-no-capture"
        }

    private fun group(l: Int, t: Int, w: Int, h: Int, vararg kids: View) =
        FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = l; topMargin = t }
            kids.forEach { addView(it) }
        }

    private class Scene(val root: FrameLayout, val sticker: View)

    private fun scene(withEarlierMask: Boolean = false): Scene {
        val sticker = box(STICKER, 50, 50, 20, 20)
        val canvas = group(0, 0, 200, 200,
            box(COPY, 0, 0, 200, 200),                     // background copy, BEFORE the photo
            box(PHOTO, 0, 0, 200, 200, masked = true),     // the photo
            box(COPY, 150, 60, 40, 40),                    // filtered copy, AFTER the photo
            sticker,
        )
        val sheet = group(0, 120, 200, 80, box(SHEET, 0, 0, 200, 80), box(THUMB, 10, 10, 30, 30, masked = true))
        val root = FrameLayout(activity)
        // A mask elsewhere, earlier in draw order, in its OWN parent -- so it sets maskSeen without
        // making the whole root a mask container (which would, correctly, disable auto-redraw).
        if (withEarlierMask) root.addView(group(0, 0, 1, 1, box(Color.BLACK, 0, 0, 1, 1, masked = true)))
        root.addView(box(BEHIND, 100, 0, 100, 40))
        root.addView(canvas)
        root.addView(sheet)
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 200, 200)
        return Scene(root, sticker)
    }

    private fun renderer() =
        RedrawOverlayRenderer(Handler(Looper.getMainLooper()), isMasked = { v -> (v.tag as? String)?.contains("ph-no-capture") == true })

    private fun render(s: Scene): Bitmap = assertNotNull(renderer().render(s.root, 200, 200, 200, 200))

    private fun isPattern(c: Int) = c == ScreenshotMaskPainter.ORANGE || c == ScreenshotMaskPainter.BLUE || c == ScreenshotMaskPainter.YELLOW

    @Test
    fun nothingMaskedRendersNothing() {
        val root = FrameLayout(activity).apply { addView(box(STICKER, 0, 0, 20, 20)) }
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 200, 200)
        assertNull(renderer().render(root, 200, 200, 200, 200))
    }

    @Test
    fun aMarkedStickerInsideTheContainerIsRedrawnInPlace() {
        val s = scene().also { PostHogRedrawOverMask.mark(it.sticker); marked += it.sticker }
        assertEquals(STICKER, render(s).getPixel(55, 55))
    }

    @Test
    fun anUnmarkedCopyInsideThePhotosContainerIsNeverRedrawn() {
        val s = scene().also { PostHogRedrawOverMask.mark(it.sticker); marked += it.sticker }
        val out = render(s)
        assertTrue(out.getPixel(170, 80) != COPY, "the filtered copy drawn AFTER the photo must not be redrawn")
        assertTrue(out.getPixel(10, 100) != COPY, "nor the background copy drawn BEFORE it")
    }

    @Test
    fun aBackgroundCopyDrawnBeforeThePhotoIsNeverRedrawn() {
        // An earlier mask elsewhere sets maskSeen before the walk reaches the canvas; without the
        // pre-pass the background copy would look like UI "in front" and be redrawn.
        val out = render(scene(withEarlierMask = true))
        assertTrue(out.getPixel(10, 100) != COPY)
    }

    @Test
    fun uiInFrontOfTheMaskIsRedrawnAutomatically() {
        assertEquals(SHEET, render(scene()).getPixel(100, 180), "the sheet is not marked, yet it is redrawn")
    }

    @Test
    fun uiBehindTheMaskIsNotRedrawn() {
        assertEquals(Color.TRANSPARENT, render(scene()).getPixel(150, 20))
    }

    @Test
    fun aMaskedThumbnailInsideRedrawnUiIsMaskedAgain() {
        val px = render(scene()).getPixel(25, 145) // thumb is at sheet(0,120)+(10,10), 30x30
        assertTrue(px != THUMB, "the thumbnail's own pixels must not come back")
        assertTrue(isPattern(px), "it is covered by the mask pattern, got #${Integer.toHexString(px)}")
    }

    @Test
    fun aSubtreeWhoseMasksCannotBeLocatedIsNotRedrawn() {
        val s = scene()
        val r = RedrawOverlayRenderer(
            Handler(Looper.getMainLooper()),
            isMasked = { v -> (v.tag as? String)?.contains("ph-no-capture") == true },
            isOpaqueToMasking = { v -> (v.background as? android.graphics.drawable.ColorDrawable)?.color == SHEET },
        )
        val out = r.render(s.root, 200, 200, 200, 200)
        assertTrue(out == null || out.getPixel(100, 180) != SHEET)
    }
}
