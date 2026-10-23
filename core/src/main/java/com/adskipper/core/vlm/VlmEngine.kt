package com.adskipper.core.vlm

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One process-wide model; loading, inference and release never overlap. */
object VlmEngine {
    enum class State { NOT_LOADED, LOADING, READY, ERROR }
    private val mutableState = MutableStateFlow(State.NOT_LOADED)
    val state: StateFlow<State> = mutableState
    val isReady: Boolean get() = mutableState.value == State.READY
    data class InferenceStatus(val count: Int = 0, val message: String = "尚未运行识别", val elapsedMs: Long = 0)
    private val mutableInferenceStatus = MutableStateFlow(InferenceStatus())
    val inferenceStatus: StateFlow<InferenceStatus> = mutableInferenceStatus
    private val mutex = Mutex()
    @Volatile private var linked = false

    suspend fun load(modelPath: String, mmprojPath: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady) return@withLock true
            mutableState.value = State.LOADING
            val loaded = try {
                if (!linked) {
                    System.loadLibrary("vlm_jni")
                    linked = true
                }
                val threads = minOf(4, Runtime.getRuntime().availableProcessors())
                nativeLoadModel(modelPath, mmprojPath, threads, true) ||
                    nativeLoadModel(modelPath, mmprojPath, threads, false)
            } catch (cancelled: CancellationException) {
                mutableState.value = State.NOT_LOADED
                throw cancelled
            } catch (error: LinkageError) {
                Timber.e(error, "Local VLM library unavailable")
                false
            } catch (error: Exception) {
                Timber.e(error, "Local VLM load failed")
                false
            }
            mutableState.value = if (loaded) State.READY else State.ERROR
            loaded
        }
    }

    suspend fun infer(bitmap: Bitmap, prompt: String, timeoutMs: Long): String? = withContext(Dispatchers.Default) {
        if (!isReady || timeoutMs <= 0) return@withContext null
        if (mutex.isLocked) {
            mutableInferenceStatus.update { it.copy(message = "等待上一轮识别结束") }
        }
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!isReady) return@withLock null
            val count = mutableInferenceStatus.value.count + 1
            val started = SystemClock.elapsedRealtime()
            var message = "识别未完成"
            mutableInferenceStatus.value = InferenceStatus(count, "正在识别")
            try {
                val output = nativeInfer(bitmap, prompt, 64, timeoutMs)
                currentCoroutineContext().ensureActive()
                message = when {
                    output.isBlank() && SystemClock.elapsedRealtime() - started >= timeoutMs -> "识别超时"
                    output.isBlank() -> "未返回完整结果"
                    BboxParser.parse(output) == null -> "未找到有效按钮"
                    else -> "已返回按钮位置，等待画面核验"
                }
                output.takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                message = if (SystemClock.elapsedRealtime() - started >= timeoutMs) "识别超时" else "本轮已取消"
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = State.ERROR
                message = "模型运行失败，基础识别继续工作"
                Timber.w(error, "Local VLM inference failed")
                null
            } finally {
                mutableInferenceStatus.value = InferenceStatus(count, message, SystemClock.elapsedRealtime() - started)
            }
        }
    }

    fun cancelInference() {
        if (linked) runCatching { nativeCancel() }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        cancelInference()
        mutex.withLock {
            try {
                if (linked) nativeRelease()
            } catch (error: Exception) {
                Timber.w(error, "Local VLM release failed")
            } finally {
                mutableState.value = State.NOT_LOADED
            }
        }
    }

    private external fun nativeLoadModel(modelPath: String, mmprojPath: String, threads: Int, useGpu: Boolean): Boolean
    private external fun nativeInfer(bitmap: Bitmap, prompt: String, maxTokens: Int, timeoutMs: Long): String
    private external fun nativeCancel()
    private external fun nativeRelease()
}
