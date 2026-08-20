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
    object DummySet : RotateResult()
    object RouterUnreachable : RotateResult()
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

/** Reads the PPPoE username the router is currently set to (CGI or Deco). */
suspend fun readActivePppoeUsername(config: RouterConfig): String? =
    if (config.type == "deco") {
        TPLinkDecoRouter.getActivePppoeUsername(
            config.ip, config.adminUser, config.adminPassword
        )
    } else {
        TPLinkRouter.getActivePppoeUsername(
            config.ip, config.adminUser, config.adminPassword
        )
    }

// Guards against MainActivity and the background worker rotating at the same
// time (they share the same process).
private val rotationInProgress = AtomicBoolean(false)

/**
 * Smart rotation used by both the app UI and the background worker.
 *
 * The decision is driven by the router's ACTUAL active PPPoE account (read
 * from the router, not from a local index — so multiple phones sharing the
 * same router all agree on the state):
 *   1. Read the active PPPoE username from the router.
 *   2. Fetch that account's usage; if it is still below the threshold, do
 *      nothing.
 *   3. Otherwise rotate to the first saved account that is still below the
 *      threshold (never to an over-quota one).
 *   4. If every saved account is exhausted, set the dummy credentials
 *      (kills PPPoE so no billable usage accrues). When any saved account
 *      drops below the threshold again, it recovers automatically.
 */
suspend fun autoRotateIfNeeded(context: Context, config: RouterConfig): RotateResult {
    if (!config.autoRotate) return RotateResult.NotNeeded
    val accounts = config.pppoeAccounts
    if (accounts.size < 2) return RotateResult.NoAccounts
    if (!rotationInProgress.compareAndSet(false, true)) return RotateResult.NotNeeded

    try {
        val activeUser = readActivePppoeUsername(config)
            ?: return RotateResult.RouterUnreachable

        val activeIndex = accounts.indexOfFirst { it.username == activeUser }
        if (activeIndex < 0) {
            // Router is on an account we don't manage.
            if (activeUser != config.dummyUser && activeUser != config.dummyPass) {
                return RotateResult.Failed(
                    "Router account \"$activeUser\" is not in the saved PPPoE list"
                )
            }
            // Dummy is active: self-heal once a saved account drops below the
            // threshold again (e.g. after the monthly quota reset).
            val recover = pickUnderThreshold(accounts, config.thresholdHours, null)
                ?: return RotateResult.AllExhausted
            return rotateTo(context, config, accounts, recover)
        }

        // Fetch the ACTIVE account's usage — that is the one burning quota.
        val data = loginAndFetchUsageData(
            accounts[activeIndex].username, accounts[activeIndex].password
        )
        if (data.message.isNotEmpty()) {
            return RotateResult.Failed("Could not fetch usage: ${data.message}")
        }
        if (data.used / 3600.0 < config.thresholdHours) {
            return RotateResult.NotNeeded
        }

        val next = pickUnderThreshold(accounts, config.thresholdHours, activeIndex)
        if (next == null) {
            return if (config.dummyUser.isNotEmpty()) {
                setDummy(context, config)
            } else {
                RotateResult.AllExhausted
            }
        }
        if (next.username == accounts[activeIndex].username) {
            return RotateResult.NotNeeded
        }
        return rotateTo(context, config, accounts, next)
    } finally {
        rotationInProgress.set(false)
    }
}

private suspend fun rotateTo(
    context: Context,
    config: RouterConfig,
    accounts: List<Account>,
    target: Account
): RotateResult {
    val error = rotateRouter(config, target.username, target.password)
    if (error.isNotEmpty()) return RotateResult.Failed(error)
    val updated = config.copy(activeIndex = accounts.indexOf(target))
    saveRouterConfig(context, updated)
    return RotateResult.Rotated(accounts.indexOf(target), target.username)
}

private suspend fun setDummy(context: Context, config: RouterConfig): RotateResult {
    val error = rotateRouter(config, config.dummyUser, config.dummyPass)
    return if (error.isEmpty()) {
        RotateResult.DummySet
    } else {
        RotateResult.Failed(error)
    }
}

/**
 * First account still below the threshold. startAfter = the active index to
 * cycle from (search starts after it); null searches every account from the
 * beginning. Accounts whose usage could not be fetched are skipped.
 */
private suspend fun pickUnderThreshold(
    accounts: List<Account>,
    thresholdHours: Double,
    startAfter: Int?
): Account? {
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
    val order = if (startAfter == null) {
        accounts.indices
    } else {
        (1..accounts.size).map { (startAfter + it) % accounts.size }
    }
    for (idx in order) {
        val entry = usageByUser[accounts[idx].username] ?: continue
        if (entry.second > 0L && entry.first / 3600.0 < thresholdHours) {
            return accounts[idx]
        }
    }
    return null
}