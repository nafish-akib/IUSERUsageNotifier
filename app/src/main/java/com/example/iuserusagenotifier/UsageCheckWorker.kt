package com.example.iuserusagenotifier

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UsageCheckWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val sharedPrefs = applicationContext.getSharedPreferences("IUSER_PREFS", Context.MODE_PRIVATE)
        val username = sharedPrefs.getString("username", null)
        val password = sharedPrefs.getString("password", null)

        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Log.d("UsageCheckWorker", "Missing credentials, returning failure.")
            return Result.failure()
        }

        return try {
            // Attempt to fetch usage data.
            val usageData = loginAndFetchUsageData(username, password)
            // Log retrieved usage information.
            Log.d("UsageCheckWorker", "Usage data fetched: ${usageData.message}")

            // Optionally save the fetch time.
            val currentTime = System.currentTimeMillis()
            sharedPrefs.edit().putLong("last_fetch_time", currentTime).apply()

            // Send the notification using the centralized notifier.
            UsageNotifier.sendUsageNotification(applicationContext ,usageData.used)

            Log.d("UsageCheckWorker", "Notification sent successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e("UsageCheckWorker", "Error during work; will retry.", e)
            Result.retry()
        }
    }
}
