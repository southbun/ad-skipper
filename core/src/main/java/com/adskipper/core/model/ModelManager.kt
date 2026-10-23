package com.adskipper.core.model

import android.content.Context
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.net.ssl.HttpsURLConnection

/** The only network path: explicit HTTPS GETs for two hash-pinned model files. */
class ModelManager(context: Context) {
    private val context = context.applicationContext
    private val dir = File(this.context.filesDir, "models/internvl3-2b-q4km")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var connection: HttpsURLConnection? = null

    data class Status(
        val running: Boolean = false,
        val downloaded: Boolean = false,
        val bytes: Long = 0,
        val message: String = "",
    )
    val totalBytes = ModelCatalog.files.sumOf { it.size }
    private val mutableStatus = MutableStateFlow(Status(downloaded = isDownloaded()))
    val status: StateFlow<Status> = mutableStatus

    fun isDownloaded(): Boolean = ModelCatalog.files.all { file ->
        File(dir, file.name).length() == file.size &&
            File(dir, "${file.name}.verified").let { it.isFile && it.readText() == file.sha256 }
    }

    fun modelPaths(): Pair<String, String>? = if (isDownloaded()) {
        File(dir, ModelCatalog.files[0].name).absolutePath to File(dir, ModelCatalog.files[1].name).absolutePath
    } else null

    fun startDownload() {
        if (job?.isActive == true) return
        job = scope.launch {
            mutableStatus.value = Status(running = true, message = "准备下载")
            try {
                check(dir.isDirectory || dir.mkdirs()) { "无法创建模型目录" }
                var completed = 0L
                for (file in ModelCatalog.files) {
                    downloadFile(file, completed)
                    completed += file.size
                }
                mutableStatus.value = Status(downloaded = true, bytes = totalBytes, message = "模型文件已校验")
            } catch (cancelled: CancellationException) {
                mutableStatus.value = Status(downloaded = isDownloaded(), message = "已暂停，可以继续下载")
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = Status(downloaded = isDownloaded(), message = "下载未完成：${error.message}")
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        val pending = connection
        scope.launch { pending?.disconnect() }
    }

    suspend fun loadModel(): Boolean {
        val paths = modelPaths() ?: return false
        return VlmEngine.load(paths.first, paths.second)
    }

    private suspend fun downloadFile(file: ModelFile, completed: Long) {
        val target = File(dir, file.name)
        val marker = File(dir, "${file.name}.verified")
        if (target.length() == file.size && marker.isFile && marker.readText() == file.sha256) return
        val part = File(dir, "${file.name}.part")
        var lastError: Exception? = null
        for (url in file.urls) {
            currentCoroutineContext().ensureActive()
            try {
                if (part.length() > file.size) check(part.delete()) { "无法重置未完成的模型" }
                if (part.length() != file.size) downloadFrom(url, file, part, completed)
                mutableStatus.value = Status(running = true, bytes = completed + part.length(), message = "正在校验模型文件")
                val coroutine = currentCoroutineContext()
                if (!ModelFiles.verify(part, file) { coroutine.ensureActive() }) {
                    part.delete()
                    error("模型校验失败，请重新下载")
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                marker.writeText(file.sha256)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载源")
    }

    private suspend fun downloadFrom(url: String, file: ModelFile, part: File, completed: Long) {
        val offset = part.length()
        val request = URL(url).openConnection() as HttpsURLConnection
        connection = request
        try {
            request.connectTimeout = 15_000
            request.readTimeout = 20_000
            request.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) request.setRequestProperty("Range", "bytes=$offset-")
            val code = request.responseCode
            check(request.url.protocol == "https") { "下载地址必须使用 HTTPS" }
            val resume = code == HttpURLConnection.HTTP_PARTIAL
            check(code == HttpURLConnection.HTTP_OK || resume) { "下载源返回 HTTP $code" }
            if (resume) check(ModelFiles.validRange(request.getHeaderField("Content-Range"), offset, file.size)) {
                "断点续传范围不匹配"
            }
            var written = if (resume) offset else 0L
            var lastEmit = 0L
            request.inputStream.buffered(256 * 1024).use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    if (resume) output.seek(offset) else output.setLength(0)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(written + count <= file.size) { "模型文件大小异常" }
                        output.write(buffer, 0, count)
                        written += count
                        val now = System.nanoTime()
                        if (now - lastEmit > 300_000_000L) {
                            mutableStatus.value = Status(running = true, bytes = completed + written, message = "正在下载")
                            lastEmit = now
                        }
                    }
                }
            }
            check(written == file.size) { "下载中断，可以继续下载" }
        } finally {
            request.disconnect()
            connection = null
        }
    }
}
