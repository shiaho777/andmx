package com.andmx.ui2.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 回合终态系统通知（设置页「通知」开关的实际消费者）：
 * 任务完成/失败、等待审批时在后台给出提示音/横幅，前台运行时不打扰。
 */
object TurnNotifier {

    private const val CHANNEL_ID = "agent_turns"
    private const val CHANNEL_NAME = "任务进展"

    @Volatile
    var appForeground: Boolean = true
    /** 进程内只问一次的运行时权限标记（首次发任务时在 UI 层触发）。 */
    var notifPermissionAsked: Boolean = false

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun maybeNotify(
        context: Context,
        conversationId: Long,
        title: String,
        body: String,
        soundEnabled: Boolean,
    ) {
        if (appForeground || !canPost(context)) return
        runCatching {
            ensureChannel(context)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("conversationId", conversationId)
                }
            val pi = launch?.let {
                PendingIntent.getActivity(
                    context, conversationId.toInt(), it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body.take(120))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            if (!soundEnabled) builder.setSilent(true)
            if (pi != null) builder.setContentIntent(pi)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(conversationId.toInt(), builder.build())
        }
    }
}
