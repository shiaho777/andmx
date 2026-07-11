package com.andmx.computeruse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that owns the [android.media.projection.MediaProjection]
 * session. Android 14+ requires an active foreground service of type
 * `mediaProjection` while capturing; older versions also benefit from keeping
 * the session alive out-of-activity.
 *
 * The service does no capture itself — it just holds the foreground state so
 * [ScreenCaptor]'s VirtualDisplay/ImageReader keep receiving frames. Start it
 * after [MediaProjectionManagerHolder] has a grant.
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "andmx_screen_capture"
        private const val NOTIF_ID = 0xA1

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Screen capture foreground service started")
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AndMX agent 屏幕操作所需的持续通知"
                },
            )
        }
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndMX 正在操作屏幕")
            .setContentText("agent 的 Computer Use 功能已启用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        // Android 14+ requires the mediaProjection foreground type, but starting
        // with that type throws SecurityException if the user hasn't granted the
        // projection yet. Fall back to a typeless foreground start so the service
        // doesn't crash the app; the type will be re-declared once a real capture
        // session begins.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (e: SecurityException) {
                Log.w(TAG, "mediaProjection FGS type not allowed yet; starting typeless: ${e.message}")
                runCatching { startForeground(NOTIF_ID, notification) }
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
