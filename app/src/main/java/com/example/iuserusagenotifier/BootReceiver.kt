
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

        // Only handle boot completed action in a manifest-declared receiver.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (isConnectedToUniversityWifi(context)) {
                val activePrefs = context.getSharedPreferences("IUSER_PREFS", Context.MODE_PRIVATE)
                val username = activePrefs.getString("username", "")
                val password = activePrefs.getString("password", "")
                Log.d("BootReceiver", "Wi-Fi connected. Username: $username")

                if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                    val oneTimeRequest = OneTimeWorkRequestBuilder<UsageCheckWorker>().build()
                    WorkManager.getInstance(context).enqueue(oneTimeRequest)
                }
            }
        }
    }

    private fun isConnectedToUniversityWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        // Adjust this check if you need to match a particular SSID.
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
