package com.adskipper.core.learn

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button

class LearningOverlay(private val service: AccessibilityService) {
    private var view: Button? = null
    fun show(onCapture: () -> Unit) {
        if (view != null) return
        val button = Button(service).apply {
            text = "采集当前广告"
            setOnClickListener { hide(); onCapture() }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; x = 12 }
        try {
            service.getSystemService(WindowManager::class.java).addView(button, params)
            view = button
        } catch (_: Exception) { LearningController.fail("无法显示采集浮窗，请检查无障碍服务") }
    }
    fun hide() {
        view?.let { runCatching { service.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }
}
