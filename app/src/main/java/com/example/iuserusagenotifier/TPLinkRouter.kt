package com.example.iuserusagenotifier

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Driver for the classic TP-Link web UI (login.html + wancfg.cmd), the
 * generation used by TL-WR840N / TL-WR841N / TL-WR940N and similar routers
 * common in IUT student rooms.
 *
 * Returns an empty string on success, or a human-readable error message.
 */
object TPLinkRouter {

    suspend fun loginAndSetPppoe(
        ip: String,
        adminUser: String,
        adminPassword: String,
        newPppoeUser: String,
        newPppoePassword: String
    ): String = withContext(Dispatchers.IO) {
        val base = "http://$ip"
        val cookieStore = mutableMapOf<String, List<Cookie>>()
        val client = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore[url.host] ?: emptyList()
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        try {
            // 1) Log in. Classic TP-Link expects the password base64-encoded.
            val encodedPassword =
                Base64.encodeToString(adminPassword.toByteArray(), Base64.NO_WRAP)
            var loggedIn = false
            for (path in listOf("/login.html", "/login.htm")) {
                val loginForm = FormBody.Builder()
                    .add("userName", adminUser)
                    .add("pcPassword", encodedPassword)
                    .build()
                client.newCall(
                    Request.Builder()
                        .url(base + path)
                        .post(loginForm)
                        .header("Referer", "$base/")
                        .build()
                ).execute().use { }
                loggedIn = cookieStore.values.flatten().any {
                    it.name.equals("Authorization", ignoreCase = true)
                }
                if (loggedIn) break
            }
            if (!loggedIn) return@withContext "Router login failed"

            // 2) Open the WAN config page and collect every form field.
            val wanResponse = client.newCall(
                Request.Builder()
                    .url("$base/wancfg.cmd")
                    .get()
                    .header("Referer", "$base/")
                    .build()
            ).execute()
            try {
                if (!wanResponse.isSuccessful) {
                    return@withContext "Failed to open WAN config (HTTP ${wanResponse.code})"
                }
                val doc = Jsoup.parse(wanResponse.body?.string().orEmpty())
                val fields = mutableMapOf<String, String>()
                for (input in doc.select("input")) {
                    val name = input.attr("name")
                    if (name.isNotEmpty()) fields[name] = input.attr("value")
                }
                for (select in doc.select("select")) {
                    val name = select.attr("name")
                    if (name.isNotEmpty()) {
                        fields[name] = select.select("option[selected]").firstOrNull()
                            ?.attr("value")
                            ?: select.attr("value")
                    }
                }
                val formAction = doc.select("form").firstOrNull { it.id() == "form0" }
                    ?: doc.select("form").firstOrNull()
                val action = formAction?.attr("action")?.ifEmpty { "/wancfg.cmd" } ?: "/wancfg.cmd"
                val actionUrl = if (action.startsWith("http")) action else base + action

                if (!fields.containsKey("wan_ppp_username")) {
                    return@withContext "PPPoE form not found (unsupported router UI?)"
                }

                // 3) Swap the credentials and submit the whole form.
                fields["wan_ppp_username"] = newPppoeUser
                fields["wan_ppp_password"] = newPppoePassword
                fields["wan_ppp_confirm"] = newPppoePassword

                val body = FormBody.Builder().apply {
                    for ((name, value) in fields) add(name, value)
                }.build()

                val saveResponse = client.newCall(
                    Request.Builder()
                        .url(actionUrl)
                        .post(body)
                        .header("Referer", "$base/wancfg.cmd")
                        .build()
                ).execute()
                try {
                    if (!saveResponse.isSuccessful) {
                        return@withContext "Router returned HTTP ${saveResponse.code}"
                    }
                    val text = saveResponse.body?.string().orEmpty().lowercase()
                    if ("success" in text || "ok" in text || "result" in text) {
                        ""
                    } else {
                        "Router did not confirm the change"
                    }
                } finally {
                    saveResponse.close()
                }
            } finally {
                wanResponse.close()
            }
        } catch (e: Exception) {
            "❌ ${e.localizedMessage ?: "Unexpected error"}"
        }
    }
}