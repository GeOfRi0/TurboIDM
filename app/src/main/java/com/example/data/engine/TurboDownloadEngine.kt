package com.example.data.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.DownloadStatus
import com.example.data.model.FileCategory
import com.example.data.model.SegmentProgress
import com.example.data.model.toHeaderConfig
import com.example.data.service.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class TurboDownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope
) {
    private val okHttpClient = OkHttpProvider.client

    // Active download coroutine jobs
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Live segment progress map: downloadId -> List<SegmentProgress>
    private val _segmentProgressMap = MutableStateFlow<Map<String, List<SegmentProgress>>>(emptyMap())
    val segmentProgressMap: StateFlow<Map<String, List<SegmentProgress>>> = _segmentProgressMap.asStateFlow()

    // Live speed map: downloadId -> speedBytesPerSec
    private val _speedMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val speedMap: StateFlow<Map<String, Long>> = _speedMap.asStateFlow()

    // Global speed limit in bytes/sec (0 = Unlimited)
    private val _maxSpeedLimitBytesPerSec = MutableStateFlow(0L)
    val maxSpeedLimitBytesPerSec: StateFlow<Long> = _maxSpeedLimitBytesPerSec.asStateFlow()

    fun setMaxSpeedLimit(bytesPerSec: Long) {
        _maxSpeedLimitBytesPerSec.value = bytesPerSec.coerceAtLeast(0L)
    }

    // Live in-memory progress state for active downloads: downloadId -> Triple(downloadedBytes, totalBytes, speed)
    data class LiveProgress(val downloadedBytes: Long, val totalBytes: Long, val speed: Long)
    private val liveProgressMap = ConcurrentHashMap<String, LiveProgress>()
    private val lastSavedPercentMap = ConcurrentHashMap<String, Int>()

    private val notificationHelper = SystemDownloadNotificationHelper(context)

    suspend fun applySpeedThrottling(bytesProcessed: Int, durationNanos: Long) {
        val limit = _maxSpeedLimitBytesPerSec.value
        if (limit <= 0L || bytesProcessed <= 0) return
        val expectedNanos = (bytesProcessed.toLong() * 1_000_000_000L) / limit
        val sleepNanos = expectedNanos - durationNanos
        if (sleepNanos > 0L) {
            val sleepMillis = sleepNanos / 1_000_000L
            if (sleepMillis > 0L) {
                delay(sleepMillis)
            }
        }
    }

    companion object {
        const val THROTTLE_INTERVAL_MS = 500L
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    var onDownloadStateChanged: ((id: String, status: DownloadStatus) -> Unit)? = null

    fun getActiveDownloadIds(): Set<String> = activeJobs.keys.toSet()

    suspend fun createDownloadEntity(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(url)
        val downloadId = UUID.randomUUID().toString()
        val guessedName = customFileName ?: extractFileNameFromUrl(cleanUrl)
        val initialCategory = FileCategory.detect(guessedName, "")

        val entity = DownloadEntity(
            id = downloadId,
            url = cleanUrl,
            fileName = guessedName,
            filePath = "",
            mimeType = "application/octet-stream",
            category = initialCategory.name,
            totalBytes = 0L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            speedBytesPerSec = 0L,
            threadCount = threadCount.coerceIn(1, 8),
            createdAt = System.currentTimeMillis(),
            userAgent = headerConfig?.effectiveUserAgent,
            referer = headerConfig?.customReferer,
            cookies = headerConfig?.customCookies,
            extraHeadersJson = headerConfig?.toExtraHeadersJson()
        )
        downloadDao.insert(entity)
        return@withContext downloadId
    }

    suspend fun enqueueDownload(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        return@withContext createDownloadEntity(url, customFileName, threadCount, headerConfig)
    }

    fun startDownload(downloadId: String) {
        if (activeJobs.containsKey(downloadId)) return

        val job = scope.launch(Dispatchers.IO) {
            val entity = downloadDao.getDownloadById(downloadId) ?: return@launch
            try {
                executeDownload(entity)
            } catch (e: CancellationException) {
                downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
                onDownloadStateChanged?.invoke(downloadId, DownloadStatus.PAUSED)
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = e.localizedMessage ?: "İndirme sırasında bir hata oluştu"
                val updated = entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = errorMsg,
                    speedBytesPerSec = 0L
                )
                downloadDao.update(updated)
                onDownloadStateChanged?.invoke(downloadId, DownloadStatus.FAILED)
            } finally {
                activeJobs.remove(downloadId)
                updateSpeed(downloadId, 0L)
                if (activeJobs.isEmpty()) {
                    DownloadForegroundService.stop(context)
                }
            }
        }
        activeJobs[downloadId] = job
    }

    suspend fun flushAllActiveDownloadsToDatabase() = withContext(Dispatchers.IO) {
        liveProgressMap.forEach { (id, progress) ->
            downloadDao.updateProgress(
                id = id,
                downloaded = progress.downloadedBytes,
                total = progress.totalBytes,
                speed = progress.speed,
                status = DownloadStatus.DOWNLOADING.name
            )
        }
    }

    fun pauseDownload(downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val progress = liveProgressMap.remove(downloadId)
            lastSavedPercentMap.remove(downloadId)
            if (progress != null) {
                downloadDao.updateProgress(
                    id = downloadId,
                    downloaded = progress.downloadedBytes,
                    total = progress.totalBytes,
                    speed = 0L,
                    status = DownloadStatus.PAUSED.name
                )
            } else {
                downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
            }
            updateSpeed(downloadId, 0L)
            notificationHelper.cancelNotification(downloadId)
            if (activeJobs.isEmpty()) {
                DownloadForegroundService.stop(context)
            }
            onDownloadStateChanged?.invoke(downloadId, DownloadStatus.PAUSED)
        }
    }

    fun resumeDownload(downloadId: String) {
        startDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val entity = downloadDao.getDownloadById(downloadId)
            entity?.let {
                // Delete partial files
                cleanupTempFiles(downloadId, it.threadCount)
                downloadDao.delete(it)
            }
            updateSpeed(downloadId, 0L)
            removeSegmentProgress(downloadId)
            notificationHelper.cancelNotification(downloadId)
            if (activeJobs.isEmpty()) {
                DownloadForegroundService.stop(context)
            }
            onDownloadStateChanged?.invoke(downloadId, DownloadStatus.CANCELLED)
        }
    }

    private suspend fun executeDownload(initialEntity: DownloadEntity): Unit = withContext(Dispatchers.IO) {
        downloadDao.updateStatus(initialEntity.id, DownloadStatus.CONNECTING.name)
        DownloadForegroundService.startOrUpdate(
            context = context,
            fileName = initialEntity.fileName,
            progress = 0,
            speedBytesPerSec = 0L,
            activeCount = activeJobs.size.coerceAtLeast(1)
        )

        val headerConfig = initialEntity.toHeaderConfig()

        // 1. Probe the server with HEAD/GET to inspect Content-Length, Accept-Ranges, Content-Disposition
        val headRequestBuilder = Request.Builder().url(initialEntity.url).head()
        headerConfig.applyTo(headRequestBuilder, initialEntity.url)
        val request = headRequestBuilder.build()

        var response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            null
        }

        // If HEAD was rejected or returned 405 Method Not Allowed, fallback to GET range 0-0
        if (response == null || !response.isSuccessful) {
            response?.close()
            val getTestBuilder = Request.Builder()
                .url(initialEntity.url)
                .header("Range", "bytes=0-0")
                .get()
            headerConfig.applyTo(getTestBuilder, initialEntity.url)
            val getTestRequest = getTestBuilder.build()
            response = try {
                okHttpClient.newCall(getTestRequest).execute()
            } catch (e: Exception) {
                null
            }
        }

        val contentLengthHeader = response?.header("Content-Length")
        val contentRangeHeader = response?.header("Content-Range")
        val acceptRangesHeader = response?.header("Accept-Ranges")
        val disposition = response?.header("Content-Disposition").orEmpty()
        val contentType = response?.header("Content-Type").orEmpty()
        response?.close()

        // If server returns an HTML web page (user pasted a page URL, movie site, or download portal):
        // Automatically sniff for real media files on that webpage instead of saving HTML as .bin!
        if (contentType.contains("text/html", ignoreCase = true) && disposition.isBlank()) {
            val sniffer = MediaSniffer(okHttpClient)
            val sniffed = sniffer.sniff(initialEntity.url, headerConfig)
            val directMedia = sniffed.firstOrNull { it.url != initialEntity.url && (it.category == FileCategory.MEDIA || it.category == FileCategory.APKS || it.category == FileCategory.ARCHIVES) }
                ?: sniffed.firstOrNull { it.url != initialEntity.url }

            if (directMedia != null) {
                executeDownload(
                    initialEntity.copy(
                        url = directMedia.url,
                        fileName = directMedia.title,
                        mimeType = directMedia.mimeType,
                        category = directMedia.category.name
                    )
                )
                return@withContext
            } else {
                throw Exception("Bu bağlantı doğrudan bir dosya değil, bir web sayfasıdır. Lütfen 'Medyaları Tara' butonunu kullanarak sayfadaki video ve medyaları seçin.")
            }
        }

        var totalBytes = contentLengthHeader?.toLongOrNull() ?: 0L
        if (totalBytes <= 1L && contentRangeHeader != null) {
            // e.g. "bytes 0-0/1048576"
            val totalFromRange = contentRangeHeader.substringAfterLast('/', "").toLongOrNull()
            if (totalFromRange != null) totalBytes = totalFromRange
        }

        val resolvedFileName = resolveFileName(initialEntity.url, disposition, contentType, initialEntity.fileName)
        val category = FileCategory.detect(resolvedFileName, contentType)

        // Target storage directory (Downloads)
        val targetDir = getDownloadsDirectory()
        if (!targetDir.exists()) targetDir.mkdirs()

        // 2. Storage Check & Pre-allocation (RandomAccessFile.setLength)
        if (totalBytes > 0) {
            val usableSpace = targetDir.usableSpace
            if (usableSpace < totalBytes) {
                val neededFormatted = formatBytes(totalBytes)
                val availableFormatted = formatBytes(usableSpace)
                throw Exception("Yetersiz depolama alanı! Gerekli: $neededFormatted, Kullanılabilir: $availableFormatted")
            }
        }

        val targetFile = File(targetDir, resolvedFileName)

        // Pre-allocation is handled strictly inside downloadParallelSegments

        val updatedEntity = initialEntity.copy(
            fileName = resolvedFileName,
            filePath = targetFile.absolutePath,
            mimeType = contentType.ifBlank { "application/octet-stream" },
            category = category.name,
            totalBytes = totalBytes,
            status = DownloadStatus.DOWNLOADING.name
        )
        downloadDao.update(updatedEntity)

        // Decide multi-threaded or single-stream
        val supportsRange = (acceptRangesHeader?.contains("bytes", ignoreCase = true) == true ||
            contentRangeHeader?.contains("bytes", ignoreCase = true) == true) && totalBytes > 1024 * 512

        val isYouTube = updatedEntity.url.contains("googlevideo.com") ||
            updatedEntity.url.contains("youtube.com") ||
            updatedEntity.url.contains("videoplayback")

        var activeDownloadEntity = updatedEntity
        if (isYouTube) {
            // Strip segment fragment limits from YouTube URLs so full video is streamed
            var cleanUrl = activeDownloadEntity.url
                .replace(Regex("""&range=\d+-\d+"""), "")
                .replace(Regex("""&rn=\d+"""), "")
            if (cleanUrl.contains("googlevideo.com") && !cleanUrl.contains("ratebypass=yes")) {
                val separator = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "${cleanUrl}${separator}ratebypass=yes"
            }
            activeDownloadEntity = activeDownloadEntity.copy(url = cleanUrl)
        }

        // YouTube CDN drops parallel socket connections with 403 or heavy throttles; single stream with YouTube headers delivers max bandwidth
        val effectiveThreads = if (supportsRange && !isYouTube) activeDownloadEntity.threadCount.coerceAtLeast(1) else 1

        if (supportsRange && effectiveThreads > 1) {
            try {
                downloadParallelSegments(activeDownloadEntity, targetFile, totalBytes, effectiveThreads)
            } catch (e: Exception) {
                // If parallel range segments encounter server block or socket drop, gracefully fallback to single stream
                e.printStackTrace()
                cleanupTempFiles(activeDownloadEntity.id, effectiveThreads)
                // Truncate targetFile if it was pre-allocated so single stream doesn't misjudge length
                if (targetFile.exists()) {
                    try {
                        RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                    } catch (ignored: Exception) {}
                }
                downloadSingleStream(activeDownloadEntity, targetFile, totalBytes)
            }
        } else {
            downloadSingleStream(activeDownloadEntity, targetFile, totalBytes)
        }

        // Final verification & magic byte inspection to guarantee real file extension (never .bin)
        if (targetFile.exists() && targetFile.length() > 0) {
            val finalBytes = targetFile.length()
            if (totalBytes > 0 && finalBytes < totalBytes) {
                throw Exception("İndirme eksik kaldı ($finalBytes / $totalBytes bayt). Bağlantı kesildi.")
            }
            var finalFile = targetFile
            var finalFileName = updatedEntity.fileName
            var finalMimeType = updatedEntity.mimeType
            var finalCategory = updatedEntity.category

            // Inspect magic bytes of the downloaded file
            val detected = FileSignatureDetector.detect(targetFile)
            if (detected != null) {
                if (detected.isHtmlOrWebPage) {
                    val isSocial = initialEntity.url.contains("instagram.com") ||
                        initialEntity.url.contains("youtube.com") ||
                        initialEntity.url.contains("tiktok.com") ||
                        initialEntity.url.contains("twitter.com") ||
                        initialEntity.url.contains("x.com")
                    val msg = if (isSocial) {
                        "İndirilen içerik bir video değil, web sayfası/oturum engelidir. Bu platform oturum veya 'Medyaları Tara' seçeneğini gerektirir."
                    } else {
                        "Bağlantı doğrudan bir dosya yerine web sayfası döndürdü. Lütfen sayfanın içindeki gerçek dosya bağlantısını kullanın."
                    }
                    throw Exception(msg)
                }

                val currentExt = targetFile.name.substringAfterLast('.', "").lowercase()
                val isGeneric = currentExt == "bin" || currentExt.isBlank() || currentExt == "tmp" ||
                    currentExt == "download" || currentExt == "part" || currentExt == "php" || currentExt == "html"
                val detectedExtClean = detected.extension.removePrefix(".").lowercase()

                if (isGeneric || currentExt != detectedExtClean) {
                    val base = targetFile.nameWithoutExtension.ifBlank { "download_${System.currentTimeMillis()}" }
                    var candidateFile = File(targetFile.parentFile, "$base${detected.extension}")
                    var counter = 1
                    while (candidateFile.exists() && candidateFile.absolutePath != targetFile.absolutePath) {
                        candidateFile = File(targetFile.parentFile, "${base}_$counter${detected.extension}")
                        counter++
                    }
                    if (targetFile.renameTo(candidateFile)) {
                        finalFile = candidateFile
                        finalFileName = candidateFile.name
                        finalMimeType = detected.mimeType
                        finalCategory = detected.category
                    }
                } else if (finalCategory != detected.category) {
                    finalCategory = detected.category
                    finalMimeType = detected.mimeType
                }
            } else {
                // If signature could not be matched and the extension is generic (.bin or .tmp), guess from mimeType
                val currentExt = targetFile.name.substringAfterLast('.', "").lowercase()
                if (currentExt == "bin" || currentExt == "tmp" || currentExt.isBlank()) {
                    val fallbackExt = when {
                        finalMimeType.contains("video") || finalMimeType.contains("mp4") -> ".mp4"
                        finalMimeType.contains("audio") || finalMimeType.contains("mpeg") -> ".mp3"
                        finalMimeType.contains("image") -> ".jpg"
                        finalMimeType.contains("pdf") -> ".pdf"
                        finalMimeType.contains("zip") -> ".zip"
                        else -> ""
                    }
                    if (fallbackExt.isNotBlank()) {
                        val base = targetFile.nameWithoutExtension.ifBlank { "download_${System.currentTimeMillis()}" }
                        var candidateFile = File(targetFile.parentFile, "$base$fallbackExt")
                        var counter = 1
                        while (candidateFile.exists() && candidateFile.absolutePath != targetFile.absolutePath) {
                            candidateFile = File(targetFile.parentFile, "${base}_$counter$fallbackExt")
                            counter++
                        }
                        if (targetFile.renameTo(candidateFile)) {
                            finalFile = candidateFile
                            finalFileName = candidateFile.name
                        }
                    }
                }
            }

            val actualFinalBytes = finalFile.length()
            val completedEntity = updatedEntity.copy(
                fileName = finalFileName,
                filePath = finalFile.absolutePath,
                mimeType = finalMimeType,
                category = finalCategory,
                downloadedBytes = actualFinalBytes,
                totalBytes = if (totalBytes <= 0) actualFinalBytes else totalBytes,
                status = DownloadStatus.COMPLETED.name,
                speedBytesPerSec = 0L,
                completedAt = System.currentTimeMillis()
            )
            downloadDao.update(completedEntity)

            // Trigger MediaScanner so Gallery and Media Players see it
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(finalMimeType),
                null
            )
            liveProgressMap.remove(completedEntity.id)
            lastSavedPercentMap.remove(completedEntity.id)
            notificationHelper.cancelNotification(updatedEntity.id)
            onDownloadStateChanged?.invoke(completedEntity.id, DownloadStatus.COMPLETED)
        } else {
            throw Exception("İndirilen dosya kaydedilemedi veya boş.")
        }
    }

    private data class SubChunk(
        val subIndex: Int,
        val originalSegmentIndex: Int,
        val startByte: Long,
        val endByte: Long,
        val isHelper: Boolean = false
    ) {
        val totalBytes: Long get() = (endByte - startByte + 1).coerceAtLeast(0L)
    }

    private suspend fun downloadParallelSegments(
        entity: DownloadEntity,
        targetFile: File,
        totalBytes: Long,
        threadCount: Int
    ) = withContext(Dispatchers.IO) {
        val headerConfig = entity.toHeaderConfig()
        // Pre-allocate the target file to the exact required size
        RandomAccessFile(targetFile, "rw").use { raf ->
            if (totalBytes > 0 && raf.length() < totalBytes) {
                raf.setLength(totalBytes)
            }
        }

        val partSize = totalBytes / threadCount
        val subChunks = CopyOnWriteArrayList<SubChunk>()
        val nextSubIndex = AtomicInteger(threadCount)
        val chunkDownloadedMap = ConcurrentHashMap<Int, AtomicLong>()

        for (i in 0 until threadCount) {
            val start = i * partSize
            val end = if (i == threadCount - 1) totalBytes - 1 else (i + 1) * partSize - 1
            val sc = SubChunk(
                subIndex = i,
                originalSegmentIndex = i,
                startByte = start,
                endByte = end,
                isHelper = false
            )
            subChunks.add(sc)
            chunkDownloadedMap[i] = AtomicLong(0L)
        }

        val downloadedBytesAtomic = AtomicLong(0L)

        // Rate-limited segment UI progress updater (at most once every 500ms)
        val lastProgressUiUpdate = AtomicLong(0L)
        val refreshProgressUI: (Boolean) -> Unit = { force ->
            val now = System.currentTimeMillis()
            if (force || now - lastProgressUiUpdate.get() >= THROTTLE_INTERVAL_MS) {
                lastProgressUiUpdate.set(now)
                val progressList = subChunks.map { sc ->
                    val dl = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                    SegmentProgress(
                        segmentIndex = sc.subIndex,
                        startByte = sc.startByte,
                        endByte = sc.endByte,
                        downloadedBytes = dl,
                        totalBytes = sc.totalBytes,
                        isHelper = sc.isHelper,
                        helperTargetSegmentIndex = if (sc.isHelper) sc.originalSegmentIndex else null
                    )
                }
                updateSegmentProgress(entity.id, progressList)
            }
        }
        refreshProgressUI(true)

        // Speed and progress tracker background job (throttled to 500ms)
        val speedTrackerJob = scope.launch(Dispatchers.IO) {
            var lastDownloaded = downloadedBytesAtomic.get()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(THROTTLE_INTERVAL_MS)
                val currentTime = System.currentTimeMillis()
                val currentDownloaded = downloadedBytesAtomic.get()
                val timeDiff = (currentTime - lastTime).coerceAtLeast(1)
                val bytesDiff = (currentDownloaded - lastDownloaded).coerceAtLeast(0)

                val speed = (bytesDiff * 1000L) / timeDiff
                updateSpeed(entity.id, speed)
                refreshProgressUI(true)

                liveProgressMap[entity.id] = LiveProgress(currentDownloaded, totalBytes, speed)
                val percent = if (totalBytes > 0) ((currentDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                val lastSavedPercent = lastSavedPercentMap[entity.id] ?: -1
                if (lastSavedPercent == -1 || percent >= lastSavedPercent + 5 || (percent == 100 && lastSavedPercent != 100)) {
                    lastSavedPercentMap[entity.id] = percent
                    downloadDao.updateProgress(
                        entity.id,
                        currentDownloaded,
                        totalBytes,
                        speed,
                        DownloadStatus.DOWNLOADING.name
                    )
                }
                notificationHelper.updateProgressNotification(
                    entity.copy(downloadedBytes = currentDownloaded, totalBytes = totalBytes),
                    speed
                )
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    fileName = entity.fileName,
                    progress = percent,
                    speedBytesPerSec = speed,
                    activeCount = activeJobs.size.coerceAtLeast(1)
                )

                lastDownloaded = currentDownloaded
                lastTime = currentTime
            }
        }

        val activeWorkers = ConcurrentHashMap<Int, SubChunk>()
        val completedSubIndexes = ConcurrentHashMap.newKeySet<Int>()

        suspend fun downloadChunkStream(chunk: SubChunk, workerId: Int) {
            val dlAtomic = chunkDownloadedMap.getOrPut(chunk.subIndex) { AtomicLong(0L) }
            val initialBytes = dlAtomic.get()
            if (initialBytes >= chunk.totalBytes && chunk.totalBytes > 0) {
                completedSubIndexes.add(chunk.subIndex)
                return
            }

            val requestStart = chunk.startByte + initialBytes
            val requestEnd = chunk.endByte

            if (requestStart > requestEnd) {
                completedSubIndexes.add(chunk.subIndex)
                return
            }

            val segRequestBuilder = Request.Builder()
                .url(entity.url)
                .header("Range", "bytes=$requestStart-$requestEnd")
                .get()
            headerConfig.applyTo(segRequestBuilder, entity.url)
            val segRequest = segRequestBuilder.build()

            val segResponse = okHttpClient.newCall(segRequest).execute()
            if (!segResponse.isSuccessful && segResponse.code != 206 && segResponse.code != 200) {
                segResponse.close()
                throw Exception("Parça #${chunk.subIndex} isteği başarısız: HTTP ${segResponse.code}")
            }

            // If server returned 200 instead of 206 for a non-zero range, it doesn't support Range requests
            if (segResponse.code == 200 && requestStart > 0L) {
                segResponse.close()
                throw Exception("Sunucu Range (parçalı indirme) desteğini reddetti (HTTP 200). Tek kanallı akışa geçiliyor.")
            }

            val inputStream = segResponse.body?.byteStream() ?: throw Exception("Boş akış")

            // Open RandomAccessFile and FileChannel directly to the final target file
            val targetRaf = RandomAccessFile(targetFile, "rw")
            val channel = targetRaf.channel

            val buffer = ByteArray(128 * 1024)
            val byteBuffer = ByteBuffer.allocateDirect(128 * 1024)
            var read: Int
            var currentWritePosition = requestStart

            try {
                while (true) {
                    if (!isActive) break
                    val chunkStartTime = System.nanoTime()
                    read = inputStream.read(buffer)
                    if (read == -1) break
                    val chunkDuration = System.nanoTime() - chunkStartTime

                    // Write directly into targetFile at the absolute file byte position
                    byteBuffer.clear()
                    byteBuffer.put(buffer, 0, read)
                    byteBuffer.flip()

                    while (byteBuffer.hasRemaining()) {
                        val written = channel.write(byteBuffer, currentWritePosition)
                        currentWritePosition += written
                    }

                    downloadedBytesAtomic.addAndGet(read.toLong())
                    dlAtomic.addAndGet(read.toLong())
                    refreshProgressUI(false)

                    // Throttle download speed if user set a speed limit
                    applySpeedThrottling(read, chunkDuration)
                }
            } finally {
                channel.close()
                targetRaf.close()
                inputStream.close()
                segResponse.close()
            }

            if (dlAtomic.get() >= chunk.totalBytes) {
                completedSubIndexes.add(chunk.subIndex)
            }
        }

        try {
            // Launch worker coroutines (1 per partitioned segment)
            val workers = (0 until threadCount).map { workerId ->
                async(Dispatchers.IO) {
                    val task = subChunks.getOrNull(workerId) ?: return@async
                    activeWorkers[workerId] = task
                    try {
                        downloadChunkStream(task, workerId)
                    } finally {
                        activeWorkers.remove(workerId)
                    }
                }
            }

            workers.awaitAll()

            // Verify all subChunks are finished; if any chunk was cut short, finish it sequentially
            for (sc in subChunks) {
                val downloaded = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                if (downloaded < sc.totalBytes) {
                    downloadChunkStream(sc, 0)
                }
            }

            val totalDownloaded = downloadedBytesAtomic.get()
            if (totalBytes > 0 && totalDownloaded < totalBytes) {
                throw Exception("Parçalı indirme tamamlanamadı: $totalDownloaded / $totalBytes bayt indirildi. Bağlantı kesildi.")
            }

            // Direct write completes without needing any file merge or temp copy!
        } finally {
            speedTrackerJob.cancel()
        }
    }

    private suspend fun downloadSingleStream(
        entity: DownloadEntity,
        targetFile: File,
        totalBytes: Long
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

        val headerConfig = entity.toHeaderConfig()
        val requestBuilder = Request.Builder().url(entity.url)
        headerConfig.applyTo(requestBuilder, entity.url)

        if (existingBytes > 0 && totalBytes > existingBytes) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = okHttpClient.newCall(requestBuilder.get().build()).execute()
        if (!response.isSuccessful && response.code != 206 && response.code != 200) {
            response.close()
            throw Exception("Sunucu yanıt vermedi: HTTP ${response.code}")
        }

        val append = (response.code == 206 && existingBytes > 0)
        val inputStream: InputStream = response.body?.byteStream() ?: throw Exception("Boş veri akışı")

        // Direct write via RandomAccessFile and FileChannel directly into final targetFile
        val targetRaf = RandomAccessFile(targetFile, "rw")
        if (!append && existingBytes > 0) {
            targetRaf.setLength(0L)
        }
        val channel = targetRaf.channel
        val startWritePos = if (append) existingBytes else 0L

        val downloadedAtomic = AtomicLong(startWritePos)
        val resolvedTotal = if (totalBytes > 0) totalBytes else response.body?.contentLength() ?: -1L

        val speedTrackerJob = scope.launch(Dispatchers.IO) {
            var lastDownloaded = downloadedAtomic.get()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(THROTTLE_INTERVAL_MS)
                val currentTime = System.currentTimeMillis()
                val currentDownloaded = downloadedAtomic.get()
                val timeDiff = (currentTime - lastTime).coerceAtLeast(1)
                val bytesDiff = (currentDownloaded - lastDownloaded).coerceAtLeast(0)

                val speed = (bytesDiff * 1000L) / timeDiff
                updateSpeed(entity.id, speed)

                liveProgressMap[entity.id] = LiveProgress(currentDownloaded, resolvedTotal, speed)
                val percent = if (resolvedTotal > 0) ((currentDownloaded * 100) / resolvedTotal).toInt().coerceIn(0, 100) else 0

                val lastSavedPercent = lastSavedPercentMap[entity.id] ?: -1
                if (lastSavedPercent == -1 || percent >= lastSavedPercent + 5 || (percent == 100 && lastSavedPercent != 100)) {
                    lastSavedPercentMap[entity.id] = percent
                    downloadDao.updateProgress(
                        entity.id,
                        currentDownloaded,
                        resolvedTotal,
                        speed,
                        DownloadStatus.DOWNLOADING.name
                    )
                }
                notificationHelper.updateProgressNotification(
                    entity.copy(downloadedBytes = currentDownloaded, totalBytes = resolvedTotal),
                    speed
                )
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    fileName = entity.fileName,
                    progress = percent,
                    speedBytesPerSec = speed,
                    activeCount = activeJobs.size.coerceAtLeast(1)
                )

                lastDownloaded = currentDownloaded
                lastTime = currentTime
            }
        }

        val buffer = ByteArray(128 * 1024)
        val byteBuffer = ByteBuffer.allocateDirect(128 * 1024)
        var read: Int
        var currentWritePosition = startWritePos

        try {
            while (true) {
                if (!isActive) break
                val chunkStartTime = System.nanoTime()
                read = inputStream.read(buffer)
                if (read == -1) break
                val chunkDuration = System.nanoTime() - chunkStartTime

                byteBuffer.clear()
                byteBuffer.put(buffer, 0, read)
                byteBuffer.flip()

                while (byteBuffer.hasRemaining()) {
                    val written = channel.write(byteBuffer, currentWritePosition)
                    currentWritePosition += written
                }

                downloadedAtomic.addAndGet(read.toLong())

                // Throttle download speed if user set a speed limit
                applySpeedThrottling(read, chunkDuration)
            }

            val finalDownloaded = downloadedAtomic.get()
            if (resolvedTotal > 0 && finalDownloaded < resolvedTotal) {
                throw Exception("İndirme tamamlanamadı: Bağlantı erken kesildi (${formatBytes(finalDownloaded)} / ${formatBytes(resolvedTotal)}).")
            }
        } finally {
            speedTrackerJob.cancel()
            channel.close()
            targetRaf.close()
            inputStream.close()
            response.close()
        }
    }

    private fun cleanupSubChunkFiles(downloadId: String) {
        val files = context.cacheDir.listFiles { _, name ->
            name.startsWith("part_${downloadId}_") && name.endsWith(".tmp")
        }
        files?.forEach { it.delete() }
    }

    private fun cleanupTempFiles(downloadId: String, threadCount: Int) {
        cleanupSubChunkFiles(downloadId)
    }

    private fun getDownloadsDirectory(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
    }

    private fun updateSpeed(id: String, speed: Long) {
        val current = _speedMap.value.toMutableMap()
        if (speed <= 0) current.remove(id) else current[id] = speed
        _speedMap.value = current
    }

    private fun updateSegmentProgress(id: String, segments: List<SegmentProgress>) {
        val current = _segmentProgressMap.value.toMutableMap()
        current[id] = segments
        _segmentProgressMap.value = current
    }

    private fun removeSegmentProgress(id: String) {
        val current = _segmentProgressMap.value.toMutableMap()
        current.remove(id)
        _segmentProgressMap.value = current
    }

    private fun extractFileNameFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val lastSegment = clean.substringAfterLast('/', "")
        if (lastSegment.isNotBlank() && lastSegment.contains('.')) {
            val decoded = try { URLDecoder.decode(lastSegment, "UTF-8") } catch (e: Exception) { lastSegment }
            val sanitized = sanitizeFileName(decoded)
            if (sanitized.isNotBlank()) return sanitized
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun extractFileNameFromQueryParams(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val candidates = listOf("filename", "file", "response-content-disposition", "name", "title", "fn")
            for (param in candidates) {
                val value = uri.getQueryParameter(param)
                if (!value.isNullOrBlank()) {
                    val decoded = URLDecoder.decode(value, "UTF-8")
                    if (decoded.contains("filename=")) {
                        val sub = decoded.substringAfter("filename=").trim('"', '\'').substringBefore(';')
                        if (sub.isNotBlank() && sub.contains('.')) return sub
                    }
                    if (decoded.contains('.')) return decoded
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractExtensionFromQueryParams(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val candidates = listOf("filename", "file", "response-content-disposition", "name", "title", "fn", "format", "ext")
            for (param in candidates) {
                val value = uri.getQueryParameter(param)
                if (!value.isNullOrBlank()) {
                    val decoded = URLDecoder.decode(value, "UTF-8")
                    if (decoded.contains('.')) {
                        val ext = ".${decoded.substringAfterLast('.').lowercase()}"
                        if (ext.length in 3..6) return ext
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun detectExtension(mimeType: String, url: String): String {
        val cleanMime = mimeType.substringBefore(';').trim().lowercase()

        // 1. Check URL path for explicit file extension
        val urlPath = url.substringBefore('?').substringBefore('#')
        val lastPathSegment = urlPath.substringAfterLast('/', "")
        if (lastPathSegment.contains('.')) {
            val ext = ".${lastPathSegment.substringAfterLast('.').lowercase()}"
            val knownExtensions = listOf(
                ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".3gp", ".ts", ".m4v",
                ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".opus",
                ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg",
                ".pdf", ".epub", ".docx", ".xlsx", ".pptx", ".txt",
                ".zip", ".rar", ".7z", ".tar", ".gz", ".apk", ".iso"
            )
            if (knownExtensions.contains(ext)) {
                return ext
            }
        }

        // Check query parameters for file extension hints
        val queryExt = extractExtensionFromQueryParams(url)
        if (queryExt.isNotBlank()) {
            return queryExt
        }

        // 2. Android MimeTypeMap lookup (skip if it maps to generic 'bin')
        if (cleanMime.isNotBlank()) {
            val mapExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
            if (!mapExt.isNullOrBlank() && mapExt != "bin") {
                return ".$mapExt"
            }
        }

        // 3. Rich dictionary matching by MIME type
        val lowerMime = cleanMime.lowercase()
        val dictExt = when {
            lowerMime.contains("mp4") || lowerMime.contains("m4v") -> ".mp4"
            lowerMime.contains("webm") -> ".webm"
            lowerMime.contains("matroska") || lowerMime.contains("mkv") -> ".mkv"
            lowerMime.contains("quicktime") || lowerMime.contains("mov") -> ".mov"
            lowerMime.contains("msvideo") || lowerMime.contains("avi") -> ".avi"
            lowerMime.contains("3gpp") || lowerMime.contains("3gp") -> ".3gp"
            lowerMime.contains("flv") -> ".flv"
            lowerMime.contains("audio/mpeg") || lowerMime.contains("mp3") -> ".mp3"
            lowerMime.contains("m4a") || lowerMime.contains("audio/mp4") -> ".m4a"
            lowerMime.contains("wav") -> ".wav"
            lowerMime.contains("flac") -> ".flac"
            lowerMime.contains("ogg") || lowerMime.contains("opus") -> ".ogg"
            lowerMime.contains("aac") -> ".aac"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") -> ".jpg"
            lowerMime.contains("png") -> ".png"
            lowerMime.contains("webp") -> ".webp"
            lowerMime.contains("gif") -> ".gif"
            lowerMime.contains("svg") -> ".svg"
            lowerMime.contains("pdf") -> ".pdf"
            lowerMime.contains("vnd.android.package-archive") || lowerMime.contains("apk") -> ".apk"
            lowerMime.contains("zip") -> ".zip"
            lowerMime.contains("rar") -> ".rar"
            lowerMime.contains("7z") -> ".7z"
            lowerMime.contains("tar") -> ".tar"
            lowerMime.contains("gzip") || lowerMime.contains("gz") -> ".gz"
            lowerMime.contains("msword") || lowerMime.contains("wordprocessingml") -> ".docx"
            lowerMime.contains("ms-excel") || lowerMime.contains("spreadsheetml") -> ".xlsx"
            lowerMime.contains("ms-powerpoint") || lowerMime.contains("presentationml") -> ".pptx"
            lowerMime.contains("text/html") -> ".html"
            lowerMime.contains("text/plain") -> ".txt"
            lowerMime.contains("json") -> ".json"
            lowerMime.startsWith("video/") -> ".mp4"
            lowerMime.startsWith("audio/") -> ".mp3"
            lowerMime.startsWith("image/") -> ".jpg"
            else -> ""
        }
        if (dictExt.isNotBlank()) return dictExt

        // 4. URL host and path context hints
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("googlevideo.com") || lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> ".mp4"
            lowerUrl.contains("cdninstagram.com") || lowerUrl.contains("instagram.com") -> ".mp4"
            lowerUrl.contains("tiktokcdn.com") || lowerUrl.contains("tiktok.com") -> ".mp4"
            lowerUrl.contains("fbcdn.net") || lowerUrl.contains("facebook.com") -> ".mp4"
            lowerUrl.contains("/audio/") || lowerUrl.contains("audio") || lowerUrl.contains("sound") -> ".mp3"
            lowerUrl.contains("/image/") || lowerUrl.contains("photo") || lowerUrl.contains("picture") -> ".jpg"
            else -> ""
        }
    }

    private fun resolveFileName(url: String, disposition: String, mimeType: String, fallback: String): String {
        // 1. Content-Disposition header (RFC 5987 / RFC 6266 + standard)
        if (disposition.isNotBlank()) {
            val rfcPattern = Pattern.compile("""filename\*=UTF-8''([^;\s]+)""", Pattern.CASE_INSENSITIVE)
            val rfcMatcher = rfcPattern.matcher(disposition)
            if (rfcMatcher.find()) {
                val raw = rfcMatcher.group(1).orEmpty()
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) {
                    val sanitized = sanitizeFileName(decoded)
                    if (sanitized.contains('.')) return sanitized
                    val ext = detectExtension(mimeType, url)
                    return if (ext.isNotBlank()) "$sanitized$ext" else sanitized
                }
            }

            val standardPattern = Pattern.compile("""filename\s*=\s*["']?([^"';]+)["']?""", Pattern.CASE_INSENSITIVE)
            val standardMatcher = standardPattern.matcher(disposition)
            if (standardMatcher.find()) {
                val raw = standardMatcher.group(1).orEmpty().trim('"', '\'')
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) {
                    val sanitized = sanitizeFileName(decoded)
                    if (sanitized.contains('.')) return sanitized
                    val ext = detectExtension(mimeType, url)
                    return if (ext.isNotBlank()) "$sanitized$ext" else sanitized
                }
            }
        }

        // 2. Query parameter check (e.g. ?filename=video.mp4)
        val queryFileName = extractFileNameFromQueryParams(url)
        if (queryFileName.isNotBlank() && queryFileName.contains('.')) {
            return sanitizeFileName(queryFileName)
        }

        // 3. Fallback title (e.g. from YouTube oEmbed, Instagram, or user-defined title)
        val cleanFallback = fallback.trim()
        val detectedExt = detectExtension(mimeType, url)
        val baseName: String

        if (cleanFallback.isNotBlank() && !cleanFallback.startsWith("file_") && !cleanFallback.startsWith("download_")) {
            if (cleanFallback.contains('.')) {
                return sanitizeFileName(cleanFallback)
            } else {
                baseName = sanitizeFileName(cleanFallback)
            }
        } else {
            val fromUrl = extractFileNameFromUrl(url)
            if (fromUrl.contains('.')) {
                return sanitizeFileName(fromUrl)
            }
            baseName = sanitizeFileName(fromUrl)
        }

        // 4. Append detected extension without duplication (never force .bin)
        return if (detectedExt.isBlank() || baseName.endsWith(detectedExt, ignoreCase = true)) {
            baseName
        } else {
            "$baseName$detectedExt"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
