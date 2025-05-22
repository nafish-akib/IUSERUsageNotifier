package com.example.iuserusagenotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color



object UsageNotifier {

    private const val CHANNEL_ID = "usage_channel"
    private const val NOTIFICATION_ID = 1

    // New method that uses a custom layout with a horizontal progress bar.
    fun sendUsageNotification(context: Context, remainingUsage: Int) {
        val maxUsage = 12000
        val percentage = ((12000-remainingUsage.toFloat()) / maxUsage) * 100

        // Choose the appropriate layout resource based on usage.
        val layoutRes = when {
            percentage > 60 -> R.layout.notification_usage_green   // Use green layout
            percentage > 30 -> R.layout.notification_usage_yellow   // Use yellow layout
            else -> R.layout.notification_usage_red                  // Use red layout
        }

        // Inflate the chosen custom layout.
        val remoteViews = RemoteViews(context.packageName, layoutRes)
        // Update the common fields.
        remoteViews.setTextViewText(R.id.usage_title, "\uD83D\uDEF0\uFE0F Internet Usage Update")
        remoteViews.setTextViewText(R.id.usage_text, "$remainingUsage / $maxUsage min")
        remoteViews.setProgressBar(R.id.usage_progressbar, maxUsage, remainingUsage, false)

        // Set the text color based on the current mode.
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val textColor = if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
        remoteViews.setTextColor(R.id.usage_title, textColor)
        remoteViews.setTextColor(R.id.usage_text, textColor)

        // Create a NotificationChannel for Android O and above.
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

        // Prepare a PendingIntent so that tapping the notification opens MainActivity.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build the notification with the custom layout.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)  // Make sure this icon exists.
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews) // For expanded view (if desired)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Check for notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            // Log a warning and do not post the notification if permission is missing.
            return
        }

        // Dispatch the notification.
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
