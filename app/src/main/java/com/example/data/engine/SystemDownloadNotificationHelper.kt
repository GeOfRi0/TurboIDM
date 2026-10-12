package com.example.data.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.DownloadEntity
import com.example.ui.viewmodel.DownloadViewModel

class SystemDownloadNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "turbo_idm_downloads"
        const val CHANNEL_NAME = "Aktif İndirmeler"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Turbo IDM indirme ilerleme durumu ve bildirim kartları"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun updateProgressNotification(item: DownloadEntity, speedBytes: Long) {
        val total = item.totalBytes
        val downloaded = item.downloadedBytes
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

        val eta = DownloadViewModel.calculateEta(downloaded, total, speedBytes)
        val speedStr = DownloadViewModel.formatSpeed(speedBytes)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.fileName)
            .setContentText("$percent% • $speedStr • Kalan: $eta")
            .setSubText("$percent%")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)

        try {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Notification permission might not be granted by user
        }
    }

    fun cancelNotification(id: String) {
        try {
            NotificationManagerCompat.from(context).cancel(id.hashCode())
        } catch (e: Exception) {
            // Ignore
        }
    }
}
