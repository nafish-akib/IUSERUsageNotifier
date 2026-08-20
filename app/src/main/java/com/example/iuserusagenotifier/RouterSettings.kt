package com.example.iuserusagenotifier

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persisted router + PPPoE rotation settings.
 *
 * [pppoeAccounts] holds the campus credentials to rotate through; [activeIndex]
 * is the credential currently set in the router (the next check rotates to
 * [activeIndex] + 1).
 */
data class RouterConfig(
    val type: String = "cgi",
    val ip: String = "192.168.0.1",
    val adminUser: String = "admin",
    val adminPassword: String = "",
    val autoRotate: Boolean = true,
    val thresholdHours: Double = 191.67,
    val pppoeAccounts: List<Account> = emptyList(),
    val activeIndex: Int = 0,
    val dummyUser: String = "00000000",
    val dummyPass: String = "00000000"
)

private const val PREFS_ROUTER = "IUSER_ROUTER"
private const val KEY_ROUTER_CONFIG = "router_config"

private val routerGson = Gson()

fun loadRouterConfig(context: Context): RouterConfig {
    val json = context.getSharedPreferences(PREFS_ROUTER, Context.MODE_PRIVATE)
        .getString(KEY_ROUTER_CONFIG, null)
        ?: return RouterConfig()
    return try {
        routerGson.fromJson(json, RouterConfig::class.java) ?: RouterConfig()
    } catch (_: Exception) {
        RouterConfig()
    }
}

fun saveRouterConfig(context: Context, config: RouterConfig) {
    context.getSharedPreferences(PREFS_ROUTER, Context.MODE_PRIVATE)
        .edit { putString(KEY_ROUTER_CONFIG, routerGson.toJson(config)) }
}