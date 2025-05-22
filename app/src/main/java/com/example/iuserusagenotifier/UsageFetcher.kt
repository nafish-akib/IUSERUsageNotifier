package com.example.iuserusagenotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class UsageData(val message: String, val used: Int)

suspend fun loginAndFetchUsageData(username: String, password: String): UsageData =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().followRedirects(false).build()

        val loginForm = FormBody.Builder().apply {
            add("username", username)
            add("password", password)
        }.build()

        val loginRequest = Request.Builder()
            .url("http://10.220.20.12/index.php/home/loginProcess")
            .post(loginForm)
            .build()

        client.newCall(loginRequest).execute().use { loginResponse ->
            val cookies = loginResponse.headers("Set-Cookie")
            val cookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }
            val redirectUrl = loginResponse.header("Location")
            if (redirectUrl == null || !redirectUrl.contains("dashboard")) {
                return@withContext UsageData("❌ Login failed!", 0)
            }
            val dashboardRequest = Request.Builder()
                .url("http://10.220.20.12/index.php/home/dashboard")
                .header("Cookie", cookieHeader)
                .build()

            client.newCall(dashboardRequest).execute().use { dashboardResponse ->
                val html = dashboardResponse.body?.string() ?: return@withContext UsageData("❌ Couldn't load.", 0)
                val usageRegex = Regex("""Total Use:</td>\s*<td>(\d+)\s*Minute""")
                val match = usageRegex.find(html)
                if (match != null) {
                    val used = match.groupValues[1].toIntOrNull() ?: 0//0
                    return@withContext UsageData("", used)
                } else {
                    return@withContext UsageData("❌ not found!", 0)
                }
            }
        }
    }
