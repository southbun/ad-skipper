package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** A slow model's coordinates are used only while its button region and page still match. */
object FrameVerifier {
    fun matches(before: Bitmap, after: Bitmap, target: SkipTarget): Boolean {
        val width = before.width
        val height = before.height
        if (width != after.width || height != after.height || !target.x.isFinite() || !target.y.isFinite() ||
            target.x < 0 || target.x >= width || target.y < 0 || target.y >= height) return false
        fun changed(x: Int, y: Int): Boolean {
            val a = before.getPixel(x, y)
            val b = after.getPixel(x, y)
            return (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))) / 3 > 45
        }
        var localChanges = 0
        for (row in 0..6) for (col in 0..10) {
            val x = (target.x + (col - 5) * width * 0.024f).toInt().coerceIn(0, width - 1)
            val y = (target.y + (row - 3) * height * 0.012f).toInt().coerceIn(0, height - 1)
            if (changed(x, y)) localChanges++
        }
        if (localChanges > 15) return false
        var pageChanges = 0
        for (row in 0 until 24) for (col in 0 until 12) {
            if (changed((col * width + width / 2) / 12, (row * height + height / 2) / 24)) pageChanges++
        }
        return pageChanges <= 144
    }
}
