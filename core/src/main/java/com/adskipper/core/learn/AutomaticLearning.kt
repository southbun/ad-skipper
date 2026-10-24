package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import com.adskipper.core.detect.KeywordMatcher
import com.adskipper.core.detect.OcrLine
import com.adskipper.core.detect.SkipLayer
import com.adskipper.core.detect.SkipTarget
import kotlin.math.abs

/** Automatic rules require an unambiguous text button and a verified exit from the ad. */
object AutomaticLearning {
    private val labels = listOf("跳过", "跳过广告", "关闭广告", "Skip", "Skip Ad", "skip_ad")

    fun propose(pkg: String, version: Long, image: Bitmap, target: SkipTarget,
                lines: List<OcrLine>, nodes: List<LearnedNode>, nodeAdEvidence: Boolean): LearnedRule? {
        if (version < 0 || !target.x.isFinite() || !target.y.isFinite() ||
            target.layer !in setOf(SkipLayer.L1_NODE, SkipLayer.L2_OCR)) return null
        val textBox = LearnedMatcher.findTextButton(lines, image.width, image.height) ?: return null
        val label = lines.singleOrNull { textBox.contains(it.centerX, it.centerY) &&
            KeywordMatcher.matches(it.text, labels) != null }
            ?: return null
        val box = if (textBox.contains(target.x.toInt(), target.y.toInt())) textBox else
            nodes.filter { LearnedMatcher.validButton(it.box, image.width, image.height) &&
                it.box.contains(target.x.toInt(), target.y.toInt()) && it.box.contains(label.centerX, label.centerY) }
                .minByOrNull { it.box.width().toLong() * it.box.height() }?.box ?: return null
        val countdown = KeywordMatcher.countdown(label.text)
        // A nearly finished countdown cannot distinguish our click from natural dismissal.
        if (countdown != null && countdown < 3) return null
        if (!nodeAdEvidence && lines.none { KeywordMatcher.isAdEvidence(it.text) } &&
            !(target.requiresCountdownProof && countdown != null)) return null
        return LearnedMatcher.create(pkg, version, image, box, nodes).takeIf {
            (it.node != null && nodeAdEvidence) || LearnedMatcher.findVisual(listOf(it), image, pkg, version) != null
        }
    }

    fun confirmsExit(rule: LearnedRule, after: Bitmap, lines: List<OcrLine>,
                     nodeAdEvidence: Boolean, elapsedMs: Long): Boolean {
        if (elapsedMs !in 100L..2_000L || after.width != rule.width || after.height != rule.height ||
            lines.isEmpty() || nodeAdEvidence || lines.any { KeywordMatcher.isAdEvidence(it.text) } ||
            lines.any { KeywordMatcher.matches(it.text, labels) != null }) return false
        val page = LearnedMatcher.sample(after,
            android.graphics.Rect(0, after.height * 6 / 100, after.width, after.height * 94 / 100), 16, 28)
        val changed = rule.page.indices.count { i ->
            val a = rule.page[i]
            val b = page[i]
            (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))) / 3 > 45
        }
        return changed >= page.size * 0.30f
    }
}
