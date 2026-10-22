package com.adskipper.core.vlm

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!isReady) return@withLock null
            try {
                nativeInfer(bitmap, prompt, 64, timeoutMs).takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Local VLM inference failed")
                null
            }
        }
    }

    fun cancelInference() {
        if (linked) runCatching { nativeCancel() }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        cancelInference()
        mutex.withLock {
            if (linked) nativeRelease()
            mutableState.value = State.NOT_LOADED
        }
    }

    private external fun nativeLoadModel(modelPath: String, mmprojPath: String, threads: Int, useGpu: Boolean): Boolean
    private external fun nativeInfer(bitmap: Bitmap, prompt: String, maxTokens: Int, timeoutMs: Long): String
    private external fun nativeCancel()
    private external fun nativeRelease()
}
