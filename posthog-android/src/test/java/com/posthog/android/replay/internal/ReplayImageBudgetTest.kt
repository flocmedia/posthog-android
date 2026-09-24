package com.posthog.android.replay.internal

import com.posthog.android.replay.internal.ReplayImageBudget.Companion.MAX_IMAGE_DIMENSION_PX
import com.posthog.android.replay.internal.ReplayImageBudget.Companion.scaledSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GAME-1315. Pins the two rules that make a wireframe snapshot's image payload
 * screen-independent: encode at the on-screen size under a hard dimension cap, and
 * stop shipping pixels once the per-snapshot byte budget is spent.
 *
 * MUTATION EVIDENCE — each edit applied to ReplayImageBudget.kt, test watched go red:
 *
 *  M1  `if (bytes > perImageBytes) return false` -> `return@charge true` (drop the
 *      per-image ceiling)              => oversizedImageIsRejectedOnItsOwnAccount FAILS.
 *      Without it one full-screen background eats the whole snapshot allowance.
 *  M2  `if (spent + bytes > perSnapshotBytes)` -> `>=`  (off-by-one at the boundary)
 *                                       => anImageThatExactlyFillsTheBudgetIsAccepted FAILS.
 *  M3  `spent += bytes.toLong()` deleted (charge without accounting)
 *                                       => budgetIsSpentAcrossImages FAILS (all 12 accepted).
 *  M4  `reset()` body emptied           => resetRestoresTheFullBudget FAILS.
 *  M5  scaledSize: `min(boxWidth, MAX_IMAGE_DIMENSION_PX)` -> `boxWidth`
 *                                       => aBoxLargerThanTheCapIsStillCapped FAILS. This is
 *      the mutation that re-introduces the bug: the cap is the ONLY thing stopping a denser
 *      screen from re-inflating every blob.
 *  M6  scaledSize: drop the `srcWidth <= capWidth && srcHeight <= capHeight` early return
 *                                       => aSmallImageIsNeverUpscaled FAILS (16x16 -> 512x512).
 *  M7  scaledSize: `min(wScale, hScale)` -> `max(...)`  (fit instead of contain)
 *                                       => aspectRatioIsPreservedAndBothSidesFit FAILS
 *      (the long side overflows the box).
 *  M8  scaledSize: drop `max(1, ...)`   => anExtremeAspectRatioNeverCollapsesToZero FAILS.
 */
internal class ReplayImageBudgetTest {
    // ---- charge / budget ----

    @Test
    fun oversizedImageIsRejectedOnItsOwnAccount() {
        val budget = ReplayImageBudget(perImageBytes = 1_000, perSnapshotBytes = 100_000)

        assertFalse(budget.charge(1_001), "an image over the per-image ceiling must be dropped")
        assertEquals(0L, budget.spentBytes, "a rejected image must not be charged")
        // and the snapshot's allowance is intact for everything after it
        assertTrue(budget.charge(1_000))
    }

    @Test
    fun anImageThatExactlyFillsTheBudgetIsAccepted() {
        val budget = ReplayImageBudget(perImageBytes = 1_000, perSnapshotBytes = 1_000)

        assertTrue(budget.charge(1_000), "the boundary is inclusive: == budget still fits")
        assertTrue(budget.exhausted())
        assertFalse(budget.charge(1), "nothing fits once the budget is exactly spent")
    }

    @Test
    fun budgetIsSpentAcrossImages() {
        val budget = ReplayImageBudget(perImageBytes = 1_000, perSnapshotBytes = 10_000)

        val accepted = (1..12).count { budget.charge(1_000) }

        assertEquals(10, accepted, "exactly ten 1 KB images fit a 10 KB snapshot budget")
        assertEquals(10_000L, budget.spentBytes)
        assertTrue(budget.exhausted())
    }

    @Test
    fun resetRestoresTheFullBudget() {
        val budget = ReplayImageBudget(perImageBytes = 1_000, perSnapshotBytes = 1_000)
        assertTrue(budget.charge(1_000))
        assertTrue(budget.exhausted())

        budget.reset()

        assertEquals(0L, budget.spentBytes)
        assertFalse(budget.exhausted())
        assertTrue(budget.charge(1_000), "the next snapshot starts with its own allowance")
    }

    @Test
    fun nonPositiveChargeIsRejected() {
        val budget = ReplayImageBudget(perImageBytes = 1_000, perSnapshotBytes = 1_000)

        assertFalse(budget.charge(0), "an empty encode is not an image")
        assertFalse(budget.charge(-1))
        assertEquals(0L, budget.spentBytes)
    }

    // ---- scaledSize ----

    @Test
    fun anImageIsEncodedAtItsOnScreenSize() {
        // A 300x300 sticker asset sitting in a 120x120 grid cell.
        assertEquals(120 to 120, scaledSize(srcWidth = 300, srcHeight = 300, boxWidth = 120, boxHeight = 120))
    }

    @Test
    fun aSmallImageIsNeverUpscaled() {
        // A 16x16 icon in a 64x64 button stays 16x16 -- upscaling would cost bytes for no detail.
        assertEquals(16 to 16, scaledSize(srcWidth = 16, srcHeight = 16, boxWidth = 64, boxHeight = 64))
    }

    @Test
    fun aBoxLargerThanTheCapIsStillCapped() {
        // THE screen-independence rule: a full-bleed image on a 1440x3120 flagship is encoded
        // at the cap, not at the display's pixel size.
        val (width, height) = scaledSize(srcWidth = 1_440, srcHeight = 3_120, boxWidth = 1_440, boxHeight = 3_120)

        assertTrue(width <= MAX_IMAGE_DIMENSION_PX && height <= MAX_IMAGE_DIMENSION_PX, "got ${width}x$height")
        assertEquals(236 to 512, width to height)
    }

    @Test
    fun anUnknownBoxFallsBackToTheCap() {
        // A detached or not-yet-laid-out view reports width/height 0.
        assertEquals(512 to 512, scaledSize(srcWidth = 2_000, srcHeight = 2_000, boxWidth = 0, boxHeight = 0))
    }

    @Test
    fun aspectRatioIsPreservedAndBothSidesFit() {
        val (width, height) = scaledSize(srcWidth = 400, srcHeight = 200, boxWidth = 100, boxHeight = 100)

        assertEquals(100 to 50, width to height, "contain, not fit: the long side sets the scale")
        assertTrue(width <= 100 && height <= 100)
    }

    @Test
    fun anExtremeAspectRatioNeverCollapsesToZero() {
        val (width, height) = scaledSize(srcWidth = 4_000, srcHeight = 3, boxWidth = 100, boxHeight = 100)

        assertEquals(100, width)
        assertTrue(height >= 1, "a zero dimension cannot be encoded at all; got $height")
    }

    @Test
    fun aDegenerateSourceIsLeftAlone() {
        // Nothing to scale; the caller's own isValid() check rejects these.
        assertEquals(0 to 0, scaledSize(srcWidth = 0, srcHeight = 0, boxWidth = 100, boxHeight = 100))
        assertEquals(-1 to 10, scaledSize(srcWidth = -1, srcHeight = 10, boxWidth = 100, boxHeight = 100))
    }
}
