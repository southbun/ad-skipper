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
}
