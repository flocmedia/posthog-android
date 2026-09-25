package com.posthog.android.replay.internal

import android.app.Activity
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

/**
 * Redraw-over-mask renders ONLY marked views, at their on-screen position, and never a
 * marked view that would smuggle a `ph-no-capture` view back into the frame.
 *
 * MUTATION EVIDENCE — each edit applied to RedrawOverlayRenderer.kt, test watched go red:
 *  M1  drop the `if (containsNoCapture(view)) return false` guard
 *      => aMarkedViewHoldingAMaskedViewIsNotDrawn FAILS: the masked child's pixels come back.
 *  M2  in walk(), draw every VISIBLE view instead of only marked ones
 *      => anUnmarkedSiblingIsNeverDrawn FAILS: the allowlist becomes a denylist, which is
 *      exactly how an untagged copy of the photo would leak.
 *  M3  drop `canvas.translate(child.left - scrollX, child.top - scrollY)`
 *      => aMarkedViewIsDrawnAtItsOnScreenPosition FAILS (drawn at the origin).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class RedrawOverlayRendererTest {
    private val marked = mutableListOf<View>()

    @After
    fun tearDown() {
        marked.forEach { PostHogRedrawOverMask.unmark(it) }
    }

    private fun mark(v: View) {
        PostHogRedrawOverMask.mark(v)
        marked.add(v)
    }

    private fun renderer() =
        RedrawOverlayRenderer(Handler(Looper.getMainLooper()), isNoCapture = { v ->
            (v.tag as? String)?.contains("ph-no-capture") == true
        })

    private fun box(
        color: Int,
        left: Int,
        top: Int,
        size: Int,
    ): View {
        val v = View(Robolectric.buildActivity(Activity::class.java).get())
        v.setBackgroundColor(color)
        v.layoutParams = FrameLayout.LayoutParams(size, size).apply { leftMargin = left; topMargin = top }
        return v
    }

    private fun rootWith(vararg children: View): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        children.forEach { (it.parent as? FrameLayout)?.removeView(it); root.addView(it) }
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 200, 200)
        return root
    }

    @Test
    fun nothingMarkedRendersNothing() {
        val root = rootWith(box(Color.RED, 10, 10, 20))
        assertNull(renderer().render(root, 200, 200, 200, 200))
    }

    @Test
    fun aMarkedViewIsDrawnAtItsOnScreenPosition() {
        val sticker = box(Color.RED, 50, 60, 20)
        val root = rootWith(sticker)
        mark(sticker)

        val out = assertNotNull(renderer().render(root, 200, 200, 200, 200))

        assertEquals(Color.RED, out.getPixel(55, 65), "inside the sticker's on-screen bounds")
        assertEquals(Color.TRANSPARENT, out.getPixel(5, 5), "nothing drawn outside it")
    }

    @Test
    fun anUnmarkedSiblingIsNeverDrawn() {
        val photoCopy = box(Color.BLUE, 0, 0, 200) // e.g. an untagged filtered copy of the photo
        val sticker = box(Color.RED, 50, 60, 20)
        val root = rootWith(photoCopy, sticker)
        mark(sticker)

        val out = assertNotNull(renderer().render(root, 200, 200, 200, 200))

        assertEquals(Color.TRANSPARENT, out.getPixel(150, 150), "the unmarked sibling must not be redrawn")
        assertEquals(Color.RED, out.getPixel(55, 65))
    }

    @Test
    fun aMarkedViewHoldingAMaskedViewIsNotDrawn() {
        val container = FrameLayout(Robolectric.buildActivity(Activity::class.java).get())
        container.layoutParams = FrameLayout.LayoutParams(200, 200)
        val photo = box(Color.BLUE, 0, 0, 200).apply { tag = "ph-no-capture" }
        container.addView(photo)
        val root = rootWith(container)
        mark(container)

        assertNull(
            renderer().render(root, 200, 200, 200, 200),
            "a marked container that holds a masked view must be skipped, not drawn",
        )
    }
}
