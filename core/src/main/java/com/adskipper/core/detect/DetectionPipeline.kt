package com.adskipper.core.detect

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.adskipper.core.data.AppSettings

/** OCR countdown candidates require a same-window countdown decrease before clicking. */
class DetectionPipeline<I>(
    private val l1: suspend (AccessibilityNodeInfo?, Collection<String>) -> Pair<Float, Float>?,
    private val l2: suspend (I, Collection<String>) -> OcrPass,
    private val l3yolo: suspend (I) -> Pair<Float, Float>? = { null },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    data class OcrPass(val hit: Pair<Float, Float>?, val lines: List<OcrLine> = emptyList(),
                       val requiresCountdownProof: Boolean = false) {
        val countdown: Int? get() {
            val point = hit ?: return null
            return lines.firstOrNull {
                it.centerX.toFloat() == point.first && it.centerY.toFloat() == point.second
            }?.let { KeywordMatcher.countdown(it.text) }
        }
    }
    data class Result(val target: SkipTarget?, val timings: List<Pair<SkipLayer, Long>>,
                      val missReason: String = "未找到符合条件的跳过按钮")

    suspend fun detect(
        root: AccessibilityNodeInfo?,
        screenshot: suspend () -> I?,
        settings: AppSettings,
        nodeAdEvidence: Boolean = false,
    ): Result {
        val timings = ArrayList<Pair<SkipLayer, Long>>(3)
        if (settings.layer1Enabled && nodeAdEvidence) {
            val started = clock()
            val hit = l1(root, settings.keywords)
            timings += SkipLayer.L1_NODE to (clock() - started)
            if (hit != null) return Result(SkipTarget(hit.first, hit.second, SkipLayer.L1_NODE), timings)
        }
        if (!settings.layer2Enabled && !settings.layer3Enabled) return Result(null, timings)
        val image = screenshot() ?: return Result(null, timings, "无法读取画面，已保留控件识别")
        var adEvidence = nodeAdEvidence
        if (settings.layer2Enabled) {
            val started = clock()
            val pass = l2(image, settings.keywords)
            adEvidence = adEvidence || pass.lines.any { KeywordMatcher.isAdEvidence(it.text) }
            timings += SkipLayer.L2_OCR to (clock() - started)
            if (pass.hit != null && (adEvidence || pass.countdown != null)) {
                return Result(SkipTarget(pass.hit.first, pass.hit.second, SkipLayer.L2_OCR,
                    countdown = pass.countdown,
                    requiresCountdownProof = pass.requiresCountdownProof || !adEvidence), timings)
            }
        }
        if (settings.layer3Enabled && adEvidence) {
            val started = clock()
            val hit = l3yolo(image)
            timings += SkipLayer.L3_YOLO to (clock() - started)
            if (hit != null) return Result(SkipTarget(hit.first, hit.second, SkipLayer.L3_YOLO), timings)
        }
        return Result(null, timings, if (adEvidence) "未找到符合条件的跳过按钮" else "未识别到广告标识或有效倒计时")
    }

    companion object {
        fun create(yolo: YoloSkipDetector?, excluded: Collection<String>): DetectionPipeline<Bitmap> {
            val ocr = OcrDetector()
            return DetectionPipeline(
                l1 = { root, keywords ->
                    NodeMatcher.findSkipNode(root, keywords, excluded)
                        ?.let { it.exactCenterX() to it.exactCenterY() }
                },
                l2 = { bitmap, keywords ->
                    val lines = ocr.recognize(bitmap)
                    val hit = lines.filter {
                        SkipSafety.isOcrCandidate(it, bitmap.width, bitmap.height)
                    }.let { KeywordMatcher.findSkipLine(it, keywords, excluded) }
                    OcrPass(hit?.let { it.centerX.toFloat() to it.centerY.toFloat() }, lines,
                        requiresCountdownProof = hit != null && !SkipSafety.isCorner(
                            hit.centerX.toFloat(), hit.centerY.toFloat(), bitmap.width, bitmap.height))
                },
                l3yolo = { bitmap ->
                    yolo?.findSkipButton(bitmap)?.takeIf {
                        !it.isEmpty && it.width() <= bitmap.width * 0.4f &&
                            it.height() <= bitmap.height * 0.12f &&
                            SkipSafety.isCorner(it.exactCenterX(), it.exactCenterY(), bitmap.width, bitmap.height)
                    }?.let { it.exactCenterX() to it.exactCenterY() }
                },
            )
        }
    }
}
