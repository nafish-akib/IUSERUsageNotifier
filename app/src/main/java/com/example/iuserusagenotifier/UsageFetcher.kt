package com.example.iuserusagenotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class UsageData(
    val message: String,
    val used: Long,
    val free: Long = 0L
)

private const val BASE_URL = "http://10.220.20.12"

/**
 * Logs into the IUSER portal and fetches the current internet usage.
 *
 * On success [UsageData.message] is empty and [UsageData.used]/[UsageData.free]
 * hold seconds. On failure [UsageData.message] holds a human-readable error.
 */
suspend fun loginAndFetchUsageData(username: String, password: String): UsageData =
    withContext(Dispatchers.IO) {
        // A fresh client with its own cookie jar per call, so concurrent fetches
        // (e.g. "Show All Users") never share or mix up sessions.
        val cookieStore = mutableMapOf<String, List<Cookie>>()
        val client = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore[url.host] ?: emptyList()
            })
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        try {
            // 1) Load the login page and extract the CSRF token.
            val loginPage = Request.Builder()
                .url("$BASE_URL/login")
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val loginHtml = client.newCall(loginPage).execute().use { it.body?.string().orEmpty() }

            val token = Regex("""name=["']_token["']\s+value=["']([^"']+)["']""")
                .find(loginHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: return@withContext UsageData("❌ CSRF token not found", 0)

            // 2) Submit the login form.
            val loginForm = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("_token", token)
                .build()

            client.newCall(
                Request.Builder()
                    .url("$BASE_URL/login")
                    .post(loginForm)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "$BASE_URL/login")
                    .build()
            ).execute().use { }

            // 3) Open the dashboard.
            val dashboardResponse = client.newCall(
                Request.Builder()
                    .url("$BASE_URL/dashboard")
                    .get()
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "$BASE_URL/login")
                    .build()
            ).execute()

            try {
                if (!dashboardResponse.isSuccessful) {
                    return@withContext UsageData(
                        "❌ Failed to load dashboard (${dashboardResponse.code})", 0
                    )
                }

                val dashboardHtml = dashboardResponse.body?.string().orEmpty()

                // The dashboard always contains the profile table and a logout link.
                // When login fails, the server returns the login form instead.
                val loggedIn = dashboardHtml.contains("User Profile", ignoreCase = true) ||
                    dashboardHtml.contains("invoicefor", ignoreCase = true) ||
                    dashboardHtml.contains("Logout", ignoreCase = true)

                if (!loggedIn) {
                    val errorText = Jsoup.parse(dashboardHtml)
                        .select(".alert, .alert-error, .loginmsg, .error")
                        .firstOrNull()
                        ?.text()
                    return@withContext UsageData(
                        "❌ Login failed${if (errorText.isNullOrBlank()) "" else " - $errorText"}",
                        0
                    )
                }

                // 4) Parse the "invoicefor" table on the dashboard.
                val doc = Jsoup.parse(dashboardHtml)
                val data = mutableMapOf<String, String>()
                for (row in doc.select("table.invoicefor tbody tr")) {
                    val cells = row.select("td")
                    if (cells.size >= 2) {
                        data[cells[0].text().trim().removeSuffix(":")] = cells[1].text().trim()
                    }
                }

                val freeSeconds = parseDurationToSeconds(data["Free Limit"] ?: "0 secs")
                val usedSeconds = parseDurationToSeconds(data["Total Use"] ?: "0 secs")

                UsageData("", usedSeconds, freeSeconds)
            } finally {
                dashboardResponse.close()
            }
        } catch (e: Exception) {
            UsageData("❌ ${e.localizedMessage ?: "Unexpected error"}", 0)
        }
    }

internal fun parseDurationToSeconds(text: String): Long {
    var total = 0L
    val regex = Regex("""(\d+)\s*(hrs?|hours?|mins?|minutes?|secs?|seconds?)""", RegexOption.IGNORE_CASE)

    for (match in regex.findAll(text)) {
        val n = match.groupValues[1].toLongOrNull() ?: 0L
        val unit = match.groupValues[2].lowercase()
        total += when {
            unit.startsWith("h") -> n * 3600
            unit.startsWith("m") -> n * 60
            else -> n
        }
    }
    return total
}

internal fun formatDuration(secondsTotal: Long): String {
    var seconds = secondsTotal.coerceAtLeast(0)
    val hrs = seconds / 3600
    seconds %= 3600
    val mins = seconds / 60
    seconds %= 60

    return buildString {
        if (hrs > 0) append("${hrs} hr${if (hrs == 1L) "" else "s"}")
        if (mins > 0) {
            if (isNotEmpty()) append(" ")
            append("${mins} min${if (mins == 1L) "" else "s"}")
        }
        if (seconds > 0 || isEmpty()) {
            if (isNotEmpty()) append(" ")
            append("${seconds} sec${if (seconds == 1L) "" else "s"}")
        }
    }
}