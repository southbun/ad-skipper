package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisualFallbackRegressionTest {
    @Test fun visualDetectionIsEnabledByDefault() {
        assertTrue(AppSettings().layer3Enabled)
    }

    @Test fun imageButtonDoesNotRequireAnOcrAdBadge() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null },
            l2 = { _, _ -> DetectionPipeline.OcrPass(null) },
            l3yolo = { 950f to 1800f },
            clock = { 0L },
        )
        val target = pipeline.detect(null, { Unit }, AppSettings(layer3Enabled = true)).target
        assertEquals(SkipLayer.L3_YOLO, target?.layer)
        assertEquals(1800f, target!!.y)
    }

    @Test fun aSmallLowerSkipLabelIsEligibleForRecognition() {
        assertTrue(SkipSafety.isOcrCandidate(OcrLine("����", 950, 1800, 150, 60), 1080, 2400))
    }
}
