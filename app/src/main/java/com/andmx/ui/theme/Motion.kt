package com.andmx.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion tokens so every transition across the app uses consistent
 * durations and easing. Centralizing them prevents the "each screen feels
 * different" drift and makes the whole app read as one coherent product.
 *
 * Values are tuned for a calm, professional feel (Codex/Linear-like): nothing
 * snaps or bounces aggressively.
 */
object Motion {
    /** Snappy UI feedback (chip select, icon swap). ~120-150ms. */
    const val DUR_FAST = 150
    /** Standard content transitions (fade, expand). ~220ms. */
    const val DUR_STD = 220
    /** Larger panel/section changes. ~300ms. */
    const val DUR_SLOW = 300

    /** Primary easing — gentle decelerate, feels natural for most enters. */
    val EASE_OUT: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EASE_IN_OUT: Easing = FastOutSlowInEasing
}
