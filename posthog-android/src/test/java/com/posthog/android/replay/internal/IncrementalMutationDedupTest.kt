package com.posthog.android.replay.internal

import com.posthog.internal.replay.RRWireframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * GAME-1315. Pins the wire-level de-duplication of incremental mutation entries.
 *
 * MUTATION EVIDENCE — each edit applied to IncrementalMutationDedup.kt, test watched go red:
 *
 *  M1  `item.childWireframes?.forEach { collectIds(it, covered) }` -> `collectIds(item, covered)`
 *      (collect the entry's OWN id too)   => SIX tests FAIL, led by
 *      aDescendantEntryIsDroppedWhenAnAncestorCarriesIt and siblingsAreAllKeptWhenNoneContainsAnother:
 *      every entry covers itself, so the filter drops all of them and the mutation ships empty.
 *      This is the subtle one -- it produces a SMALLER payload, so a size-only check calls it a
 *      win while the replay goes blank. Note everyEntryCoversItselfIsNotAThing does NOT fail here:
 *      a one-entry list returns early on `items.size < 2` and never reaches the bug. It guards the
 *      early return (M3/M6), not this.
 *  M2  `items.filter { it.id !in covered }` -> `items.filter { it.id in covered }`
 *                                         => aDescendantEntryIsDroppedWhenAnAncestorCarriesIt FAILS.
 *  M3  `if (items.size < 2) return items` -> `return items` (never dedupe)
 *                                         => aDescendantEntryIsDroppedWhenAnAncestorCarriesIt FAILS.
 *  M4  `collectIds` recursion into childWireframes deleted (only direct children counted)
 *                                         => aGrandchildIsCoveredTwoLevelsDown FAILS.
 *  M5  `items.filter {...}` -> `items.filterNot {...}.reversed()` (reorder survivors)
 *                                         => orderIsPreserved FAILS. The player's transformer
 *      notes it "assumes that removes are processed before adds", so this must filter, not resequence.
 *  M6  `if (covered.isEmpty()) return items` -> `return emptyList()`
 *                                         => siblingsAreAllKeptWhenNoneContainsAnother FAILS.
 */
internal class IncrementalMutationDedupTest {
    private fun frame(
        id: Int,
        children: List<RRWireframe>? = null,
    ) = RRWireframe(id = id, x = 0, y = 0, width = 10, height = 10, childWireframes = children)

    @Test
    fun aDescendantEntryIsDroppedWhenAnAncestorCarriesIt() {
        val child = frame(2)
        val parent = frame(1, listOf(child))

        // The flattened diff emits BOTH: the parent with its subtree, and the child again.
        val kept = IncrementalMutationDedup.dropCovered(listOf(parent, child))

        assertEquals(listOf(1), kept.map { it.id })
        assertSame(parent, kept.single(), "the ancestor is kept verbatim, subtree intact")
    }

    @Test
    fun theSurvivingAncestorStillCarriesTheDroppedDescendant() {
        // The whole safety argument: dropping the child loses nothing because the parent's
        // subtree still holds it. If this ever stopped being true the drop would be lossy.
        val child = frame(2)
        val parent = frame(1, listOf(child))

        val kept = IncrementalMutationDedup.dropCovered(listOf(parent, child))

        assertEquals(listOf(2), kept.single().childWireframes?.map { it.id })
    }

    @Test
    fun everyEntryCoversItselfIsNotAThing() {
        // A single entry has nothing above it and must always survive. Counting a node's own id
        // as "covered" empties the mutation entirely -- smaller on the wire, and blank on screen.
        val only = frame(1, listOf(frame(2)))

        assertEquals(listOf(only), IncrementalMutationDedup.dropCovered(listOf(only)))
    }

    @Test
    fun aGrandchildIsCoveredTwoLevelsDown() {
        val grandchild = frame(3)
        val child = frame(2, listOf(grandchild))
        val parent = frame(1, listOf(child))

        val kept = IncrementalMutationDedup.dropCovered(listOf(parent, child, grandchild))

        assertEquals(listOf(1), kept.map { it.id })
    }

    @Test
    fun siblingsAreAllKeptWhenNoneContainsAnother() {
        val a = frame(1)
        val b = frame(2)
        val c = frame(3, listOf(frame(4)))

        val kept = IncrementalMutationDedup.dropCovered(listOf(a, b, c))

        assertEquals(listOf(1, 2, 3), kept.map { it.id }, "unrelated changed nodes all still ship")
    }

    @Test
    fun aChangedDescendantSurvivesWhenItsAncestorDidNotChange() {
        // Only the child is in the diff; nothing carries it, so dropping it would lose the change.
        val child = frame(2)

        assertEquals(listOf(child), IncrementalMutationDedup.dropCovered(listOf(child)))
    }

    @Test
    fun orderIsPreserved() {
        val kept =
            IncrementalMutationDedup.dropCovered(
                listOf(frame(5), frame(1, listOf(frame(2))), frame(9), frame(2)),
            )

        assertEquals(listOf(5, 1, 9), kept.map { it.id })
    }

    @Test
    fun anEmptyListIsUnchanged() {
        assertTrue(IncrementalMutationDedup.dropCovered(emptyList()).isEmpty())
    }

    @Test
    fun theRealShapeCollapsesTheDeepGridCase() {
        // Non-vacuous self-check with the shape actually measured on device: a 12-deep chain where
        // every level changed. The flattened diff emits 12 entries; exactly one should survive.
        var node = frame(12)
        val entries = ArrayDeque<RRWireframe>()
        entries.addFirst(node)
        for (id in 11 downTo 1) {
            node = frame(id, listOf(node))
            entries.addFirst(node)
        }
        assertEquals(12, entries.size, "fixture must actually build 12 entries")

        val kept = IncrementalMutationDedup.dropCovered(entries.toList())

        assertEquals(listOf(1), kept.map { it.id })
    }
}
