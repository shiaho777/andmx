package com.andmx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Viewport breakpoints for responsive layout, mirroring Material 3 window-size
 * classes adapted to this project's three-pane workbench:
 *
 * - **COMPACT** (< 600dp): phone portrait → single pane (sidebar as drawer,
 *   work pane as full-screen overlay).
 * - **MEDIUM** (600-839dp): small tablet / phone landscape → sidebar + chat,
 *   work pane overlay.
 * - **EXPANDED** (≥ 840dp): the original three-pane desktop layout.
 */
object Viewport {
    val COMPACT_MAX: Dp = 599.dp
    val MEDIUM_MAX: Dp = 839.dp
}

enum class ViewportClass { COMPACT, MEDIUM, EXPANDED }

@Stable
val ViewportClass.isCompact: Boolean get() = this == ViewportClass.COMPACT
@Stable
val ViewportClass.isExpanded: Boolean get() = this == ViewportClass.EXPANDED

/**
 * Resolve the current [ViewportClass] from the screen width. Re-computed on
 * configuration changes (rotation, resize). Prefer this over hardcoded widths.
 */
@Composable
fun rememberViewportClass(): ViewportClass {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, density) {
        val widthDp = configuration.screenWidthDp.dp
        when {
            widthDp < Viewport.COMPACT_MAX -> ViewportClass.COMPACT
            widthDp < Viewport.MEDIUM_MAX -> ViewportClass.MEDIUM
            else -> ViewportClass.EXPANDED
        }
    }
}

/**
 * The screen height in dp. Useful for capping overlay/list heights that would
 * otherwise overflow in landscape (e.g. ~360dp tall): pick a fraction of it
 * rather than a hardcoded dp value.
 */
@Composable
fun rememberScreenHeightDp(): Int {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenHeightDp) { configuration.screenHeightDp }
}
