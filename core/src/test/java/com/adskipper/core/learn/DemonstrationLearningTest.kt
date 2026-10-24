package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class DemonstrationLearningTest {
    private val pkg = "test.canvas.ad"
    private fun image(x: Int = 265, y: Int = 85) = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
        for (row in 0 until height) for (column in 0 until width)
            setPixel(column, row, Color.rgb(column * 255 / width, row * 255 / height, (column + row) % 256))
        for (row in y - 20 until y + 20) for (column in x - 32 until x + 32)
            setPixel(column, row, if ((column / 3 + row / 3) % 2 == 0) Color.BLACK else Color.WHITE)
    }
    private fun rule(image: Bitmap = image(), x: Float = 265f, y: Float = 85f) =
        DemonstrationLearning.propose(pkg, 7, image, x, y, emptyList())!!

    @Test fun aHumanTapLabelsACanvasButtonWithoutTextNodesOrOcr() {
        val source = image()
        val rule = rule(source)
        assertNull(rule.node)
        assertEquals(265, rule.box.centerX())
        assertEquals(85, rule.box.centerY())
        assertEquals(rule.id, LearnedMatcher.findVisual(listOf(rule), source, pkg, 7)?.id)
    }

    @Test fun oneAppCanLearnBothPositionsAndReuseTheMatchingOne() {
        val upper = image()
        val lower = image(265, 540)
        val first = rule(upper)
        val second = rule(lower, 265f, 540f)
        assertEquals(first.id, LearnedMatcher.findVisual(listOf(first, second), upper, pkg, 7)?.id)
        assertEquals(second.id, LearnedMatcher.findVisual(listOf(first, second), lower, pkg, 7)?.id)
        assertNull(LearnedMatcher.findVisual(listOf(first), lower, pkg, 7))
        assertFalse(LearnedMatcher.sameTemplate(first, second))
    }

    @Test fun theSmallTappedControlSuppliesTheButtonBoundsWithoutKeywordMatching() {
        val node = LearnedNode("$pkg:id/close", "android.widget.Button", "×", Rect(235, 65, 295, 105))
        val rule = DemonstrationLearning.propose(pkg, 7, image(), 265f, 85f, listOf(node))!!
        assertEquals(node.box, rule.box)
        assertEquals(node, rule.node)
    }

    @Test fun tapsInSystemStripsAndInvalidCoordinatesAreNotRules() {
        val source = image()
        for ((x, y) in listOf(Float.NaN to 85f, 265f to Float.NaN, -1f to 85f,
                321f to 85f, 265f to 2f, 265f to 638f))
            assertNull(DemonstrationLearning.propose(pkg, 7, source, x, y, emptyList()))
    }

    @Test fun unchangedAdsAndAnimatedAdsWithTheSameButtonAreNotSaved() {
        val source = image()
        val rule = rule(source)
        assertFalse(DemonstrationLearning.confirmsExit(rule, source, 450))
        val changed = source.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 150 until changed.height) for (x in 0 until changed.width) changed.setPixel(x, y, Color.WHITE)
        assertFalse(DemonstrationLearning.confirmsExit(rule, changed, 450))
    }

    @Test fun aNewHomePageCanConfirmExitWithoutReadableText() {
        val rule = rule()
        val after = image(80, 450).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, getPixel(x, y) xor 0x00ffffff)
        }
        assertTrue(DemonstrationLearning.confirmsExit(rule, after, 450))
        assertFalse(DemonstrationLearning.confirmsExit(rule, after, 2_001))
        assertFalse(DemonstrationLearning.confirmsExit(rule, Bitmap.createScaledBitmap(after, 640, 1280, false), 450))
        after.eraseColor(Color.BLACK)
        assertFalse(DemonstrationLearning.confirmsExit(rule, after, 450))
    }

    @Test fun featurelessPicturesAreNotReusableRules() {
        val flat = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull(DemonstrationLearning.propose(pkg, 7, flat, 265f, 85f, emptyList()))
    }
}
