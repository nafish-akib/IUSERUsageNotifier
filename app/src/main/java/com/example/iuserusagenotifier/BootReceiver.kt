package com.example.iuserusagenotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "Received intent: ${intent.action}")


        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val activePrefs = context.getSharedPreferences("IUSER_PREFS", Context.MODE_PRIVATE)
            val username = activePrefs.getString("username", "")
            val password = activePrefs.getString("password", "")

            // Only run a check if credentials exist and a network is already available.
            if (!username.isNullOrEmpty() && !password.isNullOrEmpty() && isConnectedToWifi(context)) {
                Log.d("BootReceiver", "Wi-Fi connected. Username: $username")
                val oneTimeRequest = OneTimeWorkRequestBuilder<UsageCheckWorker>().build()
                WorkManager.getInstance(context).enqueue(oneTimeRequest)
            }
        }
    }

    private fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // At boot the active network may not be reported yet; treat that as "no Wi-Fi".
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
