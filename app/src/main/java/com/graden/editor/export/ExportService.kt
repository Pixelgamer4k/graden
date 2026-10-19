package com.graden.editor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app alive while an export runs, with the screen off or another app in front, and shows its progress in a
 * notification. The work itself is done by [ExportEngine]; this only holds the process and a wake lock.
 */
class ExportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.graden.editor.TextFonts.assets = applicationContext.assets
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        val n = notification(0, "Starting")
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching { startForeground(ID, n, type) }.onFailure { runCatching { startForeground(ID, n) } }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "graden:export").apply { acquire(6 * 3600_000L) }
        main.post(object : Runnable {
            override fun run() {
                if (!ExportJob.running) return
                val text = when (ExportJob.phase) {
                    ExportJob.Phase.AUDIO -> "Mixing sound"
                    ExportJob.Phase.SAVING -> "Saving"
                    else -> "Frame ${ExportJob.framesDone} of ${ExportJob.framesTotal}" + if (ExportJob.etaSec >= 0) " · ${eta(ExportJob.etaSec)} left" else ""
                }
                nm.notify(ID, notification((ExportJob.progress * 100).toInt(), text))
                main.postDelayed(this, 1000)
            }
        })
        return START_NOT_STICKY
    }

    private fun notification(pct: Int, text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting ${ExportJob.settings?.fileName ?: ""}")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { wake?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID = 7

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ExportService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExportService::class.java))
        }

        fun eta(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    }
}
