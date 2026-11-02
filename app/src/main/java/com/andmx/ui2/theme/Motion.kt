package com.andmx.ui2.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

private val Ease = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

private fun <T> paced(ms: Int): FiniteAnimationSpec<T> =
    if (ms <= 0) snap() else tween(durationMillis = ms, easing = Ease)

@Immutable
data class Motion(
    val fastMs: Int = 120,
    val baseMs: Int = 160,
    val enterMs: Int = 200,
    val inkMs: Int = 320,
) {
    val fastSpatial: FiniteAnimationSpec<Float> = paced(fastMs)
    val defaultSpatial: FiniteAnimationSpec<Float> = paced(baseMs)
    val slowSpatial: FiniteAnimationSpec<Float> = paced(enterMs)
    val fastEffects: FiniteAnimationSpec<Float> = paced(fastMs)
    val defaultEffects: FiniteAnimationSpec<Float> = paced(baseMs)
    val slowEffects: FiniteAnimationSpec<Float> = paced(inkMs)
    val enter: FiniteAnimationSpec<Float> = paced(enterMs)
    val defaultExpand: FiniteAnimationSpec<IntSize> = paced(enterMs)
    val enterOffset: FiniteAnimationSpec<IntOffset> = paced(enterMs)
    val baseDp: FiniteAnimationSpec<Dp> = paced(baseMs)

    fun push(): ContentTransform =
        (slideInHorizontally(enterOffset) { it } + fadeIn(enter)) togetherWith
            (slideOutHorizontally(enterOffset) { -it / 4 } + fadeOut(enter))

    fun pop(): ContentTransform =
        (slideInHorizontally(enterOffset) { -it / 4 } + fadeIn(enter)) togetherWith
            (slideOutHorizontally(enterOffset) { it } + fadeOut(enter))

    fun riseIn(): EnterTransition = expandVertically(defaultExpand) + fadeIn(enter)
    fun riseOut(): ExitTransition = shrinkVertically(defaultExpand) + fadeOut(enter)

    fun crossfade(): ContentTransform = fadeIn(defaultEffects) togetherWith fadeOut(defaultEffects)

    fun rollUp(): ContentTransform =
        (slideInVertically(enterOffset) { it / 2 } + fadeIn(enter)) togetherWith
            (slideOutVertically(enterOffset) { -it / 2 } + fadeOut(enter))

    companion object {
        val Reduced = Motion(fastMs = 0, baseMs = 0, enterMs = 0, inkMs = 0)
    }
}

val LocalMotion = staticCompositionLocalOf { Motion() }

@Composable
fun rememberMotion(): Motion {
    val context = LocalContext.current
    return remember {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        if (scale == 0f) Motion.Reduced else Motion()
    }
}
