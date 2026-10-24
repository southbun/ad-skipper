package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisualFallbackRegressionTest {
    @Test fun defaultsKeepTextRecognitionOnAndNativeVisualModelsOff() {
        val settings = AppSettings()
        assertTrue(settings.layer1Enabled)
        assertTrue(settings.layer2Enabled)
        assertFalse(settings.layer3Enabled)
        assertFalse(settings.vlmEnabled)
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
        assertEquals(1800f, target!!.y, .01f)
    }

    @Test fun aSmallLowerSkipLabelIsEligibleForRecognition() {
        assertTrue(SkipSafety.isOcrCandidate(OcrLine("跳过", 950, 1800, 150, 60), 1080, 2400))
    }
}
