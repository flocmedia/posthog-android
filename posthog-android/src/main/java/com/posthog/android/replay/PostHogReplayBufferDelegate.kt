package com.posthog.android.replay

/**
 * Delegate interface for controlling session replay buffering behavior.
 *
 * The replay queue is passive: it checks [isBuffering] on every `add()` and `flush()`,
 * and notifies the delegate after buffering a snapshot.
 */
internal interface PostHogReplayBufferDelegate {
    /**
     * Whether the replay queue should buffer snapshots instead of sending directly.
     * Checked on every `queue.add()` and `queue.flush()`.
     */
    val isBuffering: Boolean

    /**
     * Whether session recording is currently active. The queue drops snapshots routed to the
     * persisted queue while this is false, so a snapshot captured just before recording stopped
     * (e.g. a fresh remote config rejecting the cached flag) cannot leak to the network.
     */
    val isActive: Boolean

    /**
     * Called after a snapshot was added to the buffer.
     * The delegate should check threshold conditions and schedule
     * `replayQueue.migrateBufferToQueue()` on a background thread when
     * the minimum duration has been met.
     */
    fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue)

    /**
     * Called after the buffer was dropped (not migrated) — see [PostHogReplayQueue.clearBuffer].
     *
     * A drop discards any buffered FULL snapshot, but the per-view snapshot state that decides
     * full-vs-incremental lives in the integration, not the queue, so it still reports
     * "full already sent". If recording then continues (or resumes over the same session, e.g. the
     * app's manual force-replay retry loop keeping replay active across a startup remote-config
     * drop), the next captures ship as INCREMENTAL mutations that reference a full the player never
     * received — orphaned nodes the player can't anchor, which render as a blank/near-blank screen
     * (GAME-1236). The delegate re-anchors so the next capture is a fresh meta + full snapshot.
     */
    fun onBufferCleared()
}
