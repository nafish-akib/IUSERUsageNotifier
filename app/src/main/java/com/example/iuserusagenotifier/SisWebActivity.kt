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

class SisWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar  // Reference to the ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sis_web)

        // Bind UI components.
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Enable JavaScript.
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true

        // Setting up WebViewClient to manage loading state.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Showing the ProgressBar when page starts loading.
                progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Hide the ProgressBar once the page is loaded.
                progressBar.visibility = View.GONE
                // Stop the refreshing animation if it was active.
                swipeRefreshLayout.isRefreshing = false
                super.onPageFinished(view, url)
            }
        }

        // Load the SIS login URL.
        webView.loadUrl("https://sis.iutoic-dhaka.edu/login")

        // Set up the swipe-to-refresh listener.
        swipeRefreshLayout.setOnRefreshListener {
            // Reloading the webpage on user swipe.
            webView.reload()
        }

        // Handling proper back navigation using OnBackPressedDispatcher.
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
