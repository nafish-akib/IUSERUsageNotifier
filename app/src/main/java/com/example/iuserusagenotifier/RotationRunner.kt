package com.example.iuserusagenotifier

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean

sealed class RotateResult {
    object NotNeeded : RotateResult()
    object NoAccounts : RotateResult()
    object AllExhausted : RotateResult()
    data class Rotated(val index: Int, val username: String) : RotateResult()
    data class Failed(val error: String) : RotateResult()
}

/** Runs the router driver for the given config (CGI or Deco). */
suspend fun rotateRouter(config: RouterConfig, username: String, password: String): String =
    if (config.type == "deco") {
        TPLinkDecoRouter.loginAndSetPppoe(
            config.ip, config.adminUser, config.adminPassword, username, password
        )
    } else {
        TPLinkRouter.loginAndSetPppoe(
            config.ip, config.adminUser, config.adminPassword, username, password
        )
    }

// Guards against MainActivity and the background worker rotating at the same
// time (they share the same process).
private val rotationInProgress = AtomicBoolean(false)

/**
 * Smart rotation used by both the app UI and the background worker.
 *
 * When the active account is over the threshold, fetches the usage of every
 * saved account and rotates only to the first one that is still below the
 * threshold. If every saved account is exhausted (or unverifiable), nothing
 * is rotated so an over-quota account is never set as the active one.
 */
suspend fun autoRotateIfNeeded(
    context: Context,
    config: RouterConfig,
    usedSeconds: Long,
    freeSeconds: Long
): RotateResult {
    if (!config.autoRotate) return RotateResult.NotNeeded
    val accounts = config.pppoeAccounts
    if (accounts.size < 2) return RotateResult.NoAccounts
    if (usedSeconds <= 0L) return RotateResult.NotNeeded
    if (usedSeconds / 3600.0 < config.thresholdHours) return RotateResult.NotNeeded
    if (!rotationInProgress.compareAndSet(false, true)) return RotateResult.NotNeeded

    try {
        // Check the usage of every saved account in parallel.
        val usageByUser = coroutineScope {
            accounts.map { account ->
                async {
                    val data = loginAndFetchUsageData(account.username, account.password)
                    if (data.message.isEmpty()) {
                        account.username to (data.used to data.free)
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        // Pick the first account (cycling after the active one) that is still
        // below the threshold. Accounts whose usage could not be fetched are
        // skipped — we never rotate to an unknown/exhausted account blindly.
        var checked = 0
        var chosen: Pair<Int, Account>? = null
        for (step in 1..accounts.size) {
            val index = (config.activeIndex + step) % accounts.size
            val entry = usageByUser[accounts[index].username] ?: continue
            checked++
            if (entry.second > 0L && entry.first / 3600.0 < config.thresholdHours) {
                chosen = index to accounts[index]
                break
            }
        }

        val (index, account) = chosen
            ?: return if (checked == 0) {
                RotateResult.Failed("Could not verify the usage of the saved accounts")
            } else {
                RotateResult.AllExhausted
            }

        val error = rotateRouter(config, account.username, account.password)
        if (error.isNotEmpty()) return RotateResult.Failed(error)

        val updated = config.copy(activeIndex = index)
        saveRouterConfig(context, updated)
        return RotateResult.Rotated(index, account.username)
    } finally {
        rotationInProgress.set(false)
    }
}