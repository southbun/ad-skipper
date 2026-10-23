package com.adskipper.core.learn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object LearningController {
    enum class Phase { IDLE, ARMED, CAPTURING, CAPTURED, ANALYZING, REVIEW, SAVED, ERROR }
    data class State(val id: String = "", val packageName: String = "", val phase: Phase = Phase.IDLE,
                     val message: String = "", val imagePath: String? = null, val box: Rect? = null,
                     val inferenceCount: Int = 0, val elapsedMs: Long = 0)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeout: Job? = null
    private var connection: ServiceConnection? = null
    private var worker: Messenger? = null
    private var boundContext: Context? = null
    private var nodes: List<LearnedNode> = emptyList()
    private var version = -1L

    fun collecting(pkg: String): Boolean = state.value.packageName == pkg &&
        state.value.phase in setOf(Phase.ARMED, Phase.CAPTURING)

    fun arm(pkg: String) {
        discard()
        val id = UUID.randomUUID().toString()
        mutableState.value = State(id, pkg, Phase.ARMED, "打开目标应用，广告出现后点击“采集当前广告”浮窗")
        timeout = scope.launch {
            delay(60_000)
            if (state.value.id == id && state.value.phase == Phase.ARMED) fail("等待采集已超时，请重新开始")
        }
    }

    fun capturing(): String? {
        val current = state.value
        if (current.phase != Phase.ARMED) return null
        timeout?.cancel()
        mutableState.value = current.copy(phase = Phase.CAPTURING, message = "正在采集当前广告")
        return current.id
    }

    suspend fun captured(context: Context, id: String, image: Bitmap, capturedNodes: List<LearnedNode>, appVersion: Long) {
        if (state.value.id != id || state.value.phase != Phase.CAPTURING) return
        val file = File(context.cacheDir, "learning/$id.png")
        try {
            withContext(Dispatchers.IO) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } catch (error: Exception) { file.delete(); throw error }
        if (state.value.id != id || state.value.phase != Phase.CAPTURING) { file.delete(); return }
        nodes = capturedNodes
        version = appVersion
        mutableState.value = state.value.copy(phase = Phase.CAPTURED, imagePath = file.absolutePath,
            message = "已采集，请返回学习页分析。图片仅在本机临时保存")
    }

    fun analyze(context: Context) {
        if (state.value.imagePath == null || state.value.phase == Phase.ANALYZING) return
        val current = state.value.copy(id = UUID.randomUUID().toString())
        stopWorker()
        val app = context.applicationContext
        val started = SystemClock.elapsedRealtime()
        mutableState.value = current.copy(phase = Phase.ANALYZING, box = null,
            message = "正在启动学习进程，首次分析可能需要几十秒", inferenceCount = 0, elapsedMs = 0)
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            receive(current.id, message, SystemClock.elapsedRealtime() - started)
            true
        })
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (state.value.id != current.id || state.value.phase != Phase.ANALYZING) return
                worker = Messenger(binder)
                try {
                    worker!!.send(Message.obtain(null, LearningWorkerService.ANALYZE).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("id", current.id); putString("path", current.imagePath) }
                    })
                } catch (_: Exception) { fail("学习进程连接失败，基础跳过继续运行") }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (state.value.id == current.id && state.value.phase == Phase.ANALYZING)
                    fail("学习进程已退出，可重试或手动选择；基础跳过继续运行")
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        connection = link
        boundContext = app
        try {
            check(app.bindService(Intent(app, LearningWorkerService::class.java), link, Context.BIND_AUTO_CREATE))
            timeout = scope.launch {
                repeat(90) {
                    delay(1_000)
                    if (state.value.id != current.id || state.value.phase != Phase.ANALYZING) return@launch
                    mutableState.value = state.value.copy(elapsedMs = SystemClock.elapsedRealtime() - started)
                }
                if (state.value.id == current.id && state.value.phase == Phase.ANALYZING)
                    fail("学习分析超时，可手动选择按钮")
            }
        } catch (_: Exception) { fail("无法启动学习进程，可手动选择按钮") }
    }

    internal fun receive(id: String, message: Message, elapsed: Long) {
        val current = state.value
        if (current.id != id || message.data.getString("id") != id || current.phase != Phase.ANALYZING) return
        val text = message.data.getString("message").orEmpty()
        when (message.what) {
            LearningWorkerService.PROGRESS -> mutableState.value = current.copy(message = text, elapsedMs = elapsed)
            LearningWorkerService.INFER -> mutableState.value = current.copy(message = text, inferenceCount = 1, elapsedMs = elapsed)
            LearningWorkerService.RESULT -> {
                val values = message.data.getIntArray("box")
                val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(current.imagePath, size)
                val box = values?.takeIf { it.size == 4 }?.let { Rect(it[0], it[1], it[2], it[3]) }
                if (box == null || !LearnedMatcher.validButton(box, size.outWidth, size.outHeight)) {
                    fail("AI 返回的位置无效，请手动选择")
                } else {
                    mutableState.value = current.copy(phase = Phase.REVIEW, box = box, message = text, elapsedMs = elapsed)
                    stopWorker()
                }
            }
            LearningWorkerService.FAILURE -> fail(text)
        }
    }

    fun select(box: Rect, width: Int, height: Int) {
        if (state.value.imagePath == null) return
        if (!LearnedMatcher.validButton(box, width, height)) {
            mutableState.value = state.value.copy(message = "框选范围过大或无效，请只圈住跳过按钮")
            return
        }
        stopWorker()
        mutableState.value = state.value.copy(phase = Phase.REVIEW, box = Rect(box), message = "请确认框内只有跳过按钮，然后保存")
    }

    suspend fun save(context: Context) {
        val current = state.value
        val box = current.box ?: return
        if (current.phase != Phase.REVIEW) return
        val image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(current.imagePath) }
            ?: return fail("采集图片已丢失，请重新采集")
        try {
            val rule = withContext(Dispatchers.Default) {
                LearnedMatcher.create(current.packageName, version, image, box, nodes)
            }
            withContext(Dispatchers.IO) { LearnedRuleRepository.get(context).save(rule) }
            if (state.value.id == current.id) {
                discard()
                mutableState.value = current.copy(phase = Phase.SAVED, imagePath = null, box = null,
                    message = "规则已保存，下次打开会优先匹配；应用更新或画面变化时会停止命中")
            }
        } catch (error: Exception) { fail("保存失败：${error.message}") }
        finally { image.recycle() }
    }

    fun fail(message: String) {
        mutableState.value = state.value.copy(phase = Phase.ERROR, message = message)
        stopWorker()
    }

    fun discard() {
        stopWorker()
        state.value.imagePath?.let { File(it).delete() }
        nodes = emptyList()
        version = -1
        mutableState.value = State()
    }

    private fun stopWorker() {
        timeout?.cancel(); timeout = null
        runCatching { worker?.send(Message.obtain(null, LearningWorkerService.STOP)) }
        worker = null
        connection?.let { link -> runCatching { boundContext?.unbindService(link) } }
        connection = null; boundContext = null
    }
}
