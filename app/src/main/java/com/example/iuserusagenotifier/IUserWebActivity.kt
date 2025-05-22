package com.example.iuserusagenotifier

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View  // Make sure this import is added
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.URLEncoder
import java.nio.charset.Charset

class IUserWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_iuser_web)

        // Bind and configure WebView.
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Enable JavaScript.
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true

        // Set up the WebViewClient to show a loading indicator.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Show the loading indicator when page starts loading.
                progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Hide the loading indicator and stop swipe refresh when page finishes loading.
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                super.onPageFinished(view, url)
            }
        }

        // Always set the swipe-to-refresh listener.
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // Use shared preferences to determine if we have credentials.
        val sharedPreferences = getSharedPreferences("IUSER_PREFS", Context.MODE_PRIVATE)
        val username = sharedPreferences.getString("username", "") ?: ""
        val password = sharedPreferences.getString("password", "") ?: ""

        if (username.isNotEmpty() && password.isNotEmpty()) {
            val postData = "username=${URLEncoder.encode(username, "UTF-8")}" +
                    "&password=${URLEncoder.encode(password, "UTF-8")}"
            webView.postUrl(
                "http://10.220.20.12/index.php/home/loginProcess",
                postData.toByteArray(Charset.forName("UTF-8"))
            )
        } else {
            webView.loadUrl("http://10.220.20.12/index.php/home")
        }

        // Use OnBackPressedDispatcher for proper back navigation.
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
