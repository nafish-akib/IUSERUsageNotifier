package com.example.iuserusagenotifier

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

class IUserWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar

    // Auto-login once per activity, so a failed login doesn't loop.
    private var autoLoggedIn = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_iuser_web)

        // Bind and configure WebView.
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Enabling JavaScript.
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // Using shared preferences to determine if we have credentials.
        val sharedPreferences = getSharedPreferences("IUSER_PREFS", MODE_PRIVATE)
        val username = sharedPreferences.getString("username", "") ?: ""
        val password = sharedPreferences.getString("password", "") ?: ""

        // Setting up the WebViewClient to show a loading indicator.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Showing the loading indicator when page starts loading.
                progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Hide the loading indicator and stop swipe refresh when page finishes loading.
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                super.onPageFinished(view, url)

                // The portal login page is a Laravel form (CSRF token included).
                // Submit it through the form itself so the token is sent along.
                if (!autoLoggedIn && username.isNotEmpty() && password.isNotEmpty() &&
                    url?.contains("/login", ignoreCase = true) == true
                ) {
                    autoLoggedIn = true
                    val userLiteral = JSONObject.quote(username)
                    val passLiteral = JSONObject.quote(password)
                    view?.evaluateJavascript(
                        "var u=document.querySelector('input[name=\"username\"]');" +
                            "var p=document.querySelector('input[name=\"password\"]');" +
                            "var f=document.querySelector('form');" +
                            "if(u&&p&&f){u.value=$userLiteral;p.value=$passLiteral;f.submit();}",
                        null
                    )
                }
            }
        }

        // Always set the swipe-to-refresh listener.
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // The current portal root: GET /login shows the login form; after the
        // auto-submit above it lands on the dashboard.
        webView.loadUrl("http://10.220.20.12/login")

        // Using OnBackPressedDispatcher for proper back navigation.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }
}