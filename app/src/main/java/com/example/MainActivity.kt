package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DownloadViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    DashboardScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Save live in-memory progress to Room when user backgrounds the app
        viewModel.flushActiveDownloadsToDatabase()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    extractUrlFromText(sharedText)?.let { url ->
                        viewModel.setUrlInput(url)
                        viewModel.selectTab(0)
                        val fileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "Paylaşılan Link" }
                        viewModel.setCapturedUrlBanner("Paylaşılan bağlantı yakalandı: $fileName")
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: intent.data?.toString()
                if (!url.isNullOrBlank() && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                    viewModel.setUrlInput(url)
                    viewModel.selectTab(0)
                    val fileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "İndirme Dosyası" }
                    viewModel.setCapturedUrlBanner("Sistem indirme bağlantısı yakalandı: $fileName")

                    // Extract referrer if provided by browser / system
                    val referrerUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_REFERRER, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_REFERRER)
                    }
                    val referrer = referrerUri?.toString()
                        ?: intent.getStringExtra("android.intent.extra.REFERRER_NAME")
                    if (!referrer.isNullOrBlank()) {
                        viewModel.setReferer(referrer)
                    }

                    // Extract headers bundle if provided by Chrome / Android browser
                    val headersBundle = intent.getBundleExtra("com.android.browser.headers")
                    headersBundle?.let { bundle ->
                        bundle.getString("Referer")?.let { viewModel.setReferer(it) }
                        bundle.getString("Cookie")?.let { viewModel.setCookies(it) }
                        bundle.getString("User-Agent")?.let {
                            viewModel.setCustomUserAgent(it)
                            viewModel.setUserAgentPreset(com.example.data.model.ChromePreset.CUSTOM)
                        }
                    }
                }
            }
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val match = Regex("""https?://[^\s]+""").find(text)
        return match?.value
    }
}
