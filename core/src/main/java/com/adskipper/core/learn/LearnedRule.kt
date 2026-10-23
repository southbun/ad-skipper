package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.adskipper.core.detect.NodeMatcher
import com.adskipper.core.detect.SkipSafety
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

data class LearnedNode(val viewId: String, val className: String, val label: String, val box: Rect)

data class LearnedRule(
    val id: String,
    val packageName: String,
    val version: Long,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    val box: Rect,
    val layout: String,
    val node: LearnedNode?,
    val page: List<Int>,
    val button: List<Int>,
) {
    fun valid(): Boolean = id.isNotBlank() && packageName.isNotBlank() && version >= 0 &&
        SkipSafety.isButtonBox(box.left, box.top, box.right, box.bottom, width, height) &&
        page.size == 16 * 28 && button.size == 20 * 10
}

/** Persist small visual samples and node descriptors, never a live accessibility node. */
object LearnedMatcher {
    fun nodes(root: AccessibilityNodeInfo?): List<LearnedNode> {
        root ?: return emptyList()
        val result = ArrayList<LearnedNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 300) {
            val item = queue.removeFirst()
            if (!item.isVisibleToUser || !item.isEnabled || item.packageName != root.packageName) continue
            result += LearnedNode(item.viewIdResourceName.orEmpty(), item.className?.toString().orEmpty(),
                label(item.text?.toString().orEmpty() + " " + item.contentDescription?.toString().orEmpty()),
                Rect().also(item::getBoundsInScreen))
            for (i in 0 until item.childCount) item.getChild(i)?.let(queue::addLast)
        }
        return result
    }

    fun layout(nodes: List<LearnedNode>): String = MessageDigest.getInstance("SHA-256")
        .digest(nodes.joinToString("\n") { "${it.viewId}|${it.className}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun create(pkg: String, version: Long, image: Bitmap, box: Rect, nodes: List<LearnedNode>): LearnedRule {
        require(SkipSafety.isButtonBox(box.left, box.top, box.right, box.bottom, image.width, image.height))
        val node = nodes.filter { it.viewId.isNotBlank() && it.box.contains(box.centerX(), box.centerY()) &&
            SkipSafety.isButtonBox(it.box.left, it.box.top, it.box.right, it.box.bottom, image.width, image.height) }
            .minByOrNull { it.box.width().toLong() * it.box.height() }
        return LearnedRule(UUID.randomUUID().toString(), pkg, version, System.currentTimeMillis(),
            image.width, image.height, Rect(box), layout(nodes), node,
            sample(image, pageBox(image.width, image.height), 16, 28), sample(image, box, 20, 10))
            .also { require(it.valid()) }
    }

    fun findNode(rules: List<LearnedRule>, root: AccessibilityNodeInfo, pkg: String, version: Long,
                 width: Int, height: Int): Pair<LearnedRule, Rect>? {
        if (root.packageName?.toString() != pkg || !NodeMatcher.hasAdEvidence(root)) return null
        val nodes = nodes(root)
        val layout = layout(nodes)
        return rules.asSequence().filter { it.valid() && it.packageName == pkg && it.version == version &&
            it.width == width && it.height == height && it.layout == layout && it.node != null }
            .mapNotNull { rule ->
                val key = rule.node!!
                val matches = nodes.filter { it.viewId == key.viewId && it.className == key.className &&
                    it.label == key.label && near(it.box, key.box) }
                matches.singleOrNull()?.let { rule to Rect(it.box) }
            }.firstOrNull()
    }

    fun findVisual(rules: List<LearnedRule>, image: Bitmap, pkg: String, version: Long): LearnedRule? {
        val candidates = rules.filter { it.valid() && it.packageName == pkg && it.version == version &&
            it.width == image.width && it.height == image.height }
        if (candidates.isEmpty()) return null
        val page = sample(image, pageBox(image.width, image.height), 16, 28)
        return candidates.firstOrNull { rule ->
            similar(rule.page, page, 18, 0.95f) &&
                similar(rule.button, sample(image, rule.box, 20, 10), 22, 0.94f) &&
                // A flat background is not a distinctive button template.
                distinctive(rule.button) && distinctivePage(rule.page)
        }
    }

    private fun pageBox(width: Int, height: Int) = Rect(0, height * 6 / 100, width, height * 94 / 100)
    private fun near(a: Rect, b: Rect) = abs(a.left - b.left) <= 12 && abs(a.top - b.top) <= 12 &&
        abs(a.right - b.right) <= 12 && abs(a.bottom - b.bottom) <= 12
    private fun label(value: String) = value.trim().lowercase().replace(Regex("\\d+"), "#")
        .replace(Regex("\\s+"), " ").take(160)

    internal fun sample(image: Bitmap, box: Rect, columns: Int, rows: Int): List<Int> =
        List(columns * rows) { index ->
            val x = (box.left + (index % columns + 0.5f) * box.width() / columns).toInt().coerceIn(0, image.width - 1)
            val y = (box.top + (index / columns + 0.5f) * box.height() / rows).toInt().coerceIn(0, image.height - 1)
            image.getPixel(x, y)
        }

    private fun difference(a: Int, b: Int) = (abs(Color.red(a) - Color.red(b)) +
        abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))) / 3

    private fun similar(a: List<Int>, b: List<Int>, tolerance: Int, fraction: Float): Boolean =
        a.size == b.size && a.indices.count { difference(a[it], b[it]) <= tolerance } >= a.size * fraction

    private fun distinctive(values: List<Int>): Boolean =
        values.any { difference(it, values.first()) > 45 }

    private fun distinctivePage(values: List<Int>): Boolean {
        val groups = values.groupingBy { (Color.red(it) / 32 shl 6) or
            (Color.green(it) / 32 shl 3) or (Color.blue(it) / 32) }.eachCount()
        return groups.size >= 6 && groups.values.max() < values.size * 0.85f
    }
}
