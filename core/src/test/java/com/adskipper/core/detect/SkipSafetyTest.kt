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
}
