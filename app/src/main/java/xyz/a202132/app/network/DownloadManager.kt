package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 下载状态枚举
 */
enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELED
}

/**
 * 下载状态数据类
 */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,               // 0-100
    val speed: String = "0 KB/s",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val file: File? = null,
    val consecutiveFailures: Int = 0      // 连续失败次数（不含无网络）
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private val client = NetworkClient.withUserAgent(OkHttpClient.Builder()).build()
    
    // StateFlow for UI observation
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState = _downloadState.asStateFlow()
    
    private var downloadUrl: String = ""
    private var targetFile: File? = null
    
    // Control flags
    @Volatile
    private var isPaused = false
    @Volatile
    private var isCancelled = false
    
    // 连续失败计数器（排除无网络错误）
    private var consecutiveFailures = 0
    
    /**
     * 开始或继续下载
     */
    suspend fun startDownload(url: String, context: Context, versionName: String) {
        withContext(Dispatchers.IO) {
            try {
                val finalFileName = "update_$versionName.apk"
                val tempFileName = "update_$versionName.temp"
                val finalFile = File(context.externalCacheDir, finalFileName)
                val tempFile = File(context.externalCacheDir, tempFileName)
                
                // 1. Check if final file exists and is valid
                if (finalFile.exists() && finalFile.length() > 0) {
                    Log.d(TAG, "Final file exists, skipping download")
                    if (_downloadState.value.status != DownloadStatus.COMPLETED) {
                         _downloadState.value = DownloadState(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            file = finalFile,
                            totalBytes = finalFile.length(),
                            downloadedBytes = finalFile.length()
                        )
                    }
                    return@withContext
                }
                
                // 初始化检查
                if (downloadUrl != url) {
                    // 新下载请求
                    downloadUrl = url
                    targetFile = tempFile // 处理临时文件
                    
                    // 清理旧缓存文件
                    if (tempFile.exists()) {
                        // 决定是恢复下载还是重新开始下载。协议默认如果 URL 相同则恢复下载。
                        // 但这里我们将 URL 的更改视为新的下载。
                        // 为简单起见，我们将恢复下载的逻辑放在检查之后。
                    }
                    
                    // 清除其他版本中的旧文件
                    context.externalCacheDir?.listFiles()?.forEach { file ->
                        if (file.name.startsWith("update_") && file.name != finalFileName && file.name != tempFileName) {
                            file.delete()
                        }
                    }
                    
                    _downloadState.value = DownloadState(status = DownloadStatus.DOWNLOADING)
                } else {
                    if (_downloadState.value.status == DownloadStatus.PAUSED || 
                        _downloadState.value.status == DownloadStatus.ERROR) {
                        targetFile = tempFile // 确保定位到临时位置
                         _downloadState.value = _downloadState.value.copy(status = DownloadStatus.DOWNLOADING, error = null)
                    } else if (_downloadState.value.status == DownloadStatus.COMPLETED) {
                        return@withContext
                    }
                    // 如果 targetFile 为空（例如，应用程序重启），则将其重置。
                    if (targetFile == null) targetFile = tempFile
                }
                
                isPaused = false
                isCancelled = false
                var downloadedLength = if (targetFile!!.exists()) targetFile!!.length() else 0L
                var resumed = downloadedLength > 0

                fun buildRequest(length: Long): Request {
                    val builder = Request.Builder().url(url)
                    if (length > 0) {
                        Log.d(TAG, "Resuming download from bytes=$length")
                        builder.header("Range", "bytes=$length-")
                    }
                    return builder.build()
                }

                var response = client.newCall(buildRequest(downloadedLength)).execute()

                // HTTP 416 的非递归一次性回退：清除临时文件并重试一次完整下载。
                if (!response.isSuccessful && response.code == 416 && resumed) {
                    response.close()
                    if (targetFile!!.exists() && !targetFile!!.delete()) {
                        Log.w(TAG, "Failed to delete stale temp file on 416: ${targetFile!!.absolutePath}")
                    }
                    downloadedLength = 0L
                    resumed = false
                    response = client.newCall(buildRequest(downloadedLength)).execute()
                }

                if (!response.isSuccessful) {
                    if (response.code == 416) {
                        downloadUrl = ""
                        _downloadState.value = _downloadState.value.copy(
                            status = DownloadStatus.ERROR,
                            error = "服务器拒绝续传，请重试"
                        )
                        return@withContext
                    }
                    throw Exception("Download failed: ${response.code}")
                }

                val body = response.body ?: throw Exception("Response body is null")
                val totalLength = if (resumed) body.contentLength() + downloadedLength else body.contentLength()
                
                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null
                
                try {
                    inputStream = body.byteStream()
                    outputStream = FileOutputStream(targetFile!!, resumed) // Append mode when resume succeeds
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = downloadedLength
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = totalBytesRead
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                             // User cancelled, delete temp file
                            targetFile?.delete()
                            _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
                            return@withContext
                        }
                        
                        if (isPaused) {
                            _downloadState.value = _downloadState.value.copy(status = DownloadStatus.PAUSED)
                            return@withContext
                        }
                        
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // 每 500 毫秒计算一次速度和进度
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500) {
                            val timeDiff = currentTime - lastUpdateTime
                            val bytesDiff = totalBytesRead - lastBytesRead
                            val speed = calculateSpeed(bytesDiff, timeDiff)
                            val progress = if (totalLength > 0) ((totalBytesRead * 100) / totalLength).toInt() else 0
                            
                            _downloadState.value = DownloadState(
                                status = DownloadStatus.DOWNLOADING,
                                progress = progress.coerceIn(0, 100),
                                speed = speed,
                                downloadedBytes = totalBytesRead,
                                totalBytes = totalLength
                            )
                            
                            lastUpdateTime = currentTime
                            lastBytesRead = totalBytesRead
                        }
                    }
                    
                    // 下载完成 - 重命名为最终名称
                    if (targetFile!!.renameTo(finalFile)) {
                         consecutiveFailures = 0 // 下载成功，重置失败计数
                         _downloadState.value = DownloadState(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            speed = "0 KB/s",
                            downloadedBytes = totalBytesRead,
                            totalBytes = totalLength,
                            file = finalFile // 定位到最终文件
                        )
                    } else {
                        throw Exception("Failed to rename temp file to apk")
                    }
                    
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                    body.close()
                }
                
            } catch (e: Exception) {
                if (!isCancelled && !isPaused) {
                    Log.e(TAG, "Download error", e)
                    
                    // 判断是否为网络不可用错误
                    val isNetworkError = e is java.net.UnknownHostException ||
                            e is java.net.ConnectException ||
                            e is java.net.NoRouteToHostException ||
                            e is java.net.SocketTimeoutException ||
                            (e.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                            (e.message?.contains("No address associated", ignoreCase = true) == true)
                    
                    // 非网络错误才计入连续失败次数
                    if (!isNetworkError) {
                        consecutiveFailures++
                    }
                    
                    _downloadState.value = _downloadState.value.copy(
                        status = DownloadStatus.ERROR,
                        error = e.message,
                        consecutiveFailures = consecutiveFailures
                    )
                }
            }
        }
    }
    
    fun pauseDownload() {
        if (_downloadState.value.status == DownloadStatus.DOWNLOADING) {
            isPaused = true
        }
    }
    
    fun cancelDownload() {
        isCancelled = true
        // 如果临时文件存在，请将其删除。
        targetFile?.delete() 
        consecutiveFailures = 0 // 取消时重置失败计数
        _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
        downloadUrl = "" // 重置 URL 以强制下次重新开始
    }
    
    fun resetState() {
        _downloadState.value = DownloadState()
        downloadUrl = ""
        targetFile = null
        isPaused = false
        isCancelled = false
        consecutiveFailures = 0
    }
    
    // 检查是否存在有效的 APK 文件
    fun isApkReady(context: Context, versionName: String): File? {
        val file = File(context.externalCacheDir, "update_$versionName.apk")
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun calculateSpeed(bytesDiff: Long, timeMs: Long): String {
        if (timeMs == 0L) return "0 KB/s"
        val bytesPerSec = (bytesDiff * 1000) / timeMs
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
