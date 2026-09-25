package com.posthog.android.replay.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-process cost cap on mask outlines.
 *
 * MUTATION EVIDENCE — each edit applied to OutlineBudget.kt, named test watched go red:
 *  B1  record: drop `slowStreak = 0` on a fast capture
 *      => aFastCaptureResetsTheStreak FAILS.
 *  B2  record: `slowStreak >= disableAfterSlow` -> `>`
 *      => fourSlowInARowDisablesForGood FAILS.
 *  B3  record: `skipRemaining = 1 shl ...` -> `skipRemaining = 1`
 *      => backoffDoublesWithTheStreak FAILS.
 *  B4  record: `nanos <= budgetNanos` -> `<`
 *      => exactlyOnBudgetIsNotSlow FAILS.
 *  B5  shouldCollect: drop the `disabled` check
 *      => fourSlowInARowDisablesForGood FAILS.
 */
internal class OutlineBudgetTest {
    private val budget = 2_000_000L
    private val fast = 500_000L
    private val slow = 3_000_000L

    // Collects-or-skips for the next n captures, recording [cost] for each capture that collects.
    private fun OutlineBudget.run(
        n: Int,
        cost: Long,
    ): List<Boolean> =
        List(n) {
            val collect = shouldCollect()
            if (collect) record(cost)
            collect
        }

    @Test
    fun fastCapturesAlwaysCollect() {
        val sut = OutlineBudget(budget)
        assertEquals(List(20) { true }, sut.run(20, fast))
        assertFalse(sut.disabled)
    }

    @Test
    fun backoffDoublesWithTheStreak() {
        val sut = OutlineBudget(budget, disableAfterSlow = 10)
        // slow -> skip 2, slow -> skip 4, slow -> skip 8
        val pattern = sut.run(1 + 2 + 1 + 4 + 1 + 8 + 1, slow)
        assertEquals(
            listOf(true) + List(2) { false } + true + List(4) { false } + true + List(8) { false } + true,
            pattern,
        )
    }

    @Test
    fun fourSlowInARowDisablesForGood() {
        val logs = mutableListOf<String>()
        val sut = OutlineBudget(budget, log = { logs.add(it) })
        val collected = sut.run(200, slow).count { it }

        assertEquals(4, collected, "an always-slow device pays for exactly four slow captures")
        assertTrue(sut.disabled)
        assertEquals(List(50) { false }, sut.run(50, fast), "and stays off even when captures get fast")
        assertEquals(1, logs.size)
    }

    @Test
    fun aFastCaptureResetsTheStreak() {
        val sut = OutlineBudget(budget)
        repeat(3) {
            // slow, then drain the backoff with skips, then a fast capture resets the streak.
            assertTrue(sut.shouldCollect())
            sut.record(slow)
            while (!sut.shouldCollect()) Unit
            sut.record(fast)
        }
        assertFalse(sut.disabled, "never four slow in a row")
        assertTrue(sut.shouldCollect())
        sut.record(slow)
        assertEquals(List(2) { false } + true, sut.run(3, fast), "the streak restarted at 1: skip 2, not 16")
    }

    @Test
    fun exactlyOnBudgetIsNotSlow() {
        val sut = OutlineBudget(budget)
        assertEquals(List(10) { true }, sut.run(10, budget))
    }
}
