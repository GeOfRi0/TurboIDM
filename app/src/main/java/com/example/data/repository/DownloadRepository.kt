package com.example.data.repository

import com.example.data.engine.DownloadQueueManager
import com.example.data.engine.MediaSniffer
import com.example.data.engine.TurboDownloadEngine
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.DownloadStatus
import com.example.data.model.ExtractedMedia
import com.example.data.model.SegmentProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val downloadEngine: TurboDownloadEngine,
    private val mediaSniffer: MediaSniffer,
    private val queueManager: DownloadQueueManager
) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()
    val activeDownloads: Flow<List<DownloadEntity>> = downloadDao.getActiveDownloads()
    val segmentProgressMap: StateFlow<Map<String, List<SegmentProgress>>> = downloadEngine.segmentProgressMap
    val speedMap: StateFlow<Map<String, Long>> = downloadEngine.speedMap
    val maxConcurrentDownloads: StateFlow<Int> = queueManager.maxConcurrentDownloads
    val isQueuePaused: StateFlow<Boolean> = queueManager.isQueuePaused
    val onlyWifiMode: StateFlow<Boolean> = queueManager.onlyWifiMode
    val maxSpeedLimitBytesPerSec: StateFlow<Long> = downloadEngine.maxSpeedLimitBytesPerSec

    fun setOnlyWifiMode(enabled: Boolean) {
        queueManager.setOnlyWifiMode(enabled)
    }

    fun setMaxSpeedLimit(bytesPerSec: Long) {
        downloadEngine.setMaxSpeedLimit(bytesPerSec)
    }

    suspend fun enqueueDownload(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String {
        return queueManager.enqueueItem(url, customFileName, threadCount, headerConfig)
    }

    suspend fun enqueueBatch(
        items: List<Pair<String, String?>>,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ) {
        queueManager.enqueueBatch(items, threadCount, headerConfig)
    }

    suspend fun startAllQueuedAndPaused() {
        queueManager.startAllQueuedAndPaused()
    }

    fun pauseQueue() {
        queueManager.pauseQueue()
    }

    fun resumeQueue() {
        queueManager.resumeQueue()
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        queueManager.setMaxConcurrentDownloads(limit)
    }

    suspend fun sniffMedia(url: String, headerConfig: ChromeHeaderConfig? = null): List<ExtractedMedia> {
        return mediaSniffer.sniff(url, headerConfig)
    }

    suspend fun analyzeVideoResolutions(
        media: ExtractedMedia,
        currentWebUrl: String? = null,
        headerConfig: ChromeHeaderConfig? = null
    ): List<ExtractedMedia> {
        return mediaSniffer.analyzeVideoResolutions(media, currentWebUrl, headerConfig)
    }

    fun pauseDownload(id: String) {
        downloadEngine.pauseDownload(id)
    }

    fun resumeDownload(id: String) {
        downloadEngine.resumeDownload(id)
    }

    fun prioritizeDownload(id: String) {
        downloadEngine.startDownload(id)
    }

    fun cancelDownload(id: String) {
        downloadEngine.cancelDownload(id)
    }

    suspend fun deleteDownload(id: String, deleteFileFromStorage: Boolean) {
        val entity = downloadDao.getDownloadById(id)
        if (entity != null) {
            downloadEngine.cancelDownload(id)
            if (deleteFileFromStorage && entity.filePath.isNotBlank()) {
                val file = File(entity.filePath)
                if (file.exists()) file.delete()
            }
            downloadDao.deleteById(id)
        }
    }

    suspend fun retryDownload(id: String) {
        val entity = downloadDao.getDownloadById(id) ?: return
        val updated = entity.copy(
            status = DownloadStatus.QUEUED.name,
            errorMessage = null,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L
        )
        downloadDao.update(updated)
        queueManager.processQueue()
    }

    suspend fun flushAllActiveDownloadsToDatabase() {
        downloadEngine.flushAllActiveDownloadsToDatabase()
    }

    suspend fun clearHistory() {
        downloadDao.clearAll()
    }
}
