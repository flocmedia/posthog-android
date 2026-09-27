package com.posthog.android.replay.internal

import kotlin.math.min

/**
 * Caps what mask outlines can cost a slow device. Outline collection runs on the main thread
 * during the mask walk; on a fast phone it is well under a millisecond, but a budget phone can be
 * several times slower. Each capture's collection time is reported here:
 *
 *  - over [budgetNanos] is SLOW: the next 2, 4, 8, ... captures skip outlines (backoff), and
 *  - [disableAfterSlow] slow captures IN A ROW turn outlines off for the rest of the process;
 *  - a capture within budget resets the streak.
 *
 * So a device that is always slow pays for at most [disableAfterSlow] slow captures, ever. Only
 * outlines are affected: every capture is still masked, so this can never make a frame less private.
 * Thread-safe: the pre-walk runs on main, the legacy walk may not.
 */
internal class OutlineBudget(
    private val budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    private val disableAfterSlow: Int = DEFAULT_DISABLE_AFTER_SLOW,
    private val log: (String) -> Unit = {},
    private val onDisabled: (lastCaptureMillis: Double, slowCaptures: Int) -> Unit = { _, _ -> },
) {
    private var slowStreak = 0
    private var skipRemaining = 0

    @Volatile
    var disabled: Boolean = false
        private set

    /** Whether the next capture should collect outlines. Consumes one backoff slot when skipping. */
    @Synchronized
    fun shouldCollect(): Boolean {
        if (disabled) return false
        if (skipRemaining > 0) {
            skipRemaining--
            return false
        }
        return true
    }

    /** The main-thread nanoseconds one capture spent collecting outlines. */
    @Synchronized
    fun record(nanos: Long) {
        if (disabled) return
        if (nanos <= budgetNanos) {
            slowStreak = 0
            return
        }
        slowStreak++
        skipRemaining = 1 shl min(slowStreak, MAX_BACKOFF_SHIFT)
        if (slowStreak >= disableAfterSlow) {
            disabled = true
            log(
                "Session Replay mask outlines disabled: $slowStreak captures in a row over " +
                    "${budgetNanos / 1_000_000.0}ms on the main thread.",
            )
            try {
                onDisabled(nanos / 1_000_000.0, slowStreak)
            } catch (e: Throwable) {
                log("Session Replay onMaskOutlinesDisabled failed: $e.")
            }
        }
    }

    internal companion object {
        const val DEFAULT_BUDGET_NANOS: Long = 2_000_000L
        const val DEFAULT_DISABLE_AFTER_SLOW: Int = 4
        const val MAX_BACKOFF_SHIFT: Int = 5
    }
}
