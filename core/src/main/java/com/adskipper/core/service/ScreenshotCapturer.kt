package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object ScreenshotCapturer {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun capture(service: AccessibilityService): Bitmap? = mutex.withLock {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestAt
        if (elapsed < 400) delay(400 - elapsed)
        lastRequestAt = SystemClock.elapsedRealtime()
        withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(0, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var hardware: Bitmap? = null
                        val image = try {
                            hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            result.hardwareBuffer.close()
                            hardware?.recycle()
                        }
                        if (continuation.isActive) continuation.resume(image) else image?.recycle()
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }
    }
}
