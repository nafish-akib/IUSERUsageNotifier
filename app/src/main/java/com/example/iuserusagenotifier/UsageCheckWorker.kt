package com.example.iuserusagenotifier

import android.content.Context
import android.util.Log
import androidx.core.content.edit
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

            // A non-empty message means the fetch failed (e.g. wrong credentials).
            if (usageData.message.isNotEmpty()) {
                Log.d("UsageCheckWorker", "Fetch failed: ${usageData.message}")
                return Result.failure()
            }

            Log.d("UsageCheckWorker", "Usage fetched: ${formatDuration(usageData.used)}")

            sharedPrefs.edit { putLong("last_fetch_time", System.currentTimeMillis()) }

            // Build a human-readable message and send the notification.
            val message = if (usageData.free > 0L) {
                "Used: ${formatDuration(usageData.used)} of ${formatDuration(usageData.free)}"
            } else {
                "Used: ${formatDuration(usageData.used)}"
            }

            // Background auto-rotation: reads the router's ACTUAL active PPPoE
            // account, fetches its usage and rotates only when it crossed the
            // threshold. If all saved accounts are exhausted, dummy credentials
            // are set so no billable usage accrues. Nothing happens when the
            // router is unreachable (e.g. not on the room Wi-Fi).
            val config = loadRouterConfig(applicationContext)
            val rotationNote = when (val result =
                autoRotateIfNeeded(applicationContext, config)) {
                is RotateResult.Rotated ->
                    "\n\uD83D\uDD04 ${applicationContext.getString(R.string.rotation_worker_note_rotated, result.username)}"
                is RotateResult.DummySet ->
                    "\n\uD83D\uDEA8 ${applicationContext.getString(R.string.rotation_worker_note_dummy)}"
                is RotateResult.AllExhausted ->
                    "\n\uD83D\uDEA8 ${applicationContext.getString(R.string.rotation_worker_note_exhausted)}"
                is RotateResult.Failed ->
                    "\n\uD83D\uDD34 ${applicationContext.getString(R.string.rotation_worker_note_failed, result.error)}"
                else -> "" // NotNeeded / NoAccounts / RouterUnreachable: silent
            }

            UsageNotifier.sendUsageNotification(
                applicationContext,
                message + rotationNote,
                usageData.used,
                usageData.free
            )

            Log.d("UsageCheckWorker", "Notification sent successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e("UsageCheckWorker", "Error during work; will retry.", e)
            Result.retry()
        }
    }
}