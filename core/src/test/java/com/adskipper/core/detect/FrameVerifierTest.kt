package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class FrameVerifierTest {
    private fun frame(color: Int, width: Int = 1080) =
        Bitmap.createBitmap(width, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private val target = SkipTarget(950f, 180f, SkipLayer.L3_VLM)

    @Test fun unchangedButtonRegionCanBeConfirmedWithoutASecondVlmPass() {
        val before = frame(Color.WHITE)
        val after = frame(Color.WHITE)
        after.setPixel(950, 180, Color.BLACK) // one small countdown change
        assertTrue(FrameVerifier.matches(before, after, target))
    }

    @Test fun changedPageOrChangedDisplaySizeRejectsTheOldCoordinates() {
        assertFalse(FrameVerifier.matches(frame(Color.WHITE), frame(Color.BLACK), target))
        assertFalse(FrameVerifier.matches(frame(Color.WHITE), frame(Color.WHITE, 1200), target))
    }

    @Test fun changedButtonRegionRejectsTheOldCoordinates() {
        val before = frame(Color.WHITE)
        val after = frame(Color.WHITE)
        for (y in 0 until 400) for (x in 750 until 1080) after.setPixel(x, y, Color.BLACK)
        assertFalse(FrameVerifier.matches(before, after, target))
    }

    @Test fun aNewPageWithTheSameColoredCornerStillRejectsTheOldCoordinates() {
        val before = frame(Color.WHITE)
        val after = frame(Color.BLACK)
        for (y in 0 until 400) for (x in 750 until 1080) after.setPixel(x, y, Color.WHITE)
        assertFalse(FrameVerifier.matches(before, after, target))
    }

    @Test fun nonFiniteOrOutOfBoundsCoordinatesAreRejected() {
        val image = frame(Color.WHITE)
        assertFalse(FrameVerifier.matches(image, image, target.copy(x = Float.NaN)))
        assertFalse(FrameVerifier.matches(image, image, target.copy(y = -1f)))
        assertFalse(FrameVerifier.matches(image, image, target.copy(x = 1080f)))
    }
}
