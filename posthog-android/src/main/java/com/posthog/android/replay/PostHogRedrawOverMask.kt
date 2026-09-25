package com.posthog.android.replay

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Screenshot-mode opt-in: views marked here are drawn back ON TOP of the masks.
 *
 * Screenshot mode masks by painting solid rectangles over the bounds of every
 * `ph-no-capture` view on the captured bitmap. The bitmap is flat, so anything that sits
 * visually in front of a masked view -- a sticker over a masked photo -- is painted over
 * too. A view marked here is re-rendered from the view tree and composited back over the
 * mask, so it stays visible while the masked view beneath it stays hidden.
 *
 * WHY AN ALLOWLIST. Only views explicitly marked are redrawn -- never "everything in front
 * of the mask". A container of the masked view often holds other untagged views carrying
 * the same sensitive pixels (a filtered copy, a blurred background); today they are hidden
 * because they fall inside the mask's rectangle, and redrawing them would put those pixels
 * back. Mark only views whose own pixels are safe to show.
 *
 * Fail-closed by construction: the mask is painted over the whole rectangle first, and a
 * marked view whose subtree contains a `ph-no-capture` view is skipped entirely. A marked
 * view that cannot be rendered in time is simply not redrawn -- the frame then looks like
 * plain screenshot masking, never less private.
 *
 * Wireframe mode is unaffected; there `ph-no-capture` already hides only the tagged view.
 *
 * References are weak, so a detached view is forgotten without an explicit [unmark].
 */
public object PostHogRedrawOverMask {
    private val marked: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Draws [view] (and its subtree) back over the screenshot masks. Call on the main thread. */
    @JvmStatic
    public fun mark(view: View) {
        marked.add(view)
    }

    @JvmStatic
    public fun unmark(view: View) {
        marked.remove(view)
    }

    @JvmStatic
    public fun isMarked(view: View): Boolean = marked.contains(view)

    internal fun isEmpty(): Boolean = marked.isEmpty()
}
