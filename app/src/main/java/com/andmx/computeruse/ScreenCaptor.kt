package com.andmx.computeruse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Captures device screenshots via [MediaProjection], scales them to a
 * model-friendly resolution, and returns `data:image/png;base64,...` data-urls
 * ready to drop into an [com.andmx.llm.ApiMessage.imageUrls].
 *
 * Lifecycle: call [ensureStarted] once a grant exists (it builds the
 * VirtualDisplay + ImageReader), then [capture] repeatedly. [release] tears it
 * down. The captured image is downscaled so its longest edge ≤ [maxEdge] — this
 * both shrinks the token cost and matches what vision models expect. [scaleFactor]
 * is exposed so coordinates coming back from the model (relative to the scaled
 * image) can be mapped to real screen pixels by [AccessibilityController].
 */
class ScreenCaptor(private val context: Context) {
    companion object {
        private const val TAG = "ScreenCaptor"
        /** Cap on the screenshot's longest edge (px). Keeps base64/token cost bounded. */
        const val MAX_EDGE = 1024
        /** VirtualDisplay name (must be ≤ 18 chars on some OEMs). */
        private const val VD_NAME = "AndMXCapture"
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** Real screen width/height in px. */
    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * The factor applied to the screenshot: realPixel / scaledPixel. Multiply a
     * model-supplied coordinate by this to recover the real screen coordinate.
     */
    var scaleFactor: Float = 1f
        private set

    /** True once a VirtualDisplay + ImageReader are active. */
    val isActive: Boolean get() = virtualDisplay != null

    /** Build the capture pipeline from the current [MediaProjectionManagerHolder.grant]. */
    fun ensureStarted(): Boolean {
        if (isActive) return true
        val grant = MediaProjectionManagerHolder.grant ?: run {
            Log.w(TAG, "No projection grant; call requestProjection first")
            return false
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        projection = mgr.getMediaProjection(grant.resultCode, grant.data) ?: return false

        val (w, h) = realScreenSize(context)
        screenWidth = w
        screenHeight = h

        imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            VD_NAME, w, h, resourcesDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null,
        )
        scaleFactor = maxOf(w, h).toFloat() / MAX_EDGE
        Log.i(TAG, "Capture started: ${w}x${h}, scaleFactor=$scaleFactor")
        return isActive
    }

    /**
     * Grab the latest frame and return it as a PNG data-url, scaled so the
     * longest edge ≤ [MAX_EDGE]. Returns null on any capture failure (the caller
     * reports the error to the model so it can retry).
     */
    fun capture(): String? {
        if (!isActive && !ensureStarted()) return null
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val bitmap = imageToBitmap(image)
            val scaled = scaleDown(bitmap, MAX_EDGE)
            bitmap.recycle()
            encodePngDataUrl(scaled).also { scaled.recycle() }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture failed", t)
            null
        } finally {
            image.close()
        }
    }

    /** Tear down the projection and release buffers. Safe to call multiple times. */
    fun release() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null); imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        Log.i(TAG, "Capture released")
    }

    /**
     * The (width, height) the model will see — i.e. the scaled-down screenshot
     * dimensions, which is what coordinates refer to. Before capture starts,
     * returns the projected scaled size based on the real screen.
     */
    fun snapshotDims(): Pair<Int, Int> {
        val (w, h) = if (screenWidth > 0) screenWidth to screenHeight else realScreenSize(context)
        val longest = maxOf(w, h).toFloat()
        if (longest <= MAX_EDGE) return w to h
        val ratio = MAX_EDGE / longest
        return ((w * ratio).toInt()) to ((h * ratio).toInt())
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val bmp = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        // Crop the padding off so the bitmap is exactly screen-sized.
        return if (bmp.width != screenWidth || bmp.height != screenHeight) {
            Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight).also { bmp.recycle() }
        } else bmp
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val matrix = Matrix().apply { postScale(ratio, ratio) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun encodePngDataUrl(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$base64"
    }

    private fun resourcesDpi(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        return metrics.densityDpi
    }
}

private fun realScreenSize(context: Context): Pair<Int, Int> {
    val arr = MediaProjectionManagerHolder.realScreenSize(context)
    return arr[0] to arr[1]
}
