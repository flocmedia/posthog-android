package com.posthog.android.replay.internal

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GAME-1315 — bounds the base64 image payload a single wireframe snapshot may carry.
 *
 * THE FAILURE THIS PREVENTS. In wireframe mode every unmasked ImageView drawable is
 * base64'd (`Drawable.base64` -> `Bitmap.webpBase64(quality = 30)`) with no per-image
 * cap, no downscale and no total budget. A grid of images puts dozens of them on screen
 * at once, so one snapshot carries dozens of base64 WebP blobs. Past PostHog's per-event
 * ingest limit the server answers 413, and `PostHogQueue.deleteFilesIfAPIError` treats a
 * 413 on a batch of ONE as non-retryable -- "splitting cannot help" -- and deletes it. If
 * the dropped event carried the establishing full snapshot, `sentFullSnapshot` stays true
 * and every later payload is an incremental mutation against a document the player never
 * received: a white replay for the rest of the session.
 *
 * WHY A BUDGET AND NOT JUST A BIGGER LIMIT. Each image is encoded at its ON-SCREEN size,
 * so the payload scales with the display. Measured with the same drive, only the display
 * changed:
 *
 *     emulator 1080x1920 @420dpi ->   970 KiB largest event  -- under the limit, no white
 *     flagship 1440x3120 @560dpi -> 1,664 KiB largest event  -- over, 413, white
 *
 * "Under the limit on this device" is therefore not safety, it is headroom that a bigger
 * phone spends. A fixed byte budget is what makes the outcome screen-INDEPENDENT.
 *
 * WHAT IT COSTS. Past the budget an image wireframe is emitted without its pixels, not
 * dropped: the node, its bounds and its style survive, so the document still anchors and
 * the replay stays navigable. Degrading fidelity is the deliberate trade against losing
 * the whole session.
 *
 * Instances are confined to the single-threaded snapshot executor
 * (`PostHogReplayIntegration.executor`), which is why the counter needs no synchronization.
 */
internal class ReplayImageBudget(
    private val perImageBytes: Int = DEFAULT_PER_IMAGE_BYTES,
    private val perSnapshotBytes: Int = DEFAULT_PER_SNAPSHOT_BYTES,
) {
    private var spent = 0L

    /** Bytes charged since the last [reset]. */
    val spentBytes: Long get() = spent

    /** Called once per snapshot pass, before the view tree is walked. */
    fun reset() {
        spent = 0L
    }

    /** True once no further image can fit, so the caller can skip encoding entirely. */
    fun exhausted(): Boolean = spent >= perSnapshotBytes

    /**
     * Charges [bytes] against the budget.
     *
     * @return true if the image fits and may be shipped; false if it must be dropped.
     * A single oversized image is rejected on its own account so that one huge drawable
     * cannot consume the whole snapshot's allowance and starve everything after it.
     */
    fun charge(bytes: Int): Boolean {
        if (bytes <= 0) return false
        if (bytes > perImageBytes) return false
        if (spent + bytes > perSnapshotBytes) return false
        spent += bytes.toLong()
        return true
    }

    internal companion object {
        /**
         * Per-image ceiling, in base64 characters (which is what lands in the JSON, ~4/3 of
         * the encoded bytes). A sticker or icon at replay quality is a small fraction of this;
         * the ceiling exists to reject the outliers -- a full-screen photographic background,
         * a decoded high-resolution asset -- that are individually responsible for the 413.
         */
        const val DEFAULT_PER_IMAGE_BYTES: Int = 48 * 1024

        /**
         * Per-snapshot ceiling. Chosen so the whole event stays well inside the ingest limit
         * with room for the wireframe tree itself, and so it cannot grow with the display.
         */
        const val DEFAULT_PER_SNAPSHOT_BYTES: Int = 256 * 1024

        /**
         * Hard ceiling on either dimension before encoding. A drawable is encoded at the size
         * it OCCUPIES, never the size it was decoded at, and never above this -- without it a
         * denser screen re-inflates every blob and the budget is spent on the first few images.
         */
        const val MAX_IMAGE_DIMENSION_PX: Int = 512

        /**
         * The size a source bitmap should be encoded at to fill a [boxWidth] x [boxHeight]
         * on-screen box, capped at [MAX_IMAGE_DIMENSION_PX] and never upscaled.
         *
         * A non-positive box means the caller does not know the on-screen size (a detached or
         * not-yet-laid-out view); the cap alone then applies. Returns the source size unchanged
         * when it already fits, so the common small-icon case does no work.
         */
        fun scaledSize(
            srcWidth: Int,
            srcHeight: Int,
            boxWidth: Int,
            boxHeight: Int,
        ): Pair<Int, Int> {
            if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight

            val capWidth = if (boxWidth > 0) min(boxWidth, MAX_IMAGE_DIMENSION_PX) else MAX_IMAGE_DIMENSION_PX
            val capHeight = if (boxHeight > 0) min(boxHeight, MAX_IMAGE_DIMENSION_PX) else MAX_IMAGE_DIMENSION_PX

            if (srcWidth <= capWidth && srcHeight <= capHeight) return srcWidth to srcHeight

            val scale = min(capWidth.toDouble() / srcWidth, capHeight.toDouble() / srcHeight)
            val width = max(1, (srcWidth * scale).roundToInt())
            val height = max(1, (srcHeight * scale).roundToInt())
            return width to height
        }
    }
}
