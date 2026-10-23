package com.adskipper.core.learn

import android.app.Application
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.adskipper.core.detect.OcrDetector
import com.adskipper.core.detect.VlmDetector
import com.adskipper.core.model.ModelManager
import com.adskipper.core.vlm.VlmEngine
import com.google.mlkit.common.MlKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A native driver failure must not take down the accessibility service. */
class LearningWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var busy = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        when (request.what) {
            STOP -> terminate()
            ANALYZE -> if (!busy) {
                busy = true
                val reply = request.replyTo
                val id = request.data.getString("id").orEmpty()
                val path = request.data.getString("path").orEmpty()
                scope.launch {
                    fun send(what: Int, text: String, box: android.graphics.Rect? = null) {
                        runCatching { reply.send(Message.obtain(null, what).apply {
                            data = Bundle().apply {
                                putString("id", id); putString("message", text)
                                box?.let { putIntArray("box", intArrayOf(it.left, it.top, it.right, it.bottom)) }
                            }
                        }) }
                    }
                    try {
                        val file = File(path)
                        check(file.canonicalFile.parentFile == File(cacheDir, "learning").canonicalFile &&
                            file.length() in 1..16_000_000) { "采集图片无效，请重新采集" }
                        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, size)
                        check(size.outWidth.toLong() * size.outHeight in 1..16_000_000) { "图片尺寸超出限制" }
                        val image = BitmapFactory.decodeFile(path) ?: error("无法读取采集图片")
                        try {
                            send(PROGRESS, "正在本机识别跳过文字")
                            val textBox = withTimeoutOrNull(5_000L) {
                                LearnedMatcher.findTextButton(OcrDetector().recognize(image), image.width, image.height)
                            }
                            if (textBox != null) {
                                send(RESULT, "本地文字识别已定位，请确认框内是跳过按钮", textBox)
                                return@launch
                            }
                            val paths = ModelManager(this@LearningWorkerService).modelPaths()
                                ?: error("未找到唯一的跳过文字，可手动框选，或下载可选视觉模型后重试")
                            send(PROGRESS, "文字识别未定位，正在加载视觉模型（CPU）")
                            check(VlmEngine.load(paths.first, paths.second, useGpu = false)) { "学习模型加载失败" }
                            send(INFER, "AI 正在分析按钮")
                            val result = VlmDetector().analyze(image, 60_000L)
                            if (result.box == null) send(FAILURE, "${result.message}，可点预览图手动框选")
                            else send(RESULT, "请确认标出的区域确实是跳过按钮", result.box)
                        } finally { image.recycle() }
                    } catch (error: Exception) {
                        send(FAILURE, error.message ?: "学习分析失败，可手动选择按钮")
                    }
                }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        // ML Kit's manifest provider runs only in the default app process.
        MlKit.initialize(this)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { terminate(); super.onDestroy() }

    private fun terminate() {
        // This service is declared in :learning; never kill the default process.
        if (Application.getProcessName().endsWith(":learning")) Process.killProcess(Process.myPid())
    }

    companion object {
        const val ANALYZE = 1
        const val STOP = 2
        const val PROGRESS = 3
        const val INFER = 4
        const val RESULT = 5
        const val FAILURE = 6
    }
}
