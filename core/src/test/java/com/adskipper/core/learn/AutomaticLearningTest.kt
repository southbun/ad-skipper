package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.adskipper.core.detect.OcrLine
import com.adskipper.core.detect.SkipLayer
import com.adskipper.core.detect.SkipTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class AutomaticLearningTest {
    @get:Rule val temp = TemporaryFolder()
    private val pkg = "test.ad.app"
    private val target = SkipTarget(265f, 70f, SkipLayer.L2_OCR)
    private val skip = OcrLine("跳过", 265, 70, 40, 16)
    private val badge = OcrLine("广告", 25, 70, 30, 16)
    private val home = listOf(OcrLine("我的首页", 160, 100, 80, 20))
    private fun ad() = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width)
            setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, (x + y) % 256))
        for (y in 54 until 86) for (x in 237 until 293)
            setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE)
    }
    private fun changed() = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
    private fun propose(lines: List<OcrLine> = listOf(skip, badge), hit: SkipTarget = target,
                        nodes: List<LearnedNode> = emptyList(), nodeEvidence: Boolean = false) =
        AutomaticLearning.propose(pkg, 7, ad(), hit, lines, nodes, nodeEvidence)

    @Test fun verifiedTextCanBeSavedAndReusedWithoutManualConfirmation() {
        val image = ad()
        val rule = AutomaticLearning.propose(pkg, 7, image, target, listOf(skip, badge), emptyList(), false)!!
        assertTrue(AutomaticLearning.confirmsExit(rule, changed(), home, false, 900))
        val file = java.io.File(temp.root, "rules.json")
        LearnedRuleRepository(file).save(rule)
        val saved = LearnedRuleRepository(file).rules.value
        assertEquals(rule.id, LearnedMatcher.findVisual(saved, image, pkg, 7)?.id)
        assertNull(LearnedMatcher.findVisual(saved, image, "another.app", 7))
        assertNull(LearnedMatcher.findVisual(saved, image, pkg, 8))
    }

    @Test fun aBareSkipOnANormalPageCannotBecomeAnAutomaticRule() {
        assertNull(propose(listOf(skip)))
        assertNull(propose(listOf(skip.copy(text = "跳过教程"), badge)))
        assertNull(propose(listOf(skip.copy(text = "×"), badge)))
        assertNull(propose(listOf(skip, badge), target.copy(layer = SkipLayer.L3_YOLO)))
        assertNull(propose(listOf(skip, badge), target.copy(layer = SkipLayer.L3_VLM)))
        assertNull(propose(listOf(skip, badge), target.copy(layer = SkipLayer.L0_LEARNED)))
    }

    @Test fun aProvenCountdownCanSupplyTheAdContext() {
        assertNotNull(propose(listOf(skip.copy(text = "跳过 5")),
            target.copy(countdown = 5, requiresCountdownProof = true)))
        assertNull(propose(listOf(skip.copy(text = "跳过 5"))))
    }

    @Test fun aCountdownNearNaturalExpiryIsNotLearnedEvenWithAnAdBadge() {
        for (seconds in 0..2) assertNull(propose(listOf(skip.copy(text = "跳过 $seconds"), badge)))
        assertNotNull(propose(listOf(skip.copy(text = "跳过 3"), badge)))
    }

    @Test fun ambiguityOrAChangedClickPositionCannotChooseAnArbitraryRule() {
        assertNull(propose(listOf(skip, badge, skip.copy(text = "Skip", centerY = 200))))
        assertNull(propose(hit = target.copy(x = 150f)))
        assertNull(propose(hit = target.copy(x = Float.NaN)))
    }

    @Test fun aClickableParentCanContainAnOffCenterTextLabel() {
        val node = LearnedNode("test:id/skip", "android.widget.Button", "跳过", Rect(220, 50, 310, 90))
        val rule = propose(hit = target.copy(x = 300f, layer = SkipLayer.L1_NODE), nodes = listOf(node))
        assertNotNull(rule)
        assertEquals(node.box, rule!!.box)
    }

    @Test fun nodeAdEvidenceCanConfirmAnOcrTextButton() {
        assertNotNull(propose(listOf(skip), nodeEvidence = true))
    }

    @Test fun aFeaturelessImageWithoutANodeIdCannotCreateAnUnusableRule() {
        assertNull(AutomaticLearning.propose(pkg, 7, changed(), target, listOf(skip, badge), emptyList(), false))
        val node = LearnedNode("test:id/skip", "android.widget.Button", "跳过", Rect(220, 50, 310, 90))
        assertNull(AutomaticLearning.propose(pkg, 7, changed(), target, listOf(skip, badge), listOf(node), false))
        assertNotNull(AutomaticLearning.propose(pkg, 7, changed(), target, listOf(skip, badge), listOf(node), true))
    }

    @Test fun disappearingTextAloneDoesNotConfirmAnExit() {
        val rule = propose()!!
        assertFalse(AutomaticLearning.confirmsExit(rule, ad(), home, false, 900))
    }

    @Test fun remainingButtonsAdEvidenceAndUnreadableFramesRejectTheRule() {
        val rule = propose()!!
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), home + skip, false, 900))
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), home + skip + skip.copy(centerY = 200), false, 900))
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), home + badge, false, 900))
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), home, true, 900))
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), emptyList(), false, 900))
    }

    @Test fun lateOrResizedFramesCannotConfirmTheClick() {
        val rule = propose()!!
        assertFalse(AutomaticLearning.confirmsExit(rule, changed(), home, false, 2_001))
        assertFalse(AutomaticLearning.confirmsExit(rule,
            Bitmap.createBitmap(640, 1280, Bitmap.Config.ARGB_8888), home, false, 900))
    }
}
