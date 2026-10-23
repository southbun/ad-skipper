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
import com.adskipper.core.detect.VlmDetector
import com.adskipper.core.model.ModelManager
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
                        val paths = ModelManager(this@LearningWorkerService).modelPaths()
                            ?: error("请先在本地 AI 页面下载模型")
                        send(PROGRESS, "正在加载学习模型（CPU）")
                        check(VlmEngine.load(paths.first, paths.second, useGpu = false)) { "学习模型加载失败" }
                        val image = BitmapFactory.decodeFile(path) ?: error("无法读取采集图片")
                        try {
                            send(INFER, "AI 正在分析按钮")
                            val box = VlmDetector().findSkipButton(image, 60_000L)
                            if (box == null) send(FAILURE, "AI 未找到按钮，可点预览图手动选择")
                            else send(RESULT, "请确认标出的区域确实是跳过按钮", box)
                        } finally { image.recycle() }
                    } catch (error: Exception) {
                        send(FAILURE, error.message ?: "学习分析失败，可手动选择按钮")
                    }
                }
            }
        }
        true
    })

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
