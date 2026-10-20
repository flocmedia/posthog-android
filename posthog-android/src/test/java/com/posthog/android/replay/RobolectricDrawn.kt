package com.posthog.android.replay

// Robolectric never runs a real draw, so every view stays dirty (isDirty) forever and the
// GAME-1402 undrawn-change guard would refuse every capture. Tests that are not about that
// guard treat the tree as drawn unless a test records a layout with no draw after it; the
// guard's own tests reset the override and clear dirtiness with a software draw instead.
internal fun PostHogReplayIntegration.robolectricDrawn(): PostHogReplayIntegration =
    apply { undrawnChangesOverride = { _, drawState -> drawState.layoutSinceDraw } }
