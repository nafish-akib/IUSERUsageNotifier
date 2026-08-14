package com.example.iuserusagenotifier

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object UsageNotifier {

    private const val CHANNEL_ID = "usage_channel"
    private const val NOTIFICATION_ID = 1
    // Fallback scale (12000 hours) used when the free limit is unknown.
    private const val FALLBACK_MAX_SECONDS = 12000L * 60L * 60L

    @SuppressLint("ObsoleteSdkInt")
    fun sendUsageNotification(
        context: Context,
        usageMessage: String,
        usedSeconds: Long,
        freeSeconds: Long = FALLBACK_MAX_SECONDS
    ) {
        val maxSeconds = if (freeSeconds > 0L) freeSeconds else FALLBACK_MAX_SECONDS
        val usedClamped = usedSeconds.coerceIn(0L, maxSeconds)
        val usagePercent = usedClamped.toFloat() / maxSeconds.toFloat() * 100f

        // Choose the layout based on how much of the free limit is used.
        val layoutRes = when {
            usagePercent < 50f -> R.layout.notification_usage_green
            usagePercent < 70f -> R.layout.notification_usage_yellow
            else -> R.layout.notification_usage_red
        }

        // Inflate the chosen custom layout.
        val remoteViews = RemoteViews(context.packageName, layoutRes)

        remoteViews.setTextViewText(R.id.usage_title, "\uD83D\uDEF0\uFE0F Internet Usage Update")
        remoteViews.setTextViewText(R.id.usage_text, usageMessage)
        remoteViews.setProgressBar(R.id.usage_progressbar, maxSeconds.toInt(), usedClamped.toInt(), false)

        // Set the text color based on the current mode.
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val textColor = if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
        remoteViews.setTextColor(R.id.usage_title, textColor)
        remoteViews.setTextColor(R.id.usage_text, textColor)

        // Create the notification channel for Android O and above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Usage Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for sending usage notifications."
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Tapping the notification opens MainActivity.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Do not post if the notification permission is missing on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}