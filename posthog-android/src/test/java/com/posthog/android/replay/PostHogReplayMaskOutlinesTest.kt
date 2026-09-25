package com.posthog.android.replay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.internal.MaskOutlinePainter
import com.posthog.android.replay.internal.MaskOutlines
import com.posthog.android.replay.internal.OutlineCollector
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Outlines of views drawn in front of a screenshot mask (MaskOutlines + the walk's
 * recordOutlineIfInFront + MaskOutlinePainter).
 *
 * MUTATION EVIDENCE — each edit applied to production code, named test watched go red:
 *  M1  OutlineCollector.consider: drop the `rectsBefore == 0` guard
 *      => SURVIVES, and is an equivalent mutation: with no mask down yet, intersectsAny runs
 *         against an empty list and is false, so the guard is only a fast exit. The behaviour it
 *         looks like it protects is viewsBehindTheMaskAreNotOutlined, which intersectsAny holds.
 *  M2  OutlineCollector.consider: drop `rects.size != rectsBefore` (outline a view that masked itself)
 *      => aMaskedViewInFrontOfAnotherMaskIsNotOutlined FAILS.
 *  M3  MaskOutlines.drawsItself: return true for every view
 *      => onlyViewsInFrontThatDrawAndOverlapAreOutlined FAILS (the bare layout is outlined).
 *  M4  OutlineCollector.ensureMatrices: skip `m.preConcat(own)`
 *      => aRotatedViewYieldsItsRotatedQuad FAILS (axis-aligned corners).
 *  M5  OutlineCollector.ensureMatrices: `m.reset()` instead of `m.set(matrices[i - 1])`
 *      => nestedOffsetsAccumulateToRootCoordinates FAILS.
 *  M6  MaskOutlinePainter.draw: remove `canvas.clipPath(clip)`
 *      => strokesStayInsideTheMask FAILS (the stroke lands outside the mask).
 *  M7  OutlineCollector.consider: drop the MAX_OUTLINES check
 *      => outlinesAreCappedButMaskingIsNot FAILS.
 *  M8  OutlineCollector.consider: never set cullDepth
 *      => aClippingGroupOffTheMaskIsNotSearched FAILS.
 *  M9  OutlineCollector.consider: drop `&& rects.size == cullRectCount`
 *      => aMaskFoundInsideACulledGroupLiftsTheCull FAILS.
 *  Not covered here: runArmMaskCaptureLoop / the legacy walk passing collectOutlines = true.
 *  Those walks need a live PixelCopy; the capture harness covers them on-device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PostHogReplayMaskOutlinesTest {
    private lateinit var sut: PostHogReplayIntegration
    private lateinit var activity: Activity

    @BeforeTest
    fun setUp() {
        val config = PostHogAndroidConfig(API_KEY).apply { sessionReplayConfig.screenshot = true }
        sut = PostHogReplayIntegration(ApplicationProvider.getApplicationContext(), config, MainHandler())
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val attachInfo =
            View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }
                .get(activity.window.decorView)
        attachInfo.javaClass.getDeclaredField("mWindowVisibility").apply { isAccessible = true }
            .setInt(attachInfo, View.VISIBLE)
    }

    private fun leaf(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): ImageView = ImageView(activity).also { bounds[it] = Bounds(l, t, r, b) }

    private val bounds = HashMap<View, Bounds>()

    private data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int)

    // Mounts [children] in an absolute layout at the given bounds and returns the root.
    private fun mount(vararg children: View): FrameLayout {
        val root = FrameLayout(activity)
        children.forEach { root.addView(it) }
        activity.setContentView(root)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val parent = root.parent as View
        root.layout(0, 0, parent.width, parent.height)
        layoutChildren(root)
        return root
    }

    private fun layoutChildren(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            bounds[child]?.let { child.layout(it.l, it.t, it.r, it.b) }
            if (child is ViewGroup) layoutChildren(child)
        }
    }

    private fun photo(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): ImageView =
        ImageView(activity).also {
            it.tag = "ph-no-capture"
            bounds[it] = Bounds(l, t, r, b)
        }

    private fun walk(root: View): PostHogReplayIntegration.MaskWalk {
        val walk = PostHogReplayIntegration.MaskWalk(collectOutlines = true)
        with(sut) { findMaskableWidgets(root, walk) }
        return walk
    }

    private fun FloatArray.bounds(): Rect {
        val xs = listOf(this[0], this[2], this[4], this[6])
        val ys = listOf(this[1], this[3], this[5], this[7])
        return Rect(xs.min().toInt(), ys.min().toInt(), xs.max().toInt(), ys.max().toInt())
    }

    private fun rootOffset(root: View): IntArray {
        val rect = Rect()
        root.getGlobalVisibleRect(rect)
        return intArrayOf(rect.left, rect.top)
    }

    @Test
    fun onlyViewsInFrontThatDrawAndOverlapAreOutlined() {
        val sticker = leaf(100, 100, 200, 200)
        val bareLayout = FrameLayout(activity).also { bounds[it] = Bounds(0, 0, 400, 400) }
        val nested = leaf(250, 250, 300, 300)
        bareLayout.addView(nested)
        val farAway = leaf(0, 500, 50, 550)
        val root = mount(photo(0, 0, 400, 400), sticker, bareLayout, farAway)

        val walk = walk(root)

        assertEquals(1, walk.rects.size, "the photo is the only mask")
        val (ox, oy) = rootOffset(root).let { it[0] to it[1] }
        assertEquals(
            listOf(Rect(100 + ox, 100 + oy, 200 + ox, 200 + oy), Rect(250 + ox, 250 + oy, 300 + ox, 300 + oy)),
            walk.outlines.map { it.bounds() },
            "the sticker and the nested leaf; not the photo, the bare layout, or the view off the mask",
        )
    }

    @Test
    fun viewsBehindTheMaskAreNotOutlined() {
        val behind = leaf(100, 100, 200, 200)
        val root = mount(behind, photo(0, 0, 400, 400))

        assertTrue(walk(root).outlines.isEmpty())
    }

    @Test
    fun aMaskedViewInFrontOfAnotherMaskIsNotOutlined() {
        val root = mount(photo(0, 0, 400, 400), photo(50, 50, 150, 150))

        val walk = walk(root)

        assertEquals(2, walk.rects.size)
        assertTrue(walk.outlines.isEmpty(), "its own mask already covers it")
    }

    @Test
    fun aContainerWithABackgroundIsOutlined() {
        val drawer =
            FrameLayout(activity).also {
                it.background = ColorDrawable(Color.WHITE)
                bounds[it] = Bounds(0, 300, 400, 800)
            }
        val root = mount(photo(0, 0, 400, 400), drawer)

        val (ox, oy) = rootOffset(root).let { it[0] to it[1] }
        assertEquals(listOf(Rect(ox, 300 + oy, 400 + ox, 800 + oy)), walk(root).outlines.map { it.bounds() })
    }

    @Test
    fun aRotatedViewYieldsItsRotatedQuad() {
        val sticker = leaf(100, 100, 200, 200)
        val root = mount(photo(0, 0, 400, 400), sticker)
        sticker.rotation = 45f

        val quad = walk(root).outlines.single()
        val (ox, oy) = rootOffset(root).let { it[0] to it[1] }
        // Rotated 45deg about its centre (150,150): the top-left corner swings to the top-centre.
        val half = 50f * Math.sqrt(2.0).toFloat()
        assertTrue(abs(quad[0] - (150f + ox)) < 0.5f, "x0=${quad[0]}")
        assertTrue(abs(quad[1] - (150f - half + oy)) < 0.5f, "y0=${quad[1]}")
    }

    @Test
    fun nestedOffsetsAccumulateToRootCoordinates() {
        val outer = FrameLayout(activity).also { bounds[it] = Bounds(50, 60, 350, 360) }
        val inner = FrameLayout(activity).also { bounds[it] = Bounds(10, 20, 200, 200) }
        val sticker = leaf(5, 5, 25, 25)
        inner.addView(sticker)
        outer.addView(inner)
        val root = mount(photo(0, 0, 400, 400), outer)

        val (ox, oy) = rootOffset(root).let { it[0] to it[1] }
        assertEquals(
            Rect(65 + ox, 85 + oy, 85 + ox, 105 + oy),
            walk(root).outlines.single().bounds(),
        )
    }

    @Test
    fun aClippingGroupOffTheMaskIsNotSearched() {
        // Culling is a speed-up, not a behaviour: a child outside its clipping parent is already
        // dropped by the walk's visibility check. So observe the work itself -- which views the
        // collector examined.
        val group =
            FrameLayout(activity).also {
                it.background = ColorDrawable(Color.WHITE)
                bounds[it] = Bounds(0, 500, 400, 700)
            }
        val leaves = List(5) { leaf(10 * it, 10, 10 * it + 5, 15) }
        leaves.forEach { group.addView(it) }
        val root = mount(photo(0, 0, 400, 400), group)

        val examined = mutableListOf<View>()
        val walk = PostHogReplayIntegration.MaskWalk(collectOutlines = true)
        walk.outlineCollector =
            OutlineCollector(isStable = {
                examined.add(it)
                true
            }, isOpaque = { false })
        with(sut) { findMaskableWidgets(root, walk) }

        assertTrue(group in examined, "the group itself is examined")
        assertTrue(leaves.none { it in examined }, "nothing under it is")
    }

    @Test
    fun aMaskFoundInsideACulledGroupLiftsTheCull() {
        // The group misses the first mask (so it is culled), but holds a second mask and a view
        // drawn over that one.
        val group = FrameLayout(activity).also { bounds[it] = Bounds(0, 500, 400, 700) }
        group.addView(photo(0, 0, 200, 200))
        group.addView(leaf(50, 50, 100, 100))
        val root = mount(photo(0, 0, 400, 400), group)

        assertEquals(1, walk(root).outlines.size)
    }

    @Test
    fun outlinesAreCappedButMaskingIsNot() {
        val stickers = Array(MaskOutlines.MAX_OUTLINES + 20) { leaf(10, 10, 20, 20) }
        val mask2 = photo(0, 0, 50, 50)
        val root = mount(photo(0, 0, 400, 400), *stickers, mask2)

        val walk = walk(root)

        assertEquals(MaskOutlines.MAX_OUTLINES, walk.outlines.size)
        assertEquals(2, walk.rects.size, "a mask after the cap is still collected")
    }

    @Test
    fun compareModeWalkCollectsNothing() {
        val root = mount(photo(0, 0, 400, 400), leaf(100, 100, 200, 200))
        val walk = PostHogReplayIntegration.MaskWalk()
        with(sut) { findMaskableWidgets(root, walk) }

        assertTrue(walk.outlines.isEmpty())
    }

    @Test
    fun strokesStayInsideTheMask() {
        val background = Color.rgb(0x10, 0x80, 0x10)
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(background)
        // Mask covers the left half; the outlined square straddles it.
        val quad = floatArrayOf(20f, 20f, 80f, 20f, 80f, 80f, 20f, 80f)
        MaskOutlinePainter().draw(Canvas(bmp), listOf(Rect(0, 0, 50, 100)), listOf(quad), 1f, 1f, 2f)

        assertNotEquals(background, bmp.getPixel(20, 50), "left edge, inside the mask, is stroked")
        assertEquals(background, bmp.getPixel(80, 50), "right edge, outside the mask, is untouched")
        assertEquals(background, bmp.getPixel(35, 50), "the interior is not filled")
    }
}
