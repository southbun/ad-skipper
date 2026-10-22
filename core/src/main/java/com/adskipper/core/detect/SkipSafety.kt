package com.adskipper.core.detect

import kotlin.math.abs

object SkipSafety {
    fun isCorner(x: Float, y: Float, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && x.isFinite() && y.isFinite() &&
            x in 0f..width.toFloat() && y in 0f..height.toFloat() &&
            (x <= width * 0.30f || x >= width * 0.70f) &&
            (y <= height * 0.25f || y >= height * 0.85f)

    fun sameWindow(expectedPackage: String, expectedId: Int, actualPackage: String?, actualId: Int): Boolean =
        expectedPackage == actualPackage && expectedId == actualId

    fun sameTarget(first: SkipTarget, second: SkipTarget): Boolean =
        first.layer == second.layer &&
            abs(first.x - second.x) <= 24f && abs(first.y - second.y) <= 24f

    fun isOcrCandidate(line: OcrLine, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || line.width <= 0 || line.height <= 0 ||
            line.width > width * 0.4f || line.height > height * 0.12f ||
            line.centerX - line.width / 2 < 0 || line.centerY - line.height / 2 < 0 ||
            line.centerX + line.width / 2 > width || line.centerY + line.height / 2 > height) return false
        val corner = isCorner(line.centerX.toFloat(), line.centerY.toFloat(), width, height)
        val edgeCountdown = KeywordMatcher.countdown(line.text) != null &&
            (line.centerX <= width * 0.3f || line.centerX >= width * 0.7f)
        return corner || edgeCountdown
    }

    fun canConfirmTarget(first: SkipTarget, second: SkipTarget, elapsedMs: Long): Boolean {
        if (!sameTarget(first, second)) return false
        if (!first.requiresCountdownProof && !second.requiresCountdownProof) return true
        if (elapsedMs !in 500L..2_500L) return false
        val before = first.countdown ?: return false
        val after = second.countdown ?: return false
        return before in 1..30 && after in 0 until before &&
            before - after <= (elapsedMs + 999L) / 1_000L + 1
    }
}
