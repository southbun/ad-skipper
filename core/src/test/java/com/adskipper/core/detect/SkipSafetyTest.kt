package com.adskipper.core.detect

import org.junit.Assert.*
import org.junit.Test

class SkipSafetyTest {
    @Test fun cornersOnly() {
        assertTrue(SkipSafety.isCorner(950f, 100f, 1080, 2400))
        assertTrue(SkipSafety.isCorner(50f, 2250f, 1080, 2400))
        assertFalse(SkipSafety.isCorner(540f, 1200f, 1080, 2400))
        assertFalse(SkipSafety.isCorner(950f, 1200f, 1080, 2400))
    }

    @Test fun invalidCoordinatesAreRejected() {
        assertFalse(SkipSafety.isCorner(Float.NaN, 10f, 1080, 2400))
        assertFalse(SkipSafety.isCorner(-1f, 10f, 1080, 2400))
        assertFalse(SkipSafety.isCorner(1100f, 10f, 1080, 2400))
        assertFalse(SkipSafety.isCorner(1f, 1f, 0, 0))
    }

    @Test fun changedAppOrWindowRejectsOldCoordinates() {
        assertTrue(SkipSafety.sameWindow("app.a", 5, "app.a", 5))
        assertFalse(SkipSafety.sameWindow("app.a", 5, "app.b", 5))
        assertFalse(SkipSafety.sameWindow("app.a", 5, "app.a", 6))
        assertFalse(SkipSafety.sameWindow("app.a", 5, null, 5))
    }

    @Test fun movedOrChangedCandidatesAreRejected() {
        val first = SkipTarget(950f, 100f, SkipLayer.L2_OCR)
        assertTrue(SkipSafety.sameTarget(first, first.copy(x = 960f)))
        assertFalse(SkipSafety.sameTarget(first, first.copy(x = 100f)))
        assertFalse(SkipSafety.sameTarget(first, first.copy(layer = SkipLayer.L3_YOLO)))
    }

    @Test fun lowerSideCountdownIsEligibleButCentralOrBareSkipIsNot() {
        val side = OcrLine("跳过 5", 950, 1800, 150, 60)
        assertTrue(SkipSafety.isOcrCandidate(side, 1080, 2400))
        assertFalse(SkipSafety.isOcrCandidate(side.copy(text = "跳过"), 1080, 2400))
        assertFalse(SkipSafety.isOcrCandidate(side.copy(centerX = 540), 1080, 2400))
        assertFalse(SkipSafety.isOcrCandidate(side.copy(width = 600), 1080, 2400))
        assertFalse(SkipSafety.isOcrCandidate(side.copy(centerX = 1080), 1080, 2400))
    }

    @Test fun screenshotSkipCountdownFitsTheOriginalCorner() {
        val line = OcrLine("跳过 2", 1005, 181, 200, 75)
        assertTrue(SkipSafety.isCorner(1005f, 181f, 1200, 2608))
        assertTrue(SkipSafety.isOcrCandidate(line, 1200, 2608))
    }

    @Test fun onlyAStableDecreasingCountdownCanBeConfirmed() {
        val first = SkipTarget(950f, 1800f, SkipLayer.L2_OCR, countdown = 5, requiresCountdownProof = true)
        assertTrue(SkipSafety.canConfirmTarget(first, first.copy(countdown = 4), 1100))
        assertFalse(SkipSafety.canConfirmTarget(first, first, 1100))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = 6), 1100))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = 4), 120))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = 4), 3000))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = 4, x = 500f), 1100))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = null), 1100))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(countdown = 0), 1100))
    }

    @Test fun explicitAdContextStillUsesNormalTwoPassConfirmation() {
        val first = SkipTarget(950f, 100f, SkipLayer.L1_NODE)
        assertTrue(SkipSafety.canConfirmTarget(first, first.copy(x = 960f), 120))
        assertFalse(SkipSafety.canConfirmTarget(first, first.copy(layer = SkipLayer.L2_OCR), 120))
    }
}
