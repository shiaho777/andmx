package com.andmx.ui2.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize

@Immutable
data class Motion(
    val fastSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1400f),
    val defaultSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 700f),
    val slowSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 300f),
    val fastEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f),
    val defaultEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f),
    val slowEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f),
    val defaultExpand: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.8f,
        stiffness = 700f,
        visibilityThreshold = IntSize(1, 1),
    ),
)

val LocalMotion = staticCompositionLocalOf { Motion() }
