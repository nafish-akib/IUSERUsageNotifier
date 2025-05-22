package com.example.iuserusagenotifier

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.GravityCompat
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
    private lateinit var usageValueText: TextView
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
        usageValueText = findViewById(R.id.usageValueText)
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
            onRemoveClicked = { account -> removeAccount(account) }
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

        // Show "Fetching..." initially.
        usageIndicatorView.updateMessage("Fetching...")

        lifecycleScope.launch {
            try {
                val usageData = loginAndFetchUsageData(username, password)
                // First decide based on the error message returned.
                if (usageData.message.isNotEmpty()) {
                    // If the error message is non-empty it indicates a failure (e.g., wrong credentials or data not found).
                    usageIndicatorView.showErrorMessage(usageData.message)
                } else {
                    // No error message means login succeeded.
                    // It doesn't matter if usageData.used is 0 or greater than 0;
                    // the custom view's updateProgress() will handle both,
                    // displaying "0 min used" when usage is 0
                    // or animating the change when usage > 0.
                    usageIndicatorView.updateProgress(usageData.used.toFloat())
                }
            } catch (_: Exception) {
                // This catch handles network errors or issues like no Internet/Wi-Fi.
                usageIndicatorView.showErrorMessage("⚠️ Network Error")
                Toast.makeText(this@MainActivity, "⚠️ Network Error", Toast.LENGTH_LONG).show()
            }
            updateActiveAccountDisplay()
            onComplete?.invoke()
        }
    }


    private fun removeAccount(account: Account) {
        val sharedPref = getSharedPreferences(prefsAccounts, MODE_PRIVATE)
        val json = sharedPref.getString(keyAccounts, "[]")
        val type: Type = object : TypeToken<MutableList<Account>>() {}.type
        val accounts: MutableList<Account> = try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }

        if (accounts.remove(account)) {
            sharedPref.edit { putString(keyAccounts, gson.toJson(accounts)) }
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
            val sharedPref = getSharedPreferences(prefsAccounts, MODE_PRIVATE)
            val json = sharedPref.getString(keyAccounts, "[]")
            val type: Type = object : TypeToken<List<Account>>() {}.type
            val accounts: List<Account> = try {
                gson.fromJson(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            accountAdapter.submitList(accounts)
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
            val sharedPref = getSharedPreferences(prefsAccounts, MODE_PRIVATE)
            val json = sharedPref.getString(keyAccounts, "[]")
            val type: Type = object : TypeToken<MutableList<Account>>() {}.type
            val accounts: MutableList<Account> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            if (accounts.any { it.username == username }) {
                Toast.makeText(this, getString(R.string.account_exists), Toast.LENGTH_SHORT).show()
                return
            }
            accounts.add(Account(username, password))
            sharedPref.edit().putString(keyAccounts, gson.toJson(accounts)).apply()
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
        // Get saved accounts from SharedPreferences.
        val sharedPref = getSharedPreferences(prefsAccounts, MODE_PRIVATE)
        val json = sharedPref.getString(keyAccounts, "[]")
        val type: Type = object : TypeToken<List<Account>>() {}.type
        val accounts: List<Account> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        lifecycleScope.launch {
            val usageText: String = if (accounts.isEmpty()) {
                "No accounts found."
            } else {
                // Launch concurrent network calls if needed.
                val usageResultsDeferred = accounts.mapIndexed { index, account ->
                    async {
                        try {
                            val usageData = loginAndFetchUsageData(account.username, account.password)
                            // Check if an error message was returned.
                            if (usageData.message.isNotEmpty()) {
                                "${index + 1}. ${account.username}: ${usageData.message}"
                            } else {
                                "${index + 1}. ${account.username}: ${usageData.used} min used"
                            }
                        } catch (_: Exception) {
                            "${index + 1}. ${account.username}: Error fetching usage."
                        }
                    }
                }
                val usageResults = usageResultsDeferred.awaitAll()
                usageResults.joinToString(separator = "\n")
            }

            // After the data is ready, revert the button state.
            showAllUsersUsageButton.text = "Show All"
            showAllUsersUsageButton.isEnabled = true

            // Then, show the dialog box.
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.all_users_usage))
                .setMessage(usageText)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
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







