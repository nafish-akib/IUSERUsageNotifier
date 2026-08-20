package com.example.iuserusagenotifier

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Driver for the TP-Link CGI interface (TL-WR845N and similar classic models).
 *
 * Reverse-engineered against a live TL-WR845N admin panel. The protocol:
 *   1. Auth is a plain cookie: `Authorization=Basic base64(user:pass)` — no POST login.
 *   2. All reads/writes are POSTs to `/cgi?<action>&_=<timestamp>` with a
 *      text/plain body of `[OID#stack#0,0,0,0,0,0]index,count\r\n` followed by
 *      `key=value\r\n` lines (a trailing CRLF is required).
 *   3. Actions: 1 = GET, 2 = SET, 5 = GL (list).
 *   4. Discovery: GL `WAN_COMMON_INTF_CFG` -> find WANAccessType=Ethernet stack,
 *      then GL `WAN_PPP_CONN` -> find the enabled PPPoE instance.
 *   5. SET `WAN_PPP_CONN` with enable/username/password + passthrough fields.
 *      Response `[error]71014` means success.
 *
 * Returns an empty string on success, or a human-readable error message.
 */
object TPLinkRouter {

    private const val ACTION_GET = 1
    private const val ACTION_SET = 2
    private const val ACTION_GL = 5
    private const val STACK_NULL = "0,0,0,0,0,0"
    private const val OID_COMMON_INTF = "WAN_COMMON_INTF_CFG"
    private const val OID_PPP_CONN = "WAN_PPP_CONN"
    private const val JSON = "text/plain; charset=utf-8"

    private class Instance(val stack: String, val fields: MutableMap<String, String> = mutableMapOf())

    suspend fun loginAndSetPppoe(
        ip: String,
        adminUser: String,
        adminPassword: String,
        newPppoeUser: String,
        newPppoePassword: String
    ): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val auth = "Basic " + Base64.encodeToString(
            "$adminUser:$adminPassword".toByteArray(),
            Base64.NO_WRAP
        )

        try {
            // 1) Find the Ethernet WAN interface stack.
            val intfList = cgi(client, ip, auth, ACTION_GL, OID_COMMON_INTF, STACK_NULL, listOf("WANAccessType"))
            val ethStack = intfList.firstOrNull { it.fields["WANAccessType"] == "Ethernet" }?.stack
                ?: return@withContext "Ethernet WAN interface not found"

            // 2) Find the enabled PPPoE connection stack.
            val pppList = cgi(client, ip, auth, ACTION_GL, OID_PPP_CONN, STACK_NULL, listOf("enable"))
            val pppStack = pppList.firstOrNull { it.fields["enable"] == "1" }?.stack
                ?: pppList.firstOrNull()?.stack
                ?: return@withContext "PPPoE connection not found"

            // 3) Read the current config to preserve passthrough fields.
            val current = cgi(client, ip, auth, ACTION_GET, OID_PPP_CONN, pppStack)
                .firstOrNull()
            val secondConn = current?.fields?.get("secondConnection") ?: "sec_conn_disable"
            val trigger = current?.fields?.get("connectionTrigger") ?: "AlwaysOn"

            // 4) Apply the new credentials.
            val attrs = listOf(
                "enable=1",
                "username=$newPppoeUser",
                "password=$newPppoePassword",
                "secondConnection=$secondConn",
                "connectionTrigger=$trigger"
            )
            val result = cgi(client, ip, auth, ACTION_SET, OID_PPP_CONN, pppStack, attrs)
            if (result.isEmpty()) {
                "PPPoE credentials switched (verify on the router status page)"
            } else {
                "Router error: ${result[0].fields["error"] ?: "unknown"}"
            }
        } catch (e: Exception) {
            "❌ ${e.localizedMessage ?: "Unexpected error"}"
        }
    }

    /**
     * Reads the PPPoE username currently configured on the router (the CGI GET
     * of the PPPoE connection includes the live username), or null on failure.
     * This is how the app learns which account the router is really dialing
     * with, so rotation never has to trust a local index.
     */
    suspend fun getActivePppoeUsername(
        ip: String,
        adminUser: String,
        adminPassword: String
    ): String? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val auth = "Basic " + Base64.encodeToString(
            "$adminUser:$adminPassword".toByteArray(),
            Base64.NO_WRAP
        )

        try {
            val intfList = cgi(client, ip, auth, ACTION_GL, OID_COMMON_INTF, STACK_NULL, listOf("WANAccessType"))
            val ethStack = intfList.firstOrNull { it.fields["WANAccessType"] == "Ethernet" }?.stack
                ?: return@withContext null
            val pppList = cgi(client, ip, auth, ACTION_GL, OID_PPP_CONN, STACK_NULL, listOf("enable"))
            val pppStack = pppList.firstOrNull { it.fields["enable"] == "1" }?.stack
                ?: pppList.firstOrNull()?.stack
                ?: return@withContext null
            val current = cgi(client, ip, auth, ACTION_GET, OID_PPP_CONN, pppStack)
                .firstOrNull() ?: return@withContext null
            current.fields["username"]?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Runs one CGI action. [attrs] may be plain names (for GL) or `key=value`
     * lines (for GET/SET attribute requests). Returns parsed instances, or an
     * instance holding "error" if the router reported a failure.
     */
    private fun cgi(
        client: OkHttpClient,
        ip: String,
        auth: String,
        action: Int,
        oid: String,
        stack: String,
        attrs: List<String> = emptyList()
    ): List<Instance> {
        val bodyText = buildString {
            append("[$oid#$stack#$STACK_NULL]0,${attrs.size}\r\n")
            if (attrs.isNotEmpty()) {
                append(attrs.joinToString("\r\n"))
                append("\r\n")
            }
        }
        val request = Request.Builder()
            .url("http://$ip/cgi?$action&_=${System.currentTimeMillis()}")
            .post(bodyText.toRequestBody(JSON.toMediaType()))
            .header("Cookie", "Authorization=$auth")
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "http://$ip/mainFrame.htm")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return listOf(Instance("", mutableMapOf("error" to "HTTP ${response.code}")))
            }
            return parseCgiResponse(response.body?.string().orEmpty())
        }
    }

    private fun parseCgiResponse(text: String): List<Instance> {
        val instances = mutableListOf<Instance>()
        var current: Instance? = null
        for (line in text.split("\n")) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                val stack = trimmed.substring(1, if (end > 0) end else trimmed.length)
                if (stack == "error") break // [error]71014 = end/success marker
                current = Instance(stack)
                instances.add(current)
            } else {
                val eq = trimmed.indexOf('=')
                if (eq > 0 && current != null) {
                    current.fields[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                }
            }
        }
        return instances
    }
}