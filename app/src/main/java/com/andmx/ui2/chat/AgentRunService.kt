package com.andmx.ui2.chat

import com.andmx.ui2.settings.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有回合在跑时保持进程为前台，避免切到后台或离开界面后被系统杀掉。
 */
class AgentRunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_STOP && holders.get() <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "会话在后台继续运行时的持续通知"
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndMX 正在运行")
            .setContentText(tr("会话还在后台继续，离开这个页面不会中断"))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "andmx_agent_run"
        private const val NOTIF_ID = 0xA2
        private const val ACTION_STOP = "com.andmx.action.AGENT_RUN_STOP"
        private val holders = AtomicInteger(0)

        fun acquire(context: Context) {
            holders.incrementAndGet()
            val app = context.applicationContext
            val intent = Intent(app, AgentRunService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun release(context: Context) {
            val left = holders.decrementAndGet().coerceAtLeast(0)
            if (holders.get() < 0) holders.set(0)
            if (left == 0) {
                val app = context.applicationContext
                app.startService(Intent(app, AgentRunService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
