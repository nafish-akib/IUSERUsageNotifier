// UsageNotifier.kt
package com.example.iuserusagenotifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object UsageNotifier {
    fun sendNotification(context: Context, title: String, message: String) {
        val channelId = "usage_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Usage Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}


// BootReceiver.kt
package com.example.iuserusagenotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == ConnectivityManager.CONNECTIVITY_ACTION) {
            if (isOnUniversityWifi(context)) {
                val prefs = context.getSharedPreferences("IUSER_PREFS", Context.MODE_PRIVATE)
                val username = prefs.getString("username", "") ?: ""
                val password = prefs.getString("password", "") ?: ""
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    val work = OneTimeWorkRequestBuilder<UsageCheckWorker>().build()
                    WorkManager.getInstance(context).enqueue(work)
                }
            }
        }
    }

    private fun isOnUniversityWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val ssid = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .connectionInfo.ssid.replace("\"", "")
            return ssid.contains("IUT", ignoreCase = true)
        }
        return false
    }
}
