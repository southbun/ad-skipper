package com.adskipper.core.learn

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.abs

data class DemonstrationTouch(val path: Path, val x: Float, val y: Float, val durationMs: Long, val tap: Boolean)

/** This overlay explicitly records and forwards one demonstrated interaction, rather than spying on raw touches. */
class DemonstrationOverlay(private val service: AccessibilityService) {
    private var view: FrameLayout? = null
    val showing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(onTouch: (DemonstrationTouch) -> Unit, onCancel: () -> Unit): Boolean {
        if (showing) return true
        val page = FrameLayout(service)
        val cancel = Button(service).apply {
            text = "请点跳过学习\n无广告点此取消"
            textSize = 12f
            setOnClickListener { hide(); onCancel() }
        }
        val density = service.resources.displayMetrics.density
        page.addView(cancel, FrameLayout.LayoutParams((150 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (40 * density).toInt()
        })
        var path: Path? = null
        var x = 0f
        var y = 0f
        var moved = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        page.setOnTouchListener { _, event ->
            if (view !== page) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX; y = event.rawY; moved = false
                    path = Path().apply { moveTo(x, y) }
                }
                MotionEvent.ACTION_MOVE -> {
                    path?.lineTo(event.rawX, event.rawY)
                    moved = moved || abs(event.rawX - x) > slop || abs(event.rawY - y) > slop
                }
                MotionEvent.ACTION_UP -> {
                    val recorded = path
                    path = null
                    hide()
                    if (recorded != null) {
                        recorded.lineTo(event.rawX, event.rawY)
                        val duration = (event.eventTime - event.downTime).coerceIn(1L, 5_000L)
                        onTouch(DemonstrationTouch(recorded, x, y, duration, !moved && duration <= 500L))
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> { path = null; hide(); onCancel() }
                MotionEvent.ACTION_CANCEL -> { path = null }
            }
            true
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            service.getSystemService(WindowManager::class.java).addView(page, params)
            view = page
            true
        } catch (_: Exception) { false }
    }

    fun hide() {
        val old = view
        view = null
        old?.let { runCatching { service.getSystemService(WindowManager::class.java).removeView(it) } }
    }
}
