package com.example.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.ui.viewmodel.DownloadViewModel

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "turbo_idm_foreground_channel"
        const val CHANNEL_NAME = "Turbo IDM İndirme Servisi"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.example.action.START_FOREGROUND"
        const val ACTION_UPDATE = "com.example.action.UPDATE_FOREGROUND"
        const val ACTION_STOP = "com.example.action.STOP_FOREGROUND"

        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_ACTIVE_COUNT = "extra_active_count"

        fun startOrUpdate(
            context: Context,
            fileName: String,
            progress: Int,
            speedBytesPerSec: Long,
            activeCount: Int
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_SPEED, speedBytesPerSec)
                putExtra(EXTRA_ACTIVE_COUNT, activeCount)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var isForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Dosya indiriliyor"
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
                val speed = intent.getLongExtra(EXTRA_SPEED, 0L)
                val activeCount = intent.getIntExtra(EXTRA_ACTIVE_COUNT, 1).coerceAtLeast(1)

                val notification = buildForegroundNotification(fileName, progress, speed, activeCount)

                if (!isForegroundActive) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                        isForegroundActive = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, notification)
                }
            }
        }

        return START_STICKY
    }

    private fun buildForegroundNotification(
        fileName: String,
        progress: Int,
        speed: Long,
        activeCount: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedStr = DownloadViewModel.formatSpeed(speed)
        val title = if (activeCount > 1) {
            "⚡ Turbo IDM: $activeCount Dosya İndiriliyor"
        } else {
            "⚡ Turbo IDM: $fileName"
        }
        val contentText = "%$progress tamamlandı • $speedStr"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("%$progress")
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Arka planda kesintisiz indirme işlemleri için foreground servis bildirimi"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundActive = false
    }
}
