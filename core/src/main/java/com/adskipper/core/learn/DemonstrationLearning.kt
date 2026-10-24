package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** The user labels the target by tapping it; OCR and model detection are not prerequisites. */
object DemonstrationLearning {
    data class Status(val packageName: String = "", val message: String = "")
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus

    fun report(pkg: String, message: String) { mutableStatus.value = Status(pkg, message) }

    fun propose(pkg: String, version: Long, image: Bitmap, x: Float, y: Float,
                nodes: List<LearnedNode>): LearnedRule? {
        if (version < 0 || !x.isFinite() || !y.isFinite() ||
            x !in 0f..image.width.toFloat() || y !in image.height * 0.04f..image.height * 0.97f) return null
        val box = nodes.filter { it.box.contains(x.toInt(), y.toInt()) &&
            LearnedMatcher.validButton(it.box, image.width, image.height) }
            .minByOrNull { it.box.width().toLong() * it.box.height() }?.box ?: run {
                val halfWidth = maxOf(8, image.width * 6 / 100)
                val halfHeight = maxOf(6, minOf(image.width * 4 / 100, image.height * 5 / 100))
                Rect(maxOf(0, x.toInt() - halfWidth), maxOf(0, y.toInt() - halfHeight),
                    minOf(image.width, x.toInt() + halfWidth), minOf(image.height, y.toInt() + halfHeight))
            }
        if (!LearnedMatcher.validButton(box, image.width, image.height)) return null
        return LearnedMatcher.create(pkg, version, image, box, nodes).takeIf {
            LearnedMatcher.findVisual(listOf(it), image, pkg, version) != null
        }
    }

    fun confirmsExit(rule: LearnedRule, after: Bitmap, elapsedMs: Long): Boolean {
        if (elapsedMs !in 100L..2_000L || after.width != rule.width || after.height != rule.height ||
            LearnedMatcher.matchesButton(rule, after)) return false
        val page = LearnedMatcher.sample(after, Rect(0, after.height * 6 / 100,
            after.width, after.height * 94 / 100), 16, 28)
        if (page.distinct().size < 2) return false
        return rule.page.indices.count { i ->
            val a = rule.page[i]
            val b = page[i]
            (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))) / 3 > 45
        } >= page.size * 0.30f
    }
}
