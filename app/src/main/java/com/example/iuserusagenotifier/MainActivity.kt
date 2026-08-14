package com.example.iuserusagenotifier

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit


data class Account(val username: String, val password: String)

class MainActivity : AppCompatActivity() {

    // SharedPreferences keys.
    private val prefsAccounts = "IUSER_ACCOUNTS"
    private val keyAccounts = "accounts_list"
    private val prefsActive = "IUSER_PREFS"

    // UI components.
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerHandle: ImageView
    private lateinit var activeAccountBar: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var usageIndicatorView: CircularUsageIndicator
    private lateinit var accountsRecyclerView: RecyclerView
    private lateinit var notificationIntervalSpinner: Spinner
    private lateinit var showAllUsersUsageButton: MaterialButton
    private lateinit var addAccountButton: MaterialButton

    // Adapter for saved accounts.
    private lateinit var accountAdapter: AccountAdapter

    // Notification interval value in hours.
    private var notificationIntervalHours: Long = 1

    // Gson instance for handling JSON.
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        /*/ Schedule a test notification after a 2-second delay
        Handler(Looper.getMainLooper()).postDelayed({
            // Here we send a test usage value, e.g., "8000 min" used.
            // Adjust the value as needed for your testing.
            UsageNotifier.sendUsageNotification(applicationContext, 9000)
        }, 1000) */

        // Setting up drawer and then its handle.
        drawerLayout = findViewById(R.id.drawerLayout)
        drawerHandle = findViewById(R.id.drawerHandle)
        drawerHandle.setOnClickListener { drawerLayout.openDrawer(GravityCompat.END) }

        // Sidebar: Setup buttons to launch webview activities.
        findViewById<Button>(R.id.btnIUserWebview).setOnClickListener {
            startActivity(Intent(this, IUserWebActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.END)
        }
        findViewById<Button>(R.id.btnSISWebview).setOnClickListener {
            startActivity(Intent(this, SisWebActivity::class.java))
            drawerLayout.closeDrawer(GravityCompat.END)
        }

        // Bind main UI elements.
        activeAccountBar = findViewById(R.id.activeAccountBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        usageIndicatorView = findViewById(R.id.usageIndicatorView)
        accountsRecyclerView = findViewById(R.id.accountsRecyclerView)
        accountsRecyclerView.layoutManager = LinearLayoutManager(this)
        accountAdapter = AccountAdapter(
            onAccountSelected = { account ->
                val activePrefs = getSharedPreferences(prefsActive, MODE_PRIVATE)
                activePrefs.edit {
                    putString("username", account.username)
                        .putString("password", account.password)
                }
                updateActiveAccountDisplay()
                onCheckUsage()
                scheduleUsageCheck() // Scheduling periodic usage check when an account is selected.
            },
            onRemoveClicked = { account -> removeAccount(account) },
            onEditClicked = { account -> showChangePasswordDialog(account) }
        )
        accountsRecyclerView.adapter = accountAdapter

        notificationIntervalSpinner = findViewById(R.id.notificationIntervalSpinner)
        showAllUsersUsageButton = findViewById(R.id.showAllUsersUsageButton)
        addAccountButton = findViewById(R.id.addAccountButton)

        configureEdgeToEdgeUI()
        checkNotificationPermission()
        restoreSavedCredentials()
        updateAccountsList()
        setupButtonListeners()
        setupNotificationIntervalSpinner()

        swipeRefreshLayout.setColorSchemeResources(
            R.color.teal_200,
            R.color.usage_red,
            R.color.amber_500
        )
        swipeRefreshLayout.setOnRefreshListener {
            onCheckUsage {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        if (credentialsExist()) {
            onCheckUsage()
            scheduleUsageCheck() // Schedule periodic notifications when valid credentials exist.
        }
    }

    private fun configureEdgeToEdgeUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Top banner: keeps the header below the status bar / camera cutout,
        // and moves the drawer handle down so it is not covered by the camera.
        val topBanner = findViewById<View>(R.id.topBanner)
        val drawerHandleBaseTop = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val topInset = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            )
            if (topBanner.layoutParams.height != topInset) {
                topBanner.layoutParams = topBanner.layoutParams.apply { height = topInset }
            }
            val handleLp = drawerHandle.layoutParams as ViewGroup.MarginLayoutParams
            val handleTop = drawerHandleBaseTop + topInset
            if (handleLp.topMargin != handleTop) {
                handleLp.topMargin = handleTop
                drawerHandle.layoutParams = handleLp
            }
            insets
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun restoreSavedCredentials() {
        updateActiveAccountDisplay()
    }

    private fun credentialsExist(): Boolean {
        val activePrefs = getSharedPreferences(prefsActive, MODE_PRIVATE)
        val username = activePrefs.getString("username", "") ?: ""
        val password = activePrefs.getString("password", "") ?: ""
        return username.isNotEmpty() && password.isNotEmpty()
    }

    private fun setupNotificationIntervalSpinner() {
        val displayIntervals = resources.getStringArray(R.array.notification_intervals)
        // This array contains pure numbers as strings (e.g., "1", "2", etc.)
        val numericIntervals = resources.getStringArray(R.array.notification_intervals_numeric)
        val spinnerAdapter = ArrayAdapter(this, R.layout.spinner_item, displayIntervals)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        notificationIntervalSpinner.adapter = spinnerAdapter

        // Read the saved value from SharedPreferences (default is 1)
        val savedInterval = getSharedPreferences(prefsActive, MODE_PRIVATE)
            .getLong("notification_interval", 1)

        // Find the matching index in the numericIntervals array.
        for (index in numericIntervals.indices) {
            if (numericIntervals[index].toLongOrNull() == savedInterval) {
                notificationIntervalSpinner.setSelection(index)
                break
            }
        }

        notificationIntervalSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    // Use the numeric array here for conversion:
                    notificationIntervalHours = numericIntervals[position].toLongOrNull() ?: 1L
                    getSharedPreferences(prefsActive, MODE_PRIVATE)
                        .edit {
                            putLong("notification_interval", notificationIntervalHours)
                        }
                    scheduleUsageCheck() // Reschedule periodic usage checks with the new interval
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
    }


    @SuppressLint("SetTextI18n")
    private fun setupButtonListeners() {
        showAllUsersUsageButton.setOnClickListener {
            showAllUsersUsageButton.text = "Loading..."
            showAllUsersUsageButton.isEnabled = false

            // Now, calling the function that fetches data and shows the dialog.
            showAllUsersUsageDialog()
        }
        addAccountButton.setOnClickListener { showAddAccountDialog() }
    }



    private fun onCheckUsage(onComplete: (() -> Unit)? = null) {
        val activePrefs = getSharedPreferences(prefsActive, MODE_PRIVATE)
        val username = activePrefs.getString("username", "") ?: ""
        val password = activePrefs.getString("password", "") ?: ""

        // Check if credentials exist.
        if (username.isEmpty() || password.isEmpty()) {
            // Instead of "Fetching..." or network error,
            // displaying a prompt that encourages the user to add an account.
            usageIndicatorView.updateMessage("Add Account")
            onComplete?.invoke()
            return
        }

        // The IUSER portal is only reachable on the IUT campus Wi-Fi.
        if (!isConnectedToIUTWifi()) {
            // Show an animated "waiting for IUT Wi-Fi" state instead of a raw network error.
            usageIndicatorView.showIndeterminate(getString(R.string.connect_to_iut_wifi))
            onComplete?.invoke()
            return
        }

        // Show "Fetching..." initially.
        usageIndicatorView.updateMessage("Fetching...")

        lifecycleScope.launch {
            try {
                val usageData = loginAndFetchUsageData(username, password)
                // An empty message means the fetch succeeded.
                if (usageData.message.isEmpty()) {
                    // usageData is in seconds; the indicator works in minutes.
                    val maxMinutes = (usageData.free / 60L).coerceAtLeast(1L)
                    usageIndicatorView.updateProgress(
                        (usageData.used / 60L).toFloat(),
                        maxMinutes.toFloat()
                    )
                } else {
                    usageIndicatorView.showErrorMessage(usageData.message)
                }
            } catch (_: Exception) {
                // Network dropped mid-fetch: fall back to the friendly Wi-Fi prompt.
                usageIndicatorView.showIndeterminate(getString(R.string.connect_to_iut_wifi))
            }
            updateActiveAccountDisplay()
            onComplete?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun isConnectedToIUTWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        // minSdk 30, so getTransportInfo() (API 29+) is always available.
        val wifiInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo ?: return false
        val ssid = wifiInfo.ssid?.trim('"')?.trim() ?: return false
        return ssid.contains("IUT", ignoreCase = true)
    }


    private fun loadAccounts(): MutableList<Account> {
        val json = getSharedPreferences(prefsAccounts, MODE_PRIVATE)
            .getString(keyAccounts, "[]")
        val type: Type = object : TypeToken<MutableList<Account>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveAccounts(accounts: List<Account>) {
        getSharedPreferences(prefsAccounts, MODE_PRIVATE)
            .edit { putString(keyAccounts, gson.toJson(accounts)) }
    }

    private fun removeAccount(account: Account) {
        val accounts = loadAccounts()

        if (accounts.remove(account)) {
            saveAccounts(accounts)
            Toast.makeText(this, getString(R.string.account_removed), Toast.LENGTH_SHORT).show()

            // Check if the removed account is the active account.
            val activePref = getSharedPreferences(prefsActive, MODE_PRIVATE)
            val activeUsername = activePref.getString("username", "")

            if (activeUsername == account.username) {
                // Clear active account details.
                activePref.edit {
                    remove("username")
                    remove("password")
                }
                // updating the UI to reflect that no account is active.
                updateActiveAccountDisplay()
            }

            updateAccountsList()
        } else {
            Toast.makeText(this, getString(R.string.account_not_found), Toast.LENGTH_SHORT).show()
        }
    }



    private fun updateAccountsList() {
            accountAdapter.submitList(loadAccounts())
        }

        private fun showAddAccountDialog() {
            val dialogView = layoutInflater.inflate(R.layout.dialog_add_account, null)
            val dialogUsername = dialogView.findViewById<TextInputEditText>(R.id.dialogUsername)
            val dialogPassword = dialogView.findViewById<TextInputEditText>(R.id.dialogPassword)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.add_account))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.add)) { _, _ ->
                    val newUsername = dialogUsername.text.toString().trim()
                    val newPassword = dialogPassword.text.toString().trim()
                    if (newUsername.isNotEmpty() && newPassword.isNotEmpty()) {
                        onAddAccount(newUsername, newPassword)
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.enter_both_fields),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        private fun onAddAccount(username: String, password: String) {
            val accounts = loadAccounts()
            if (accounts.any { it.username == username }) {
                Toast.makeText(this, getString(R.string.account_exists), Toast.LENGTH_SHORT).show()
                return
            }
            accounts.add(Account(username, password))
            saveAccounts(accounts)
            Toast.makeText(this, getString(R.string.account_added), Toast.LENGTH_SHORT).show()
            updateAccountsList()
            getSharedPreferences(prefsActive, MODE_PRIVATE).edit().apply {
                putString("username", username)
                putString("password", password)
                apply()
            }
            updateActiveAccountDisplay()
            onCheckUsage()
            scheduleUsageCheck() // Schedule periodic usage check after adding an account
        }


    @SuppressLint("SetTextI18n")
    private fun showAllUsersUsageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_all_users_usage, null)
        val table = dialogView.findViewById<TableLayout>(R.id.usageTable)
        val emptyText = dialogView.findViewById<TextView>(R.id.usageTableEmpty)

        addTableHeader(table)

        lifecycleScope.launch {
            try {
                val accounts = loadAccounts()
                if (accounts.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    table.visibility = View.GONE
                } else {
                    // Fetch every account in parallel, then fill the table.
                    val usageResultsDeferred = accounts.mapIndexed { index, account ->
                        async {
                            try {
                                val usageData = loginAndFetchUsageData(account.username, account.password)
                                if (usageData.message.isNotEmpty()) {
                                    Triple(index, account.username, listOf(usageData.message, "—"))
                                } else {
                                    val remaining = (usageData.free - usageData.used).coerceAtLeast(0L)
                                    Triple(
                                        index,
                                        account.username,
                                        listOf(
                                            "${formatDuration(usageData.used)} used",
                                            if (usageData.free > 0L) "${formatDuration(remaining)} left" else "—"
                                        )
                                    )
                                }
                            } catch (_: Exception) {
                                Triple(
                                    index,
                                    account.username,
                                    listOf(getString(R.string.error_fetching_usage), "—")
                                )
                            }
                        }
                    }
                    val results = usageResultsDeferred.awaitAll().sortedBy { it.first }
                    for ((index, username, cells) in results) {
                        addTableRow(table, listOf("${index + 1}", username, cells[0], cells[1]))
                    }
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.all_users_usage))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } finally {
                // Always revert the button state, even on failure.
                showAllUsersUsageButton.text = getString(R.string.show_usage)
                showAllUsersUsageButton.isEnabled = true
            }
        }
    }

    private fun addTableHeader(table: TableLayout) {
        val row = TableRow(this)
        addTableCell(row, "#", isHeader = true, weight = 0f)
        addTableCell(row, getString(R.string.username_hint), isHeader = true, weight = 1f)
        addTableCell(row, getString(R.string.used), isHeader = true, weight = 1f)
        addTableCell(row, getString(R.string.remaining), isHeader = true, weight = 1f)
        table.addView(row)
    }

    private fun addTableRow(table: TableLayout, cells: List<String>) {
        val row = TableRow(this)
        cells.forEachIndexed { index, text ->
            addTableCell(row, text, isHeader = false, weight = if (index == 0) 0f else 1f)
        }
        table.addView(row)
    }

    private fun addTableCell(row: TableRow, text: String, isHeader: Boolean, weight: Float) {
        val cell = TextView(this).apply {
            this.text = text
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = if (isHeader) 15f else 14f
            typeface = if (isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (isHeader) {
                setBackgroundColor(resources.getColor(R.color.teal_200, null))
                setTextColor(Color.BLACK)
            }
        }
        row.addView(
            cell,
            TableRow.LayoutParams(
                if (weight > 0f) 0 else TableRow.LayoutParams.WRAP_CONTENT,
                TableRow.LayoutParams.WRAP_CONTENT,
                weight
            )
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showChangePasswordDialog(account: Account) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val dialogUsername = dialogView.findViewById<TextInputEditText>(R.id.dialogUsername)
        val dialogPassword = dialogView.findViewById<TextInputEditText>(R.id.dialogPassword)
        dialogUsername.setText(account.username)
        dialogPassword.setText(account.password)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.change_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        // Validate before closing so the dialog stays open on empty input.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPassword = dialogPassword.text.toString().trim()
                if (newPassword.isEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_password), Toast.LENGTH_SHORT).show()
                } else {
                    updateAccountPassword(account, newPassword)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun updateAccountPassword(account: Account, newPassword: String) {
        val accounts = loadAccounts()
        val index = accounts.indexOfFirst { it.username == account.username }
        if (index < 0) {
            Toast.makeText(this, getString(R.string.account_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        accounts[index] = accounts[index].copy(password = newPassword)
        saveAccounts(accounts)

        // If the edited account is active, keep the active credentials in sync.
        val activePrefs = getSharedPreferences(prefsActive, MODE_PRIVATE)
        if (activePrefs.getString("username", "") == account.username) {
            activePrefs.edit { putString("password", newPassword) }
        }

        updateAccountsList()
        Toast.makeText(this, getString(R.string.password_updated), Toast.LENGTH_SHORT).show()
    }



    private fun updateActiveAccountDisplay() {
            val activePrefs = getSharedPreferences(prefsActive, MODE_PRIVATE)
            val activeUsername = activePrefs.getString("username", "None") ?: "None"
            activeAccountBar.text = getString(R.string.active_account, activeUsername)
        }

        // --- Scheduling Periodic Notifications using WorkManager ---
        private fun scheduleUsageCheck() {
            // Get the currently selected interval (in hours) from SharedPreferences.
            val intervalHours = getSharedPreferences(prefsActive, MODE_PRIVATE)
                .getLong("notification_interval", 1)
            // Minimum period for PeriodicWorkRequest is 15 minutes.
            if (intervalHours < 1) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<UsageCheckWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "usage_check",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest
                )

            Toast.makeText(
                this,
                "Scheduled usage check every $intervalHours hour(s)",
                Toast.LENGTH_SHORT
            ).show()
        }


    }







