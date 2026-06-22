package com.andmx.computeruse

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred

/**
 * Holds the MediaProjection grant across the app lifetime.
 *
 * Android hands back an `(resultCode, data)` pair from the user-consent dialog;
 * we stash it here so [ScreenCaptor] (backed by [ScreenCaptureService]) can
 * build a VirtualDisplay from it. MediaProjection can't be persisted across
 * process death, so the grant is in-memory only.
 *
 * The consent dialog itself must be launched by an Activity (see
 * [MainActivity] / [PermissionGate]); the launcher writes the result back via
 * [provideGrant]. [requestProjection] awaits the resolution.
 */
object MediaProjectionManagerHolder {
    /** The granted resultCode + intent from the consent dialog, or null if not yet authorized. */
    var grant: ProjectionGrant? = null
        private set

    private var pending = CompletableDeferred<Boolean>()

    /** True once a user has granted projection (grant is non-null). */
    val isAuthorized: Boolean get() = grant != null

    /**
     * Await the projection grant. Resolves true if the user accepted, false if
     * rejected. The consent dialog is launched by the Activity layer, which
     * calls [provideGrant] with the result.
     */
    suspend fun requestProjection(): Boolean = pending.await()

    /** Called by the Activity layer once the system consent dialog returns. */
    fun provideGrant(resultCode: Int, data: Intent?) {
        if (data != null) {
            grant = ProjectionGrant(resultCode, data)
            pending.complete(true)
        } else {
            pending.complete(false)
        }
    }

    /** Reset state so a fresh request can be made (e.g. after denial). */
    fun reset() {
        grant = null
        pending = CompletableDeferred()
    }

    /** The real screen size in pixels (used for coordinate scaling). */
    fun realScreenSize(context: Context): IntArray {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }
}

/** The authorized projection grant captured from the consent dialog. */
data class ProjectionGrant(val resultCode: Int, val data: Intent)

/** Convenience accessor for the system MediaProjectionManager. */
fun Context.systemMediaProjectionManager(): MediaProjectionManager =
    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
