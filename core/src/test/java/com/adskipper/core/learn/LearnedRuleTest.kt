package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.adskipper.core.detect.OcrLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class LearnedRuleTest {
    private val pkg = "test.ad.app"
    private val box = Rect(240, 55, 290, 85)
    private fun image() = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width)
            setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, (x + y) % 256))
        for (y in box.top until box.bottom) for (x in box.left until box.right)
            setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE)
    }
    private fun rule(image: Bitmap = image(), nodes: List<LearnedNode> = emptyList()) =
        LearnedMatcher.create(pkg, 7, image, box, nodes)

    @Test fun aConfirmedAdCanMatchOnTheNextLaunch() {
        val image = image()
        val rule = rule(image)
        assertEquals(rule.id, LearnedMatcher.findVisual(listOf(rule), image, pkg, 7)?.id)
    }

    @Test fun aSmallCountdownChangeKeepsTheButtonTemplate() {
        val before = image()
        val rule = rule(before)
        before.setPixel(280, 60, Color.RED)
        assertNotNull(LearnedMatcher.findVisual(listOf(rule), before, pkg, 7))
    }

    @Test fun changedPageWithTheSameButtonIsRejected() {
        val original = image()
        val rule = rule(original)
        val changed = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in box.top until box.bottom) for (x in box.left until box.right)
            changed.setPixel(x, y, original.getPixel(x, y))
        assertNull(LearnedMatcher.findVisual(listOf(rule), changed, pkg, 7))
    }

    @Test fun changedButtonWithTheSamePageIsRejected() {
        val image = image()
        val rule = rule(image)
        for (y in box.top until box.bottom) for (x in box.left until box.right) image.setPixel(x, y, Color.RED)
        assertNull(LearnedMatcher.findVisual(listOf(rule), image, pkg, 7))
    }

    @Test fun rulesAreBoundToPackageVersionAndDisplaySize() {
        val image = image()
        val rule = rule(image)
        assertNull(LearnedMatcher.findVisual(listOf(rule), image, "other.app", 7))
        assertNull(LearnedMatcher.findVisual(listOf(rule), image, pkg, 8))
        assertNull(LearnedMatcher.findVisual(listOf(rule), Bitmap.createScaledBitmap(image, 640, 1280, false), pkg, 7))
    }

    @Test fun aBottomSplashButtonAboveTheNavigationAreaIsValid() {
        assertTrue(LearnedMatcher.validButton(Rect(989, 2421, 1148, 2514), 1200, 2608))
        val rule = LearnedMatcher.create(pkg, 7, image(), Rect(262, 594, 308, 620), emptyList())
        assertTrue(rule.valid())
    }

    @Test fun theCapturedBottomSkipTextProducesAReviewBox() {
        val lines = listOf(OcrLine("跳转至淘宝享双11优惠", 600, 2290, 471, 45),
            OcrLine("跳过", 1068, 2467, 113, 47))
        val candidate = LearnedMatcher.findTextButton(lines, 1200, 2608)
        assertNotNull(candidate)
        assertTrue(candidate!!.contains(1068, 2467))
        assertTrue(LearnedMatcher.validButton(candidate, 1200, 2608))
    }

    @Test fun ambiguousSkipTextNeverChoosesTheFirstButton() {
        assertNull(LearnedMatcher.findTextButton(listOf(OcrLine("跳过", 1068, 2467, 113, 47),
            OcrLine("Skip", 1068, 200, 113, 47)), 1200, 2608))
    }

    @Test fun normalBusinessTextAndSystemStripTextAreNotLearningCandidates() {
        for (line in listOf(OcrLine("跳过登录", 1068, 200, 113, 47),
                OcrLine("点击跳过，立即购买", 1068, 200, 113, 47),
                OcrLine("跳过", 1068, 2570, 113, 47)))
            assertNull(LearnedMatcher.findTextButton(listOf(line), 1200, 2608))
    }

    @Test fun aCountdownLabelIsAcceptedWithoutLoadingTheLargeModel() {
        assertNotNull(LearnedMatcher.findTextButton(listOf(OcrLine("跳过 2", 1068, 200, 113, 47)), 1200, 2608))
    }

    @Test fun flatPagesAndFlatButtonRegionsCannotBecomeVisualMatches() {
        val image = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val flat = rule(image)
        assertNull(LearnedMatcher.findVisual(listOf(flat), image, pkg, 7))
        for (y in box.top until box.bottom) for (x in box.left until box.right)
            image.setPixel(x, y, if (x % 2 == 0) Color.WHITE else Color.BLACK)
        assertNull(LearnedMatcher.findVisual(listOf(rule(image)), image, pkg, 7))
    }

    @Test fun invalidOrOversizedBoxesAreRejectedAtCreation() {
        for (bounds in listOf(Rect(0, 0, 320, 640), Rect(-1, 20, 30, 40), Rect(10, 10, 10, 20),
                Rect(20, 0, 60, 20), Rect(120, 615, 180, 635))) {
            assertThrows(IllegalArgumentException::class.java) { LearnedMatcher.create(pkg, 7, image(), bounds, emptyList()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun node(bounds: Rect, id: String = "", text: String = "") = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg; className = "android.widget.Button"; viewIdResourceName = id
        this.text = text; isVisibleToUser = true; isEnabled = true; setBoundsInScreen(bounds)
    }
    private fun root(ad: Boolean = true, text: String = "×", buttonBox: Rect = box): AccessibilityNodeInfo = node(Rect(0, 0, 320, 640)).apply {
        shadowOf(this).addChild(node(buttonBox, "$pkg:id/close_ad", text))
        shadowOf(this).addChild(node(Rect(0, 500, 80, 530), text = if (ad) "广告" else "首页"))
    }

    @Test fun aSavedNodeCanMatchWithFreshAdEvidence() {
        val root = root()
        val rule = rule(nodes = LearnedMatcher.nodes(root))
        assertNotNull(rule.node)
        assertEquals(box, LearnedMatcher.findNode(listOf(rule), root, pkg, 7, 320, 640)?.second)
    }

    @Test fun theSameNodeIdOnANormalPageDoesNotMatch() {
        val rule = rule(nodes = LearnedMatcher.nodes(root()))
        assertNull(LearnedMatcher.findNode(listOf(rule), root(ad = false), pkg, 7, 320, 640))
    }

    @Test fun changedLabelLayoutOrNodePositionRejectsFastReuse() {
        val rule = rule(nodes = LearnedMatcher.nodes(root()))
        assertNull(LearnedMatcher.findNode(listOf(rule), root(text = "购买"), pkg, 7, 320, 640))
        val changed = root()
        shadowOf(changed).addChild(node(Rect(0, 300, 100, 400), "new_id"))
        assertNull(LearnedMatcher.findNode(listOf(rule), changed, pkg, 7, 320, 640))
        val moved = root(buttonBox = Rect(10, 55, 60, 85))
        assertNull(LearnedMatcher.findNode(listOf(rule), moved, pkg, 7, 320, 640))
    }
}
