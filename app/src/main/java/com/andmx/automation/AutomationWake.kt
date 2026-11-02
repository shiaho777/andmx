package com.andmx.automation

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andmx.ui2.MainActivity2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AutomationWake {
    const val ACTION = "com.andmx.action.AUTOMATION_DUE"
    private const val REQUEST = 71

    @Volatile
    var onDue: (suspend () -> Unit)? = null

    fun schedule(context: Context, atMs: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val whenMs = atMs.coerceAtLeast(System.currentTimeMillis() + 15_000L)
        val pending = pending(context)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class AutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AutomationWake.ACTION &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }
        val handler = AutomationWake.onDue
        if (handler == null) {
            notifyOpen(context)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handler()
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyOpen(context: Context) {
        val launch = Intent(context, MainActivity2::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context,
            72,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "任务进展", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("定时任务到点了")
            .setContentText("打开 AndMX 后会继续跑到期的任务")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(72, notification)
    }

    companion object {
        private const val CHANNEL = "agent_turns"
    }
}
