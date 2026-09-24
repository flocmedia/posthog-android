package com.posthog.android.replay.internal

import com.posthog.internal.replay.RRWireframe

/**
 * GAME-1315 — removes the redundant entries from an incremental mutation's adds/updates.
 *
 * THE DUPLICATION. `findAddedAndRemovedItems` diffs a FLATTENED tree, so every changed node
 * becomes its own entry; but each entry is emitted as the node WITH its whole subtree. A node
 * is therefore serialized once for itself and once inside each changed ancestor. On a ~12-deep
 * scrolling image grid that is a ~13x multiplier: one capture here carried 583 base64
 * occurrences of just 37 distinct blobs -- 762 KB on the wire for 58 KB of actual pixels.
 *
 * WHY IT MATTERS. Past PostHog's per-event ingest limit the server answers 413, and
 * `PostHogQueue.deleteFilesIfAPIError` treats a 413 on a batch of ONE as non-retryable
 * ("splitting cannot help") and deletes the event. Lose the establishing full snapshot that
 * way and `sentFullSnapshot` stays true, so every later payload is an incremental mutation
 * against a document the player never received: a white replay for the rest of the session.
 *
 * WHY DROPPING WHOLE ENTRIES, AND NOT THEIR CHILDREN. The web player converts an update into a
 * REMOVE followed by an ADD (posthog `common/replay-shared/src/mobile/transformer/transformers.ts`,
 * `makeIncrementalRemoveForUpdate`). Stripping `childWireframes` off an entry would therefore
 * remove the node and re-add it childless, discarding every UNCHANGED descendant -- a blank
 * region, invisible to any size check. Dropping a whole entry that some ancestor already
 * carries is safe in a way that stripping is not: the ancestor's subtree still holds it.
 *
 * WHY IT IS LOSSLESS. The player flattens every subtree it receives and dedupes by id
 * (`flattenMutationAdds` + `dedupeMutations`), so the duplicates are discarded on arrival --
 * they exist only to save the SDK from diffing on the client. Its own contract is that "for a
 * given ID that is present more than once in a single snapshot, every instance of that ID is
 * identical", which holds here by construction: every entry is a node from the SAME `current`
 * tree walk, so an ancestor's copy of a descendant IS that descendant.
 *
 * Removes are deliberately untouched: an `RRRemovedNode` is an id and a parent id, so there is
 * no subtree to duplicate and nothing to win.
 */
internal object IncrementalMutationDedup {
    /**
     * Keeps only the top-most entries: those whose id does not already appear somewhere inside
     * another entry's subtree.
     *
     * Order is preserved, which matters because the player's own comment notes it "assumes that
     * removes are processed before adds" -- this must not become a resequencing operation on top
     * of a filtering one.
     */
    fun dropCovered(items: List<RRWireframe>): List<RRWireframe> {
        if (items.size < 2) return items

        // Ids owned by some OTHER entry's subtree. A node's own root id is excluded, or every
        // entry would cover itself and the whole list would be dropped.
        val covered = HashSet<Int>()
        for (item in items) {
            item.childWireframes?.forEach { collectIds(it, covered) }
        }
        if (covered.isEmpty()) return items

        return items.filter { it.id !in covered }
    }

    private fun collectIds(
        node: RRWireframe,
        out: MutableSet<Int>,
    ) {
        out.add(node.id)
        node.childWireframes?.forEach { collectIds(it, out) }
    }
}
