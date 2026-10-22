package com.adskipper.core.vlm

import android.graphics.Rect

/** InternVL's native xyxy coordinates, explicitly requested on a 0..1000 scale. */
object BboxParser {
    private val response = Regex("""\{\s*"bbox_2d"\s*:\s*\[([^\[\]]+)]\s*}""")

    fun parse(text: String): FloatArray? {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val body = response.matchEntire(clean)?.groupValues?.get(1) ?: return null
        val numbers = body.split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (numbers.size != 4 || numbers.any { !it.isFinite() || it !in 0f..1000f } ||
            numbers[0] >= numbers[2] || numbers[1] >= numbers[3]) return null
        return FloatArray(4) { numbers[it] / 1000f }
    }

    fun toPixelRect(norm: FloatArray, width: Int, height: Int): Rect = Rect(
        (norm[0] * width).toInt(), (norm[1] * height).toInt(),
        (norm[2] * width).toInt(), (norm[3] * height).toInt(),
    )
}
