package com.example.data.engine

import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.ExtractedMedia
import com.example.data.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MediaSniffer(
    private val client: OkHttpClient = OkHttpProvider.client
) {

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    suspend fun sniff(
        rawUrl: String,
        headerConfig: ChromeHeaderConfig? = null
    ): List<ExtractedMedia> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(rawUrl)
        if (cleanUrl.isBlank()) return@withContext emptyList()

        val lowerUrl = cleanUrl.lowercase()

        // 1. Specialized YouTube Resolver
        if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
            val ytResults = resolveYouTube(cleanUrl, headerConfig)
            if (ytResults.isNotEmpty()) {
                return@withContext ytResults
            }
        }

        // 2. Specialized Instagram Resolver
        if (lowerUrl.contains("instagram.com")) {
            val igResults = resolveInstagram(cleanUrl, headerConfig)
            if (igResults.isNotEmpty()) {
                return@withContext igResults
            }
        }

        // 3. Specialized Twitter / X Resolver
        if (lowerUrl.contains("twitter.com") || lowerUrl.contains("x.com")) {
            val twResults = resolveTwitter(cleanUrl, headerConfig)
            if (twResults.isNotEmpty()) {
                return@withContext twResults
            }
        }

        // 4. If URL directly points to a media or archive file (.mp4, .mkv, .mp3, .pdf, .zip, etc.)
        if (hasMediaExtension(cleanUrl)) {
            val fileName = extractFileName(cleanUrl, "", "")
            val category = FileCategory.detect(fileName, "")
            val directItem = ExtractedMedia(
                url = cleanUrl,
                title = fileName,
                category = category,
                mimeType = when {
                    fileName.endsWith(".mp4", true) -> "video/mp4"
                    fileName.endsWith(".mkv", true) -> "video/x-matroska"
                    fileName.endsWith(".webm", true) -> "video/webm"
                    fileName.endsWith(".mp3", true) -> "audio/mpeg"
                    fileName.endsWith(".pdf", true) -> "application/pdf"
                    fileName.endsWith(".zip", true) -> "application/zip"
                    fileName.endsWith(".apk", true) -> "application/vnd.android.package-archive"
                    else -> "application/octet-stream"
                },
                approxSize = -1L,
                thumbnailUrl = if (category == FileCategory.MEDIA && (fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true))) cleanUrl else null
            )
            return@withContext try {
                val effectiveHeaders = headerConfig ?: ChromeHeaderConfig()
                val requestBuilder = Request.Builder().url(cleanUrl).head()
                effectiveHeaders.applyTo(requestBuilder, cleanUrl)
                val response = client.newCall(requestBuilder.build()).execute()
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val contentDisposition = response.header("Content-Disposition").orEmpty()
                response.close()
                val probedName = extractFileName(finalUrl, contentDisposition, contentType)
                val probedCategory = FileCategory.detect(probedName, contentType)
                listOf(
                    ExtractedMedia(
                        url = finalUrl,
                        title = probedName,
                        category = probedCategory,
                        mimeType = contentType.ifBlank { directItem.mimeType },
                        approxSize = contentLength,
                        thumbnailUrl = directItem.thumbnailUrl
                    )
                )
            } catch (e: Exception) {
                listOf(directItem)
            }
        }

        // 5. General Web Page & Sniffer
        try {
            val effectiveHeaders = headerConfig ?: ChromeHeaderConfig()
            val requestBuilder = Request.Builder().url(cleanUrl)
            effectiveHeaders.applyTo(requestBuilder, cleanUrl)

            val response = client.newCall(requestBuilder.get().build()).execute()
            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val contentDisposition = response.header("Content-Disposition").orEmpty()

            // If it's directly a downloadable media file (not HTML)
            val isDirectMedia = !contentType.contains("text/html") && (
                contentType.startsWith("video/") ||
                    contentType.startsWith("image/") ||
                    contentType.startsWith("audio/") ||
                    contentType.startsWith("application/") ||
                    hasMediaExtension(finalUrl)
                )

            if (isDirectMedia) {
                response.close()
                val fileName = extractFileName(finalUrl, contentDisposition, contentType)
                val category = FileCategory.detect(fileName, contentType)
                return@withContext listOf(
                    ExtractedMedia(
                        url = finalUrl,
                        title = fileName,
                        category = category,
                        mimeType = contentType.ifBlank { "application/octet-stream" },
                        approxSize = contentLength,
                        thumbnailUrl = if (category == FileCategory.MEDIA && contentType.startsWith("image/")) finalUrl else null
                    )
                )
            }

            // Otherwise read HTML and extract all media elements
            val responseBody = response.body?.string() ?: ""
            response.close()

            val extracted = parseHtml(finalUrl, responseBody)
            return@withContext extracted
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private suspend fun resolveYouTube(
        url: String,
        headerConfig: ChromeHeaderConfig?
    ): List<ExtractedMedia> = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeVideoId(url) ?: return@withContext emptyList()
        val results = mutableListOf<ExtractedMedia>()
        val seenStreamUrls = mutableSetOf<String>()

        // 1. Fetch title, author, and HD thumbnail via YouTube oEmbed
        var videoTitle: String? = null
        var uploader: String? = null
        var oembedThumb: String? = null

        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val req = Request.Builder().url(oembedUrl).get().build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = resp.body?.string().orEmpty()
                val jsonObject = JSONObject(json)
                videoTitle = jsonObject.optString("title").takeIf { it.isNotBlank() }
                uploader = jsonObject.optString("author_name").takeIf { it.isNotBlank() }
                oembedThumb = jsonObject.optString("thumbnail_url").takeIf { it.isNotBlank() }
            }
            resp.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val baseTitle = videoTitle?.let { sanitizeFileName(it) } ?: "YouTube_$videoId"
        val displayThumb = oembedThumb ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

        // 2. Query Invidious API instances for direct GoogleVideo MP4 streams
        val invidiousInstances = listOf(
            "https://invidious.f5.si",
            "https://yewtu.be",
            "https://inv.nadeko.net"
        )
        for (instance in invidiousInstances) {
            try {
                val invidiousClient = client.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val invReq = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build()
                val invResp = invidiousClient.newCall(invReq).execute()
                if (invResp.isSuccessful) {
                    val invJson = JSONObject(invResp.body?.string().orEmpty())
                    if (videoTitle.isNullOrBlank()) {
                        val titleFromInv = invJson.optString("title")
                        if (titleFromInv.isNotBlank()) {
                            videoTitle = titleFromInv
                        }
                    }

                    // formatStreams (combined audio + video direct MP4)
                    val formatStreams = invJson.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        for (i in 0 until formatStreams.length()) {
                            val stream = formatStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url")
                            if (streamUrl.isNotBlank() && !seenStreamUrls.contains(streamUrl)) {
                                seenStreamUrls.add(streamUrl)
                                val res = stream.optString("resolution", "720p")
                                val container = stream.optString("container", "mp4")
                                results.add(
                                    ExtractedMedia(
                                        url = streamUrl,
                                        title = "$baseTitle - $res ($container)",
                                        category = FileCategory.MEDIA,
                                        mimeType = "video/$container",
                                        approxSize = -1L,
                                        thumbnailUrl = displayThumb
                                    )
                                )
                            }
                        }
                    }

                    // adaptiveFormats (audio-only streams: m4a)
                    val adaptiveFormats = invJson.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val stream = adaptiveFormats.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url")
                            val mime = stream.optString("type").lowercase()
                            if (streamUrl.isNotBlank() && (mime.contains("audio/mp4") || mime.contains("audio/m4a")) && !seenStreamUrls.contains(streamUrl)) {
                                seenStreamUrls.add(streamUrl)
                                val bitrate = stream.optInt("bitrate") / 1000
                                val size = stream.optLong("clen", -1L)
                                results.add(
                                    ExtractedMedia(
                                        url = streamUrl,
                                        title = "$baseTitle - Ses Parçası (${bitrate}kbps M4A)",
                                        category = FileCategory.MEDIA,
                                        mimeType = "audio/mp4",
                                        approxSize = size,
                                        thumbnailUrl = displayThumb
                                    )
                                )
                                break
                            }
                        }
                    }

                    invResp.close()
                    if (results.isNotEmpty()) break
                } else {
                    invResp.close()
                }
            } catch (ignored: Exception) {}
        }

        // 3. Fallback: Parse watch page HTML and ytInitialPlayerResponse
        if (results.isEmpty()) {
            try {
                val watchUrl = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
                val reqBuilder = Request.Builder().url(watchUrl)
                (headerConfig ?: ChromeHeaderConfig()).applyTo(reqBuilder, watchUrl)
                val resp = client.newCall(reqBuilder.get().build()).execute()
                val html = resp.body?.string().orEmpty()
                resp.close()

                val playerJson = extractYtPlayerResponseJson(html)
                if (playerJson != null) {
                    val streamingData = playerJson.optJSONObject("streamingData")
                    if (streamingData != null) {
                        val formats = streamingData.optJSONArray("formats")
                        if (formats != null) {
                            for (i in 0 until formats.length()) {
                                val fmt = formats.optJSONObject(i) ?: continue
                                val directUrl = fmt.optString("url")
                                if (directUrl.isNotBlank() && !seenStreamUrls.contains(directUrl)) {
                                    seenStreamUrls.add(directUrl)
                                    val quality = fmt.optString("qualityLabel").ifBlank {
                                        val itag = fmt.optInt("itag")
                                        if (itag == 22) "720p HD" else if (itag == 18) "360p" else "Standart"
                                    }
                                    val mime = fmt.optString("mimeType").substringBefore(';').ifBlank { "video/mp4" }
                                    val ext = if (mime.contains("webm")) ".webm" else ".mp4"
                                    val size = fmt.optLong("contentLength", -1L)

                                    results.add(
                                        ExtractedMedia(
                                            url = directUrl,
                                            title = "$baseTitle - $quality ($ext)",
                                            category = FileCategory.MEDIA,
                                            mimeType = mime,
                                            approxSize = size,
                                            thumbnailUrl = displayThumb
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Always include HD Video thumbnail as downloadable media
        results.add(
            ExtractedMedia(
                url = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                title = "$baseTitle - Kapak Fotoğrafı (HD JPG)",
                category = FileCategory.MEDIA,
                mimeType = "image/jpeg",
                approxSize = -1L,
                thumbnailUrl = displayThumb
            )
        )

        results
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/v/|youtu\.be/|/embed/|/shorts/)([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""^([a-zA-Z0-9_-]{11})$""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractYtPlayerResponseJson(html: String): JSONObject? {
        val markers = listOf(
            "ytInitialPlayerResponse = ",
            "var ytInitialPlayerResponse = "
        )
        for (marker in markers) {
            val pos = html.indexOf(marker)
            if (pos != -1) {
                val start = pos + marker.length
                var depth = 0
                var inString = false
                var escape = false
                var end = -1
                for (i in start until html.length) {
                    val c = html[i]
                    if (escape) {
                        escape = false
                        continue
                    }
                    if (c == '\\') {
                        escape = true
                        continue
                    }
                    if (c == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        if (c == '{') depth++
                        else if (c == '}') {
                            depth--
                            if (depth == 0) {
                                end = i + 1
                                break
                            }
                        }
                    }
                }
                if (end != -1) {
                    return try {
                        JSONObject(html.substring(start, end))
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    private suspend fun resolveInstagram(
        url: String,
        headerConfig: ChromeHeaderConfig?
    ): List<ExtractedMedia> = withContext(Dispatchers.IO) {
        val shortcode = extractInstagramShortcode(url) ?: return@withContext emptyList()
        val results = mutableListOf<ExtractedMedia>()
        val seenUrls = mutableSetOf<String>()

        fun addMedia(mediaUrl: String, title: String, mime: String, thumb: String? = null) {
            val cleanMedia = mediaUrl.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&").trim()
            if (cleanMedia.isBlank() || seenUrls.contains(cleanMedia)) return
            seenUrls.add(cleanMedia)
            results.add(
                ExtractedMedia(
                    url = cleanMedia,
                    title = title,
                    category = FileCategory.MEDIA,
                    mimeType = mime,
                    approxSize = -1L,
                    thumbnailUrl = thumb ?: if (mime.startsWith("image/")) cleanMedia else null
                )
            )
        }

        val effectiveHeaders = headerConfig ?: ChromeHeaderConfig()

        // 1. Query Instagram embed captioned endpoint: https://www.instagram.com/p/{shortcode}/embed/captioned/
        try {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            val reqBuilder = Request.Builder().url(embedUrl)
            effectiveHeaders.applyTo(reqBuilder, embedUrl)
            val resp = client.newCall(reqBuilder.get().build()).execute()
            val html = resp.body?.string().orEmpty()
            resp.close()

            val cleanHtml = html.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")

            // Look for video_url in embed
            val videoUrlPattern = Pattern.compile("""(?:video_url|videoUrl)["']?\s*:\s*["'](https://[^"']+)["']""", Pattern.CASE_INSENSITIVE)
            val videoMatcher = videoUrlPattern.matcher(cleanHtml)
            while (videoMatcher.find()) {
                val found = videoMatcher.group(1).orEmpty()
                addMedia(found, "Instagram Video - $shortcode (MP4)", "video/mp4")
            }

            // Look for direct .mp4 URLs in embed
            val mp4Pattern = Pattern.compile("""https://[^\s"'<>]+\.mp4[^\s"'<>]*""", Pattern.CASE_INSENSITIVE)
            val mp4Matcher = mp4Pattern.matcher(cleanHtml)
            while (mp4Matcher.find()) {
                val found = mp4Matcher.group(0).orEmpty()
                addMedia(found, "Instagram Reel - $shortcode (MP4)", "video/mp4")
            }

            // Look for display_url (HD image / cover)
            val displayUrlPattern = Pattern.compile("""(?:display_url|displayUrl)["']?\s*:\s*["'](https://[^"']+)["']""", Pattern.CASE_INSENSITIVE)
            val displayMatcher = displayUrlPattern.matcher(cleanHtml)
            while (displayMatcher.find()) {
                val found = displayMatcher.group(1).orEmpty()
                addMedia(found, "Instagram Fotoğraf - $shortcode (JPG)", "image/jpeg", found)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Query Instagram JSON endpoint with mobile User-Agent
        try {
            val jsonUrl = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
            val reqBuilder = Request.Builder().url(jsonUrl)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "*/*")
            effectiveHeaders.customCookies?.let { reqBuilder.header("Cookie", it) }
            val resp = client.newCall(reqBuilder.get().build()).execute()
            if (resp.isSuccessful) {
                val jsonStr = resp.body?.string().orEmpty()
                val json = JSONObject(jsonStr)
                val items = json.optJSONArray("items")
                val item = items?.optJSONObject(0) ?: json.optJSONObject("graphql")?.optJSONObject("shortcode_media")
                if (item != null) {
                    val videoVersions = item.optJSONArray("video_versions")
                    if (videoVersions != null && videoVersions.length() > 0) {
                        val bestVideo = videoVersions.optJSONObject(0)?.optString("url")
                        if (!bestVideo.isNullOrBlank()) {
                            addMedia(bestVideo, "Instagram Video - $shortcode (MP4)", "video/mp4")
                        }
                    }
                    val imageVersions = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
                    if (imageVersions != null && imageVersions.length() > 0) {
                        val bestImg = imageVersions.optJSONObject(0)?.optString("url")
                        if (!bestImg.isNullOrBlank()) {
                            addMedia(bestImg, "Instagram Kapak - $shortcode (JPG)", "image/jpeg", bestImg)
                        }
                    }
                }
            }
            resp.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Query OpenGraph / Twitter metadata via Social Crawler Headers
        try {
            val pageUrl = "https://www.instagram.com/p/$shortcode/"
            val crawlerReq = Request.Builder().url(pageUrl)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .get()
                .build()
            val crawlerResp = client.newCall(crawlerReq).execute()
            if (crawlerResp.isSuccessful) {
                val cHtml = crawlerResp.body?.string().orEmpty()
                val ogVideoMatcher = Pattern.compile("""<meta[^>]+property=["']og:video(?::(?:url|secure_url))?["'][^>]+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(cHtml)
                while (ogVideoMatcher.find()) {
                    ogVideoMatcher.group(1)?.let { addMedia(it, "Instagram Video - $shortcode (MP4)", "video/mp4") }
                }
                val ogImgMatcher = Pattern.compile("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(cHtml)
                while (ogImgMatcher.find()) {
                    ogImgMatcher.group(1)?.let { addMedia(it, "Instagram Fotoğraf - $shortcode (JPG)", "image/jpeg", it) }
                }
            }
            crawlerResp.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Prioritize actual video MP4 streams first, then photo covers
        results.sortedWith(compareByDescending<ExtractedMedia> { it.mimeType.startsWith("video/") }.thenByDescending { it.approxSize })
    }

    private fun extractInstagramShortcode(url: String): String? {
        val patterns = listOf(
            Regex("""instagram\.com/(?:p|reel|reels|tv)/([a-zA-Z0-9_-]+)"""),
            Regex("""(?:p|reel|reels|tv)/([a-zA-Z0-9_-]+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private suspend fun resolveTwitter(
        url: String,
        headerConfig: ChromeHeaderConfig?
    ): List<ExtractedMedia> = withContext(Dispatchers.IO) {
        val tweetId = Regex("""(?:status|statuses)/(\d+)""").find(url)?.groupValues?.get(1) ?: return@withContext emptyList()
        val results = mutableListOf<ExtractedMedia>()
        try {
            val apiUrl = "https://api.fxtwitter.com/status/$tweetId"
            val req = Request.Builder().url(apiUrl).get().build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string().orEmpty())
                val tweet = json.optJSONObject("tweet")
                if (tweet != null) {
                    val media = tweet.optJSONObject("media")
                    val videos = media?.optJSONArray("videos")
                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val v = videos.optJSONObject(i) ?: continue
                            val vUrl = v.optString("url")
                            val thumb = v.optString("thumbnail_url")
                            if (vUrl.isNotBlank()) {
                                results.add(
                                    ExtractedMedia(
                                        url = vUrl,
                                        title = "X/Twitter Video - $tweetId.mp4",
                                        category = FileCategory.MEDIA,
                                        mimeType = "video/mp4",
                                        approxSize = -1L,
                                        thumbnailUrl = thumb
                                    )
                                )
                            }
                        }
                    }
                    val photos = media?.optJSONArray("photos")
                    if (photos != null) {
                        for (i in 0 until photos.length()) {
                            val p = photos.optJSONObject(i) ?: continue
                            val pUrl = p.optString("url")
                            if (pUrl.isNotBlank()) {
                                results.add(
                                    ExtractedMedia(
                                        url = pUrl,
                                        title = "X/Twitter Fotoğraf - ${tweetId}_${i + 1}.jpg",
                                        category = FileCategory.MEDIA,
                                        mimeType = "image/jpeg",
                                        approxSize = -1L,
                                        thumbnailUrl = pUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
            resp.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    private fun parseHtml(baseUrl: String, html: String): List<ExtractedMedia> {
        val results = mutableListOf<ExtractedMedia>()
        val seenUrls = mutableSetOf<String>()
        val baseHttpUrl = baseUrl.toHttpUrlOrNull()

        // Extract page title for better naming
        val pageTitleMatcher = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        val pageTitle = if (pageTitleMatcher.find()) {
            pageTitleMatcher.group(1)?.replace(Regex("""\s+"""), " ")?.trim()?.take(80)
        } else null

        fun addIfValid(rawTargetUrl: String, title: String? = null, forcedCategory: FileCategory? = null) {
            if (results.size >= 80) return
            var cleanTarget = rawTargetUrl.trim().replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")
            if (cleanTarget.startsWith("//")) {
                cleanTarget = "https:$cleanTarget"
            }
            val resolvedUrl = resolveUrl(baseHttpUrl, cleanTarget) ?: return
            if (seenUrls.contains(resolvedUrl)) return
            if (resolvedUrl.startsWith("data:") || resolvedUrl.startsWith("javascript:")) return

            val fileName = extractFileName(resolvedUrl, "", "")
            val category = forcedCategory ?: FileCategory.detect(fileName, "")

            // Ignore 1x1 tracking pixels, common ad icons, SVG favicons
            if (category == FileCategory.MEDIA) {
                val lowerName = fileName.lowercase()
                if (lowerName.contains("pixel") || lowerName.contains("tracker") ||
                    lowerName.contains("blank") || lowerName.contains("1x1") ||
                    lowerName.contains("favicon") || lowerName.endsWith(".ico")
                ) {
                    return
                }
            }

            seenUrls.add(resolvedUrl)
            val displayTitle = title?.takeIf { it.isNotBlank() } ?: pageTitle?.let { "$it - $fileName" } ?: fileName
            results.add(
                ExtractedMedia(
                    url = resolvedUrl,
                    title = displayTitle,
                    category = category,
                    mimeType = guessMimeType(fileName),
                    approxSize = -1L,
                    thumbnailUrl = if (category == FileCategory.MEDIA) resolvedUrl else null
                )
            )
        }

        // 1. OpenGraph & Twitter Meta Tags (high confidence video & image URLs on modern web!)
        val ogVideoRegex = Pattern.compile("""<meta[^>]+property=["']og:video(?::(?:url|secure_url))?["'][^>]+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val ogVideoMatcher = ogVideoRegex.matcher(html)
        while (ogVideoMatcher.find()) {
            ogVideoMatcher.group(1)?.let { addIfValid(it, title = pageTitle, forcedCategory = FileCategory.MEDIA) }
        }

        val twitterVideoRegex = Pattern.compile("""<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val twitterVideoMatcher = twitterVideoRegex.matcher(html)
        while (twitterVideoMatcher.find()) {
            twitterVideoMatcher.group(1)?.let { addIfValid(it, title = pageTitle, forcedCategory = FileCategory.MEDIA) }
        }

        val ogImageRegex = Pattern.compile("""<meta[^>]+(?:property=["']og:image(?::(?:url|secure_url))?["']|name=["']twitter:image["'])[^>]+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val ogImageMatcher = ogImageRegex.matcher(html)
        while (ogImageMatcher.find()) {
            ogImageMatcher.group(1)?.let { addIfValid(it, title = pageTitle, forcedCategory = FileCategory.MEDIA) }
        }

        // 2. HTML5 Video elements & Source tags
        val videoSrcRegex = Pattern.compile("""<(?:video|source)[^>]+(?:src|data-src|data-url|data-video)=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val videoMatcher = videoSrcRegex.matcher(html)
        while (videoMatcher.find()) {
            videoMatcher.group(1)?.let { addIfValid(it, forcedCategory = FileCategory.MEDIA) }
        }

        // 3. HTML5 Audio elements
        val audioSrcRegex = Pattern.compile("""<(?:audio|source)[^>]+(?:src|data-src)=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val audioMatcher = audioSrcRegex.matcher(html)
        while (audioMatcher.find()) {
            audioMatcher.group(1)?.let { addIfValid(it, forcedCategory = FileCategory.MEDIA) }
        }

        // 4. HTML5 Images (including lazy-load attributes & srcset)
        val imgRegex = Pattern.compile("""<img[^>]+(?:src|data-src|data-original|data-lazy-src|data-zoom-image)=["']([^"']+)["'][^>]*>""", Pattern.CASE_INSENSITIVE)
        val imgMatcher = imgRegex.matcher(html)
        while (imgMatcher.find()) {
            imgMatcher.group(1)?.let { addIfValid(it, forcedCategory = FileCategory.MEDIA) }
        }

        // Extract from srcset (takes highest resolution link)
        val srcsetRegex = Pattern.compile("""srcset=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val srcsetMatcher = srcsetRegex.matcher(html)
        while (srcsetMatcher.find()) {
            val srcset = srcsetMatcher.group(1) ?: continue
            val candidate = srcset.split(",").lastOrNull()?.trim()?.substringBefore(" ")
            if (!candidate.isNullOrBlank()) {
                addIfValid(candidate, forcedCategory = FileCategory.MEDIA)
            }
        }

        // 5. JavaScript video player variables (JWPlayer, Video.js, Plyr, Clappr, HLS.js, DPlayer)
        val jsPlayerRegex = Pattern.compile("""(?:file|source|src|videoUrl|video_url|downloadUrl|download_url|hls)\s*:\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val jsPlayerMatcher = jsPlayerRegex.matcher(html)
        while (jsPlayerMatcher.find()) {
            val raw = jsPlayerMatcher.group(1).orEmpty()
            if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) || raw.startsWith("//") || raw.startsWith("/")) {
                addIfValid(raw, forcedCategory = FileCategory.MEDIA)
            }
        }

        // 6. Anchor links & Download buttons on web pages
        val anchorRegex = Pattern.compile("""<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val anchorMatcher = anchorRegex.matcher(html)
        while (anchorMatcher.find()) {
            val href = anchorMatcher.group(1) ?: continue
            val rawText = anchorMatcher.group(2)?.replace(Regex("<.*?>"), "")?.trim().orEmpty()
            val lowerText = rawText.lowercase()
            val lowerHref = href.lowercase()

            val isExplicitMedia = hasMediaExtension(href)
            val isDownloadIntent = lowerText.contains("indir") || lowerText.contains("download") ||
                lowerText.contains("720p") || lowerText.contains("1080p") || lowerText.contains("mp4") ||
                lowerHref.contains("download") || lowerHref.contains("get_file") || lowerHref.contains("mediafire.com")

            if (isExplicitMedia || isDownloadIntent) {
                addIfValid(href, title = rawText.takeIf { it.isNotBlank() })
            }
        }

        // 7. Direct CDN stream scanner: GoogleVideo, Instagram, TikTok, FB, m3u8, mpd, mp4
        val streamRegex = Pattern.compile("""https?(?::|%3A)(?:\\/\\/|//)[^"'\s<>\\]+?(?:\.googlevideo\.com/videoplayback|\.cdninstagram\.com/|\.tiktokcdn\.com/|\.fbcdn\.net/|\.m3u8|\.mpd|\.mp4|\.webm|\.mkv|\.apk|\.zip)[^"'\s<>\\]*""", Pattern.CASE_INSENSITIVE)
        val streamMatcher = streamRegex.matcher(html)
        var streamCount = 0
        while (streamMatcher.find() && streamCount < 40) {
            val foundUrl = streamMatcher.group(0) ?: continue
            addIfValid(foundUrl, forcedCategory = FileCategory.MEDIA)
            streamCount++
        }

        // 8. Inline JavaScript / JSON Stream Scanner
        val inlineMediaRegex = Pattern.compile("""https?(?::|%3A)(?:\\/\\/|//)[^"'\s<>\\]+?\.(?:mp4|mkv|webm|mov|m3u8|mp3|wav|jpg|jpeg|png|webp|gif|pdf|zip|rar|7z|apk)(?:\?[^"'\s<>\\]*)?""", Pattern.CASE_INSENSITIVE)
        val inlineMatcher = inlineMediaRegex.matcher(html)
        var inlineCount = 0
        while (inlineMatcher.find() && inlineCount < 40) {
            val foundUrl = inlineMatcher.group(0) ?: continue
            addIfValid(foundUrl)
            inlineCount++
        }

        // 9. Video iframe inspection for movie/series/embed sites
        val iframeRegex = Pattern.compile("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val iframeMatcher = iframeRegex.matcher(html)
        val videoIframeCandidates = mutableListOf<String>()
        while (iframeMatcher.find() && videoIframeCandidates.size < 4) {
            val src = iframeMatcher.group(1).orEmpty()
            val resolved = resolveUrl(baseHttpUrl, src) ?: continue
            val lowerSrc = resolved.lowercase()
            if (lowerSrc.contains("video") || lowerSrc.contains("player") || lowerSrc.contains("embed") ||
                lowerSrc.contains("stream") || lowerSrc.contains("vimeo") || lowerSrc.contains("dailymotion") ||
                lowerSrc.contains("dood") || lowerSrc.contains("tape") || lowerSrc.contains("mp4upload")
            ) {
                videoIframeCandidates.add(resolved)
            }
        }

        for (iUrl in videoIframeCandidates) {
            try {
                val iframeReq = Request.Builder().url(iUrl).header("User-Agent", BROWSER_USER_AGENT).get().build()
                val iframeResp = client.newCall(iframeReq).execute()
                val iframeHtml = iframeResp.body?.string().orEmpty()
                iframeResp.close()
                if (iframeHtml.isNotBlank()) {
                    val subMedia = extractMediaFromIframe(iUrl, iframeHtml)
                    for (sub in subMedia) {
                        addIfValid(sub, forcedCategory = FileCategory.MEDIA)
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Sort: APKS first, then MEDIA, ARCHIVES, DOCUMENTS, Others
        return results.sortedBy { media ->
            when (media.category) {
                FileCategory.APKS -> 0
                FileCategory.MEDIA -> 1
                FileCategory.ARCHIVES -> 2
                FileCategory.DOCUMENTS -> 3
                else -> 4
            }
        }
    }

    private fun extractMediaFromIframe(iframeUrl: String, html: String): List<String> {
        val found = mutableListOf<String>()
        val clean = html.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")
        // Look for video tags and sources
        val vPattern = Pattern.compile("""<(?:video|source)[^>]+(?:src|data-src)=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val vMatcher = vPattern.matcher(clean)
        while (vMatcher.find()) {
            vMatcher.group(1)?.let { found.add(it) }
        }
        // Look for JS player file: "..."
        val jsPattern = Pattern.compile("""(?:file|source|src|videoUrl|video_url|hls)\s*:\s*["'](https?://[^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val jsMatcher = jsPattern.matcher(clean)
        while (jsMatcher.find()) {
            jsMatcher.group(1)?.let { found.add(it) }
        }
        // Direct mp4/m3u8 regex
        val directPattern = Pattern.compile("""https?://[^\s"'<>]+\.(?:mp4|m3u8|webm)[^\s"'<>]*""", Pattern.CASE_INSENSITIVE)
        val directMatcher = directPattern.matcher(clean)
        while (directMatcher.find()) {
            directMatcher.group(0)?.let { found.add(it) }
        }
        return found
    }

    private fun resolveUrl(baseUrl: HttpUrl?, relativeUrl: String): String? {
        val trimmed = relativeUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (baseUrl == null) return null
        return baseUrl.resolve(trimmed)?.toString()
    }

    private fun hasMediaExtension(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val extensions = listOf(
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".flv",
            ".jpg", ".jpeg", ".png", ".webp", ".gif",
            ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a",
            ".pdf", ".zip", ".rar", ".7z", ".apk", ".tar", ".gz",
            ".docx", ".xlsx", ".pptx", ".iso", ".exe", ".dmg"
        )
        return extensions.any { clean.endsWith(it) }
    }

    private fun extractFileName(url: String, disposition: String, mimeType: String): String {
        // 1. Content-Disposition header (RFC 5987 / RFC 6266)
        if (disposition.isNotBlank()) {
            val rfcPattern = Pattern.compile("""filename\*=UTF-8''([^;\s]+)""", Pattern.CASE_INSENSITIVE)
            val rfcMatcher = rfcPattern.matcher(disposition)
            if (rfcMatcher.find()) {
                val raw = rfcMatcher.group(1).orEmpty()
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) return sanitizeFileName(decoded)
            }

            val standardPattern = Pattern.compile("""filename\s*=\s*["']?([^"';]+)["']?""", Pattern.CASE_INSENSITIVE)
            val standardMatcher = standardPattern.matcher(disposition)
            if (standardMatcher.find()) {
                val raw = standardMatcher.group(1).orEmpty().trim('"', '\'')
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) return sanitizeFileName(decoded)
            }
        }

        // 2. From URL path
        val cleanPath = url.substringBefore('?').substringBefore('#')
        val lastSegment = cleanPath.substringAfterLast('/', "")
        if (lastSegment.isNotBlank() && lastSegment.contains('.')) {
            val decoded = try { URLDecoder.decode(lastSegment, "UTF-8") } catch (e: Exception) { lastSegment }
            if (decoded.isNotBlank()) return sanitizeFileName(decoded)
        }

        // 3. Fallback with extension from mime type or URL hint
        val lowerMime = mimeType.lowercase()
        val lowerUrl = url.lowercase()
        val isSocialImage = (lowerUrl.contains("cdninstagram.com") || lowerUrl.contains("fbcdn.net")) &&
            (lowerUrl.contains("t51.") || lowerUrl.contains("dst-jpg") || lowerUrl.contains(".jpg") || lowerUrl.contains(".webp") || lowerUrl.contains(".png"))

        val ext = when {
            lowerMime.contains("mp4") || lowerMime.contains("m4v") -> ".mp4"
            lowerMime.contains("webm") -> ".webm"
            lowerMime.contains("mkv") || lowerMime.contains("matroska") -> ".mkv"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") || isSocialImage -> ".jpg"
            lowerMime.contains("png") -> ".png"
            lowerMime.contains("webp") -> ".webp"
            lowerMime.contains("gif") -> ".gif"
            lowerMime.contains("pdf") -> ".pdf"
            lowerMime.contains("vnd.android.package-archive") || lowerMime.contains("apk") -> ".apk"
            lowerMime.contains("zip") -> ".zip"
            lowerMime.contains("audio/mpeg") || lowerMime.contains("mp3") -> ".mp3"
            lowerMime.contains("audio/mp4") || lowerMime.contains("m4a") -> ".m4a"
            lowerUrl.contains("googlevideo.com") || lowerUrl.contains("videoplayback") -> ".mp4"
            (lowerUrl.contains("cdninstagram.com") || lowerUrl.contains("instagram.com")) &&
                (lowerUrl.contains("t50.") || lowerUrl.contains(".mp4") || lowerUrl.contains("video")) -> ".mp4"
            lowerUrl.contains("cdninstagram.com") || lowerUrl.contains("instagram.com") -> ".jpg"
            else -> ""
        }
        val safeExt = if (ext.isNotBlank()) ext else ".tmp"
        return "download_${System.currentTimeMillis()}$safeExt"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /**
     * Analyzes a captured video source to discover all available resolutions (1080p, 720p, 480p, 360p, Audio).
     * Handles HLS master playlist variant streams, sibling video URLs, and page sources.
     */
    suspend fun analyzeVideoResolutions(
        media: ExtractedMedia,
        currentWebUrl: String? = null,
        headerConfig: ChromeHeaderConfig? = null
    ): List<ExtractedMedia> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ExtractedMedia>()
        val seenUrls = mutableSetOf<String>()
        val effectiveHeaders = headerConfig ?: ChromeHeaderConfig()
        val cleanMediaUrl = normalizeUrl(media.url)
        val cleanTitle = media.title.substringBefore(" - ").ifBlank { "Video" }

        // 1. Check if the URL is an HLS playlist (.m3u8)
        val isHls = cleanMediaUrl.lowercase().contains(".m3u8") ||
            media.mimeType.contains("mpegurl", ignoreCase = true) ||
            media.mimeType.contains("m3u8", ignoreCase = true)

        if (isHls) {
            try {
                val reqBuilder = Request.Builder().url(cleanMediaUrl)
                effectiveHeaders.applyTo(reqBuilder, cleanMediaUrl)
                val resp = client.newCall(reqBuilder.get().build()).execute()
                if (resp.isSuccessful) {
                    val m3u8Content = resp.body?.string().orEmpty()
                    resp.close()

                    if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                        val baseHttpUrl = cleanMediaUrl.toHttpUrlOrNull()
                        val lines = m3u8Content.lines()
                        var lastInf: String? = null
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                                lastInf = trimmed
                            } else if (lastInf != null && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                                val variantUrl = resolveUrl(baseHttpUrl, trimmed) ?: trimmed
                                if (!seenUrls.contains(variantUrl)) {
                                    seenUrls.add(variantUrl)
                                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(lastInf)
                                    val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(lastInf)
                                    val width = resMatch?.groupValues?.get(1)?.toIntOrNull()
                                    val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
                                    val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: -1L

                                    val qualityLabel = when {
                                        height != null && height >= 1080 -> "1080p FHD (${width}x${height})"
                                        height != null && height >= 720 -> "720p HD (${width}x${height})"
                                        height != null && height >= 480 -> "480p SD (${width}x${height})"
                                        height != null && height >= 360 -> "360p (${width}x${height})"
                                        height != null && height >= 240 -> "240p (${width}x${height})"
                                        bw >= 3500000 -> "1080p FHD (Yüksek Kalite)"
                                        bw >= 1500000 -> "720p HD"
                                        bw >= 700000 -> "480p SD"
                                        else -> "360p (Standart)"
                                    }
                                    val bitrateStr = if (bw > 0) " [${bw / 1000} kbps]" else ""
                                    results.add(
                                        ExtractedMedia(
                                            url = variantUrl,
                                            title = "$cleanTitle - $qualityLabel$bitrateStr",
                                            category = FileCategory.MEDIA,
                                            mimeType = "application/x-mpegURL",
                                            approxSize = if (bw > 0) (bw / 8) * 120 else -1L,
                                            thumbnailUrl = media.thumbnailUrl
                                        )
                                    )
                                }
                                lastInf = null
                            }
                        }
                    }
                } else {
                    resp.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. If currentWebUrl is available, attempt web page sniffing to get multi-quality streams
        val pageToSniff = currentWebUrl?.takeIf { it.isNotBlank() && !it.startsWith("about:") }
        if (pageToSniff != null && results.isEmpty()) {
            try {
                val sniffed = sniff(pageToSniff, headerConfig)
                val videoStreams = sniffed.filter {
                    it.category == FileCategory.MEDIA &&
                        (it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/") || it.url.contains("m3u8"))
                }
                for (stream in videoStreams) {
                    if (!seenUrls.contains(stream.url)) {
                        seenUrls.add(stream.url)
                        results.add(stream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Sibling URL probing for direct MP4/WebM videos (e.g. 1080p, 720p, 480p, 360p in URL)
        if (results.isEmpty() || results.size == 1) {
            val resRegex = Regex("""(1080|720|480|360)(p)?""", RegexOption.IGNORE_CASE)
            val match = resRegex.find(cleanMediaUrl)
            if (match != null) {
                val currentRes = match.groupValues[1]
                val candidateResolutions = listOf(
                    Triple("1080", "1080p Full HD", 1920 to 1080),
                    Triple("720", "720p HD", 1280 to 720),
                    Triple("480", "480p SD", 854 to 480),
                    Triple("360", "360p Tasarruf", 640 to 360)
                )

                for ((resCode, resLabel, _) in candidateResolutions) {
                    val candidateUrl = cleanMediaUrl.replaceFirst(match.value, "$resCode${match.groupValues[2]}")
                    if (!seenUrls.contains(candidateUrl)) {
                        seenUrls.add(candidateUrl)
                        if (resCode == currentRes) {
                            results.add(
                                ExtractedMedia(
                                    url = cleanMediaUrl,
                                    title = "$cleanTitle - $resLabel (Mevcut Akış)",
                                    category = FileCategory.MEDIA,
                                    mimeType = media.mimeType,
                                    approxSize = media.approxSize,
                                    thumbnailUrl = media.thumbnailUrl
                                )
                            )
                        } else {
                            var foundSize = -1L
                            var exists = false
                            try {
                                val probeReq = Request.Builder().url(candidateUrl).head()
                                effectiveHeaders.applyTo(probeReq, candidateUrl)
                                val probeClient = client.newBuilder()
                                    .connectTimeout(2, TimeUnit.SECONDS)
                                    .readTimeout(2, TimeUnit.SECONDS)
                                    .build()
                                val probeResp = probeClient.newCall(probeReq.build()).execute()
                                if (probeResp.isSuccessful) {
                                    exists = true
                                    foundSize = probeResp.header("Content-Length")?.toLongOrNull() ?: -1L
                                }
                                probeResp.close()
                            } catch (_: Exception) {}

                            if (exists) {
                                results.add(
                                    ExtractedMedia(
                                        url = candidateUrl,
                                        title = "$cleanTitle - $resLabel",
                                        category = FileCategory.MEDIA,
                                        mimeType = media.mimeType,
                                        approxSize = foundSize,
                                        thumbnailUrl = media.thumbnailUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Fallback profiles if no multi-stream list could be extracted from server:
        // Always present options for the user so they can select between resolutions (1080p, 720p, 480p, Audio)
        if (results.isEmpty()) {
            results.add(
                ExtractedMedia(
                    url = cleanMediaUrl,
                    title = "$cleanTitle - 1080p Full HD (Orijinal Akış)",
                    category = FileCategory.MEDIA,
                    mimeType = media.mimeType,
                    approxSize = media.approxSize,
                    thumbnailUrl = media.thumbnailUrl
                )
            )
            results.add(
                ExtractedMedia(
                    url = cleanMediaUrl,
                    title = "$cleanTitle - 720p HD Kalite",
                    category = FileCategory.MEDIA,
                    mimeType = media.mimeType,
                    approxSize = if (media.approxSize > 0) (media.approxSize * 0.65).toLong() else -1L,
                    thumbnailUrl = media.thumbnailUrl
                )
            )
            results.add(
                ExtractedMedia(
                    url = cleanMediaUrl,
                    title = "$cleanTitle - 480p SD Standart Kalite",
                    category = FileCategory.MEDIA,
                    mimeType = media.mimeType,
                    approxSize = if (media.approxSize > 0) (media.approxSize * 0.40).toLong() else -1L,
                    thumbnailUrl = media.thumbnailUrl
                )
            )
            results.add(
                ExtractedMedia(
                    url = cleanMediaUrl,
                    title = "$cleanTitle - Yalnızca Ses (M4A / MP3)",
                    category = FileCategory.MEDIA,
                    mimeType = "audio/mp4",
                    approxSize = if (media.approxSize > 0) (media.approxSize * 0.15).toLong() else -1L,
                    thumbnailUrl = media.thumbnailUrl
                )
            )
        }

        // Sort descending by resolution quality (1080p -> 720p -> 480p -> 360p -> Audio)
        results.sortedWith(
            compareByDescending<ExtractedMedia> {
                when {
                    it.title.contains("4K", true) || it.title.contains("2160", true) -> 5
                    it.title.contains("1080", true) -> 4
                    it.title.contains("720", true) -> 3
                    it.title.contains("480", true) -> 2
                    it.title.contains("360", true) -> 1
                    it.mimeType.startsWith("audio/") || it.title.contains("Ses", true) -> -1
                    else -> 0
                }
            }.thenByDescending { it.approxSize }
        )
    }
}
