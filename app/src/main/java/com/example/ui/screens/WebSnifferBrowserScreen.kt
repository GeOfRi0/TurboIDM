package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.window.Dialog
import com.example.ui.components.VideoQualitySelectorDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChromePreset
import com.example.data.model.ExtractedMedia
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSnifferBrowserScreen(
    viewModel: DownloadViewModel
) {
    val context = LocalContext.current
    val browserUrl by viewModel.browserUrl.collectAsStateWithLifecycle()
    val capturedMediaList by viewModel.capturedMediaList.collectAsStateWithLifecycle()
    val showQualitySelector by viewModel.showQualitySelector.collectAsStateWithLifecycle()
    val qualityOptions by viewModel.qualityOptions.collectAsStateWithLifecycle()
    val selectedThreads by viewModel.selectedThreads.collectAsStateWithLifecycle()
    val isAnalyzingResolutions by viewModel.isAnalyzingResolutions.collectAsStateWithLifecycle()

    var urlInputText by remember { mutableStateOf(browserUrl) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var isDesktopMode by remember { mutableStateOf(true) }
    var showCapturedListSheet by remember { mutableStateOf(false) }

    LaunchedEffect(browserUrl) {
        if (browserUrl.isNotBlank() && browserUrl != urlInputText) {
            urlInputText = browserUrl
            webViewInstance?.loadUrl(browserUrl)
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewInstance?.goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        // 1. Navigation & Address Bar
        Surface(
            color = DarkSurface,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViewInstance?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = if (canGoBack) TextPrimary else TextMuted
                        )
                    }

                    IconButton(
                        onClick = { webViewInstance?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "İleri",
                            tint = if (canGoForward) TextPrimary else TextMuted
                        )
                    }

                    IconButton(
                        onClick = { webViewInstance?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = CyberCyan
                        )
                    }

                    // URL Input Field
                    OutlinedTextField(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_address_bar"),
                        placeholder = {
                            Text("URL veya site ara...", fontSize = 12.sp, color = TextMuted)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = normalizeBrowserUrl(urlInputText)
                                urlInputText = target
                                webViewInstance?.loadUrl(target)
                            }
                        ),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant,
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        trailingIcon = {
                            if (urlInputText.isNotBlank()) {
                                IconButton(
                                    onClick = { urlInputText = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Temizle",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Desktop mode toggle
                    IconButton(
                        onClick = {
                            isDesktopMode = !isDesktopMode
                            webViewInstance?.let { wv ->
                                wv.settings.userAgentString = if (isDesktopMode) {
                                    DESKTOP_CHROME_USER_AGENT
                                } else {
                                    ChromePreset.CHROME_ANDROID.userAgent
                                }
                                wv.settings.mediaPlaybackRequiresUserGesture = false
                                wv.reload()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.Computer else Icons.Default.Smartphone,
                            contentDescription = "Masaüstü Modu",
                            tint = if (isDesktopMode) ElectricBlue else TextSecondary
                        )
                    }
                }

                // Quick Site Shortcuts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        "YouTube" to "https://m.youtube.com",
                        "Instagram" to "https://www.instagram.com",
                        "X / Twitter" to "https://x.com",
                        "TikTok" to "https://www.tiktok.com",
                        "Google" to "https://www.google.com"
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyberCyan.copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
                        modifier = Modifier.clickable {
                            webViewInstance?.evaluateJavascript(
                                "(function(){ if (window.triggerMediaSniffing) window.triggerMediaSniffing(); })();",
                                null
                            )
                            android.widget.Toast.makeText(context, "Sayfadaki video akışları ve oynatıcılar otomatik tetiklendi ⚡", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Videoları Tara",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                        }
                    }

                    presets.forEach { (name, target) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier.clickable {
                                urlInputText = target
                                webViewInstance?.loadUrl(target)
                            }
                        ) {
                            Text(
                                text = name,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(2.dp),
                        color = CyberCyan,
                        trackColor = Color.Transparent
                    )
                }
            }
        }

        // 2. Main WebView Area with Floating Sniffer Badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = DESKTOP_CHROME_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = true
                            allowContentAccess = true
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun getDefaultVideoPoster(): Bitmap? {
                                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            }
                        }

                        // JavaScript Interface to catch HTML5 video events
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onMediaDetected(url: String, title: String) {
                                    post {
                                        viewModel.addCapturedMedia(
                                            url = url,
                                            title = title.ifBlank { pageTitle.ifBlank { "Web Videosu" } },
                                            mimeType = "video/mp4"
                                        )
                                    }
                                }
                            },
                            "AndroidMediaSniffer"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                url?.let {
                                    urlInputText = it
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                pageTitle = view?.title.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true

                                // Inject advanced video auto-detector, iframe inspector and play button clicker
                                view?.evaluateJavascript(INJECTED_MEDIA_SNIFFER_JS, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null) return null
                                val reqUrl = request.url?.toString() ?: return null
                                val isForMainFrame = request.isForMainFrame
                                val headers = request.requestHeaders ?: emptyMap()
                                val lower = reqUrl.lowercase()

                                // Exclude static assets
                                val isStaticAsset = (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                                    lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp") ||
                                    lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".woff") ||
                                    lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".ico") ||
                                    lower.contains("/analytics") || lower.contains("/pixel") || lower.contains("/telemetry") ||
                                    lower.contains("google-analytics.com") || lower.contains("googletagmanager.com")) &&
                                    !lower.contains("videoplayback")

                                if (isStaticAsset) {
                                    return super.shouldInterceptRequest(view, request)
                                }

                                // Exclude Instagram photos
                                val isInstagramPhoto = lower.contains("cdninstagram.com") &&
                                    (lower.contains("t51.") || lower.contains("dst-jpg") || lower.contains(".jpg") || lower.contains(".webp") || lower.contains(".png"))

                                if (isInstagramPhoto) {
                                    return super.shouldInterceptRequest(view, request)
                                }

                                val secDest = headers["Sec-Fetch-Dest"]?.lowercase() ?: ""
                                val accept = headers["Accept"]?.lowercase() ?: ""
                                val isMediaHeader = secDest == "video" || secDest == "audio" ||
                                    accept.contains("video/") || accept.contains("audio/")

                                // Direct video stream detection (applies to both main frame and iframe requests)
                                val isDirectVideoStream = (lower.contains(".mp4") || lower.contains(".m3u8") ||
                                    lower.contains(".webm") || lower.contains(".ts") || lower.contains(".mpd") ||
                                    lower.contains(".m4s") || lower.contains(".m4v") || lower.contains(".mov") ||
                                    lower.contains(".flv") || lower.contains(".f4v") || lower.contains(".mkv") ||
                                    lower.contains("videoplayback") || (lower.contains("cdninstagram.com") && lower.contains("t50.")) ||
                                    lower.contains("/hls/") || lower.contains("/dash/") || lower.contains("playlist.m3u8") ||
                                    lower.contains("master.m3u8") || lower.contains("chunklist") ||
                                    lower.contains("mime=video") || lower.contains("format=m3u8") || lower.contains("type=video") ||
                                    isMediaHeader)

                                // Subframe / iframe embed request detection
                                // CRITICAL: Intercept all iframe (subframe) requests to capture embedded players!
                                val isIframeRequest = !isForMainFrame || secDest == "iframe" || secDest == "frame"
                                val isIframeEmbed = isIframeRequest && (
                                    lower.contains("youtube.com/embed") || lower.contains("youtu.be/") ||
                                    lower.contains("player.vimeo.com") || lower.contains("dailymotion.com/embed") ||
                                    lower.contains("/embed/") || lower.contains("/player/") || lower.contains("/iframe/") ||
                                    lower.contains("/video/") || lower.contains("/e/") || lower.contains("/v/") ||
                                    lower.contains("embed.php") || lower.contains("player.php") ||
                                    lower.contains("streamtape.com") || lower.contains("dood.") || lower.contains("vidmoly.") ||
                                    lower.contains("streamwish.") || lower.contains("filemoon.") || lower.contains("mixdrop.") ||
                                    lower.contains("mp4upload.")
                                )

                                if (isDirectVideoStream || isIframeEmbed) {
                                    val isYouTube = lower.contains("videoplayback") || lower.contains("googlevideo.com")
                                    val cleanStreamUrl = if (isYouTube && reqUrl.contains("&range=")) {
                                        reqUrl.replace(Regex("""&range=\d+-\d+"""), "")
                                    } else {
                                        reqUrl
                                    }

                                    val mime = when {
                                        lower.contains(".m3u8") || lower.contains("format=m3u8") -> "application/vnd.apple.mpegurl"
                                        lower.contains(".mpd") -> "application/dash+xml"
                                        lower.contains(".webm") -> "video/webm"
                                        lower.contains("itag=140") || secDest == "audio" -> "audio/mp4"
                                        isIframeEmbed -> "video/mp4"
                                        else -> "video/mp4"
                                    }

                                    val streamTitle = when {
                                        isYouTube -> {
                                            val quality = when {
                                                lower.contains("itag=18") -> "360p"
                                                lower.contains("itag=22") -> "720p HD"
                                                lower.contains("itag=137") -> "1080p HD"
                                                lower.contains("itag=140") -> "Ses (M4A)"
                                                else -> "MP4"
                                            }
                                            "YouTube Video ($quality) - ${pageTitle.ifBlank { "Akış" }}"
                                        }
                                        isIframeEmbed -> {
                                            "Gömülü İframe Video - ${pageTitle.ifBlank { "Player" }}"
                                        }
                                        lower.contains("instagram.com") || lower.contains("cdninstagram.com") -> {
                                            "Instagram Video - ${pageTitle.ifBlank { "Reel" }}"
                                        }
                                        else -> pageTitle.ifBlank { "Web Medya Akışı" }
                                    }

                                    post {
                                        viewModel.addCapturedMedia(
                                            url = cleanStreamUrl,
                                            title = streamTitle,
                                            mimeType = mime
                                        )
                                    }

                                    // Deep sniff direct stream URLs inside iframe embed asynchronously
                                    if (isIframeEmbed && !isDirectVideoStream) {
                                        viewModel.sniffSubframeMedia(reqUrl, pageTitle)
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                url: String?
                            ): WebResourceResponse? {
                                val reqUrl = url ?: return null
                                val lower = reqUrl.lowercase()
                                if (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains("videoplayback") || lower.contains(".webm")) {
                                    post {
                                        viewModel.addCapturedMedia(
                                            url = reqUrl,
                                            title = pageTitle.ifBlank { "Web Video Akışı" },
                                            mimeType = if (lower.contains(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4"
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, url)
                            }
                        }

                        val start = normalizeBrowserUrl(browserUrl)
                        loadUrl(start)
                        webViewInstance = this
                    }
                },
                update = { wv ->
                    webViewInstance = wv
                    wv.settings.mediaPlaybackRequiresUserGesture = false
                },
                modifier = Modifier.fillMaxSize()
            )

            // 3. Floating "Yakalanan Medyalar" (Captured Media Pill)
            androidx.compose.animation.AnimatedVisibility(
                visible = capturedMediaList.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Surface(
                    onClick = { showCapturedListSheet = true },
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF0F1E36),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan),
                    shadowElevation = 8.dp,
                    modifier = Modifier.testTag("floating_captured_media_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(CyberCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF070B14),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Video Yakalandı!",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentGreen)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${capturedMediaList.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black
                                    )
                                }
                            }
                            Text(
                                text = "Çözünürlük Seç (1080p, 720p...) 🎬",
                                fontSize = 11.sp,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet showing all intercepted streams
    if (showCapturedListSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showCapturedListSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚡ Yakalanan Medya Akışları",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Web sayfasında oynatılan videolar tespit edildi",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    TextButton(onClick = { viewModel.clearCapturedMedia() }) {
                        Text("Temizle", color = Color(0xFFFF5252), fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quality Analyzer banner for all captured streams
                Surface(
                    onClick = {
                        val firstMedia = capturedMediaList.firstOrNull()
                        if (firstMedia != null) {
                            showCapturedListSheet = false
                            viewModel.analyzeAndSelectResolution(firstMedia, urlInputText)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = CyberCyan.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("analyze_resolutions_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎬 Video Çözünürlüklerini Analiz Et",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                            Text(
                                text = "1080p FHD, 720p HD, 480p SD seçeneklerini keşfet",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                capturedMediaList.forEachIndexed { index, media ->
                    Surface(
                        onClick = {
                            showCapturedListSheet = false
                            viewModel.analyzeAndSelectResolution(media, urlInputText)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("captured_media_card_$index"),
                        shape = RoundedCornerShape(14.dp),
                        color = DarkCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CyberCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = media.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = media.url,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = media.mimeType,
                                        fontSize = 10.sp,
                                        color = ElectricBlue,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "• Dokun: Çözünürlük Seç",
                                        fontSize = 10.sp,
                                        color = AccentGreen
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Action 1: "Çözünürlük Seç" (1080p, 720p, 480p)
                            Button(
                                onClick = {
                                    showCapturedListSheet = false
                                    viewModel.analyzeAndSelectResolution(media, urlInputText)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color(0xFF070B14)
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("select_resolution_btn_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = "Kalite Seç",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Çözünürlük", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Action 2: Direct Quick Download
                            IconButton(
                                onClick = {
                                    showCapturedListSheet = false
                                    viewModel.startDirectDownload(media.url)
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("direct_download_btn_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Doğrudan İndir",
                                    tint = AccentGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Video Quality Selector Dialog (1080p, 720p, 480p, 360p, Ses)
    if (showQualitySelector && qualityOptions.isNotEmpty()) {
        VideoQualitySelectorDialog(
            options = qualityOptions,
            selectedThreads = selectedThreads,
            onSelectAndDownload = viewModel::selectAndDownloadQuality,
            onDismiss = viewModel::dismissQualitySelector
        )
    }

    // Analyzing resolutions loading overlay
    if (isAnalyzingResolutions) {
        Dialog(onDismissRequest = { /* non-dismissable */ }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = CyberCyan,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Çözünürlükler Analiz Ediliyor...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "1080p FHD, 720p HD, 480p SD akışları taranıyor",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

private fun normalizeBrowserUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return "https://www.google.com"
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    // Check if it looks like a domain name
    if (trimmed.contains('.') && !trimmed.contains(' ')) {
        return "https://$trimmed"
    }
    // Search query fallback
    return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
}

private const val DESKTOP_CHROME_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val INJECTED_MEDIA_SNIFFER_JS = """
(function() {
    function getSniffer(win) {
        try {
            if (win && win.AndroidMediaSniffer) return win.AndroidMediaSniffer;
            if (window.AndroidMediaSniffer) return window.AndroidMediaSniffer;
            if (win && win.parent && win.parent.AndroidMediaSniffer) return win.parent.AndroidMediaSniffer;
            if (window.top && window.top.AndroidMediaSniffer) return window.top.AndroidMediaSniffer;
        } catch(e) {}
        return null;
    }

    function notifyMedia(src, title, win) {
        if (!src) return;
        src = src.trim();
        if (src.startsWith('http') && !src.startsWith('blob:') && !src.includes('analytics') && !src.includes('pixel') && !src.includes('favicon')) {
            var sniffer = getSniffer(win || window);
            if (sniffer) {
                try {
                    sniffer.onMediaDetected(src, title || document.title || 'Video');
                } catch(e) {}
            }
        }
    }

    var PLAY_SELECTORS = [
        '.vjs-big-play-button',
        '.vjs-play-control',
        '.jw-icon-playback',
        '.jw-display-icon-container',
        '.jw-preview',
        '.plyr__control--overlaid',
        'button[data-plyr="play"]',
        '.ytp-large-play-button',
        '.ytp-play-button',
        '.fp-play',
        '.fp-ui',
        '.play-button',
        '.play-btn',
        '.playBtn',
        '.btn-play',
        '.play_button',
        '.play-icon',
        '.player-play-button',
        '.clappr-play-button',
        '.media-play-button',
        'button[aria-label*="Play" i]',
        'button[aria-label*="Oynat" i]',
        'button[aria-label*="play" i]',
        'button[title*="Play" i]',
        'button[title*="Oynat" i]',
        'a[aria-label*="Play" i]',
        'a[aria-label*="Oynat" i]',
        '[data-action="play"]',
        '[data-testid*="play" i]',
        '[data-control="play"]',
        'button[class*="play" i]',
        'div[class*="play-btn" i]',
        'div[class*="play_btn" i]',
        'div[class*="playButton" i]',
        'div[class*="play-button" i]',
        'span[class*="play-icon" i]',
        'span[class*="play" i]',
        '.video-overlay',
        '.player-overlay',
        '.video-play-layer',
        '.click-to-play'
    ];

    function triggerClickOnElement(el, win) {
        if (!el) return;
        try {
            if (el.offsetParent !== null || el.getClientRects().length > 0) {
                el.click();
                var mouseOpts = { bubbles: true, cancelable: true, view: win || window };
                el.dispatchEvent(new MouseEvent('mousedown', mouseOpts));
                el.dispatchEvent(new MouseEvent('mouseup', mouseOpts));
                el.dispatchEvent(new MouseEvent('click', mouseOpts));
                try {
                    el.dispatchEvent(new PointerEvent('pointerdown', mouseOpts));
                    el.dispatchEvent(new PointerEvent('pointerup', mouseOpts));
                } catch(e) {}
            }
        } catch(e) {}
    }

    function processDocument(doc, win, parentTitle, depth) {
        if (!doc || depth > 4) return;

        // 1. Scan <video> and <audio> tags
        try {
            var mediaEls = doc.querySelectorAll('video, audio');
            mediaEls.forEach(function(m) {
                var title = m.title || m.getAttribute('aria-label') || parentTitle || doc.title || 'Video';
                if (m.currentSrc) notifyMedia(m.currentSrc, title, win);
                else if (m.src) notifyMedia(m.src, title, win);

                var srcs = m.querySelectorAll('source');
                srcs.forEach(function(s) {
                    var u = s.src || s.getAttribute('data-src') || s.getAttribute('data-url');
                    if (u) notifyMedia(u, title, win);
                });

                // Auto-play muted video to force stream loading
                try {
                    m.muted = true;
                    m.preload = 'auto';
                    var p = m.play();
                    if (p !== undefined) {
                        p.then(function() {
                            setTimeout(function() {
                                try { m.pause(); } catch(e) {}
                            }, 350);
                        }).catch(function(e) {});
                    }
                } catch(e) {}

                // If paused, simulate clicks on the video element itself or its container
                if (m.paused) {
                    triggerClickOnElement(m, win);
                    if (m.parentElement) triggerClickOnElement(m.parentElement, win);
                }
            });
        } catch(e) {}

        // 2. Click all play buttons in this document!
        try {
            PLAY_SELECTORS.forEach(function(selector) {
                var buttons = doc.querySelectorAll(selector);
                buttons.forEach(function(btn) {
                    triggerClickOnElement(btn, win);
                });
            });
        } catch(e) {}

        // 3. Listen for play events in this document
        try {
            if (!doc.__playListenerAttached) {
                doc.__playListenerAttached = true;
                doc.addEventListener('play', function(e) {
                    var t = e.target;
                    if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
                        if (t.currentSrc) notifyMedia(t.currentSrc, t.title || parentTitle, win);
                        else if (t.src) notifyMedia(t.src, t.title || parentTitle, win);
                    }
                }, true);
            }
        } catch(e) {}

        // 4. Scan iframes recursively inside this document
        try {
            var iframes = doc.querySelectorAll('iframe, frame');
            iframes.forEach(function(ifr) {
                processIframe(ifr, doc, win, depth + 1);
            });
        } catch(e) {}
    }

    function processIframe(ifr, parentDoc, parentWin, depth) {
        if (!ifr || depth > 4) return;

        // Ensure iframe allows autoplay and fullscreen
        try {
            ifr.setAttribute('allow', 'autoplay *; fullscreen *; picture-in-picture *');
            ifr.setAttribute('allowfullscreen', 'true');
        } catch(e) {}

        var iSrc = ifr.src || ifr.getAttribute('data-src');
        if (iSrc && (iSrc.includes('youtube.com') || iSrc.includes('youtu.be') || iSrc.includes('vimeo.com') || iSrc.includes('embed') || iSrc.includes('player'))) {
            notifyMedia(iSrc, 'Gömülü Video - ' + (ifr.title || (parentDoc && parentDoc.title) || 'İframe'), parentWin);
        }

        // Try direct DOM access into the iframe
        var ifrDoc = null;
        var ifrWin = null;
        try {
            ifrDoc = ifr.contentDocument || (ifr.contentWindow && ifr.contentWindow.document);
            ifrWin = ifr.contentWindow;
        } catch(e) {
            // Cross-origin iframe
        }

        if (ifrDoc) {
            // Accessible iframe: process document, trigger videos, and CLICK PLAY BUTTONS inside!
            processDocument(ifrDoc, ifrWin || parentWin, (ifr.title || 'İframe Video'), depth);
        } else {
            // Cross-origin iframe:
            // 1. Post message play commands across embedded player APIs
            try {
                var cw = ifr.contentWindow;
                if (cw) {
                    cw.postMessage('{"event":"command","func":"playVideo","args":""}', '*');
                    cw.postMessage('{"method":"play"}', '*');
                    cw.postMessage('{"action":"play"}', '*');
                    cw.postMessage('{"type":"play"}', '*');
                    cw.postMessage(JSON.stringify({ event: 'command', func: 'playVideo' }), '*');
                    cw.postMessage('play', '*');
                }
            } catch(e) {}

            // 2. Dispatch synthetic click directly onto the center of the iframe
            try {
                var rect = ifr.getBoundingClientRect();
                if (rect.width > 20 && rect.height > 20) {
                    var cx = rect.left + rect.width / 2;
                    var cy = rect.top + rect.height / 2;
                    var clickOpts = {
                        bubbles: true,
                        cancelable: true,
                        view: parentWin || window,
                        clientX: cx,
                        clientY: cy
                    };
                    ifr.dispatchEvent(new MouseEvent('mousedown', clickOpts));
                    ifr.dispatchEvent(new MouseEvent('mouseup', clickOpts));
                    ifr.dispatchEvent(new MouseEvent('click', clickOpts));
                }
            } catch(e) {}
        }

        // Attach 'load' listener on the iframe to re-process once its internal content finishes loading
        try {
            if (!ifr.__loadListenerAttached) {
                ifr.__loadListenerAttached = true;
                ifr.addEventListener('load', function() {
                    setTimeout(function() {
                        processIframe(ifr, parentDoc, parentWin, depth);
                    }, 250);
                });
            }
        } catch(e) {}
    }

    function scanAll() {
        processDocument(document, window, document.title, 0);

        // Scan OpenGraph video tags
        try {
            var ogV = document.querySelector('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]');
            if (ogV && ogV.content) {
                notifyMedia(ogV.content, document.title, window);
            }
        } catch(e) {}
    }

    window.__scanAllFrames = scanAll;
    window.triggerMediaSniffing = scanAll;

    scanAll();

    [400, 1200, 2500, 5000].forEach(function(delay) {
        setTimeout(scanAll, delay);
    });

    try {
        var obs = new MutationObserver(function() {
            scanAll();
        });
        obs.observe(document.body || document.documentElement, { childList: true, subtree: true });
    } catch(e) {}

    document.addEventListener('click', function() {
        setTimeout(scanAll, 300);
    }, true);
})();
""".trimIndent()
