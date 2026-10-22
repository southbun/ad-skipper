package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DetectionPipelineTest {
    private val allOn = AppSettings(layer3Enabled = true)
    private val point = 950f to 100f
    private val badge = listOf(OcrLine("广告", 40, 100, 40, 30))

    @Test fun nodeHitNeedsAdEvidence() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> point }, l2 = { _, _ -> DetectionPipeline.OcrPass(null) }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, allOn).target)
    }

    @Test fun provenNodeHitAvoidsScreenshot() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> point },
            l2 = { _, _ -> error("OCR should not run") }, clock = { 0L },
        )
        val result = pipeline.detect(null, { error("No screenshot needed") }, allOn, nodeAdEvidence = true)
        assertEquals(SkipLayer.L1_NODE, result.target?.layer)
    }

    @Test fun ocrSkipWithoutAdContextIsRejected() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(point) },
            l3yolo = { error("No ad evidence") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, allOn).target)
    }

    @Test fun ocrBadgeAllowsSkip() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(point, badge) }, clock = { 0L },
        )
        assertEquals(SkipLayer.L2_OCR, pipeline.detect(null, { Unit }, allOn).target?.layer)
    }

    @Test fun yoloAlsoNeedsCurrentAdEvidence() = runTest {
        var lines = badge
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null, lines) },
            l3yolo = { point }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
        lines = emptyList()
        assertNull(pipeline.detect(null, { Unit }, allOn).target)
    }

    @Test fun secureOrUnavailableScreenshotStopsImageLayers() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> error("Screenshot unavailable") },
            l3yolo = { error("Screenshot unavailable") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { null }, allOn).target)
    }

    @Test fun nodeOnlyDoesNotTakeScreenshot() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> error("OCR disabled") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { error("No screenshot") },
            AppSettings(layer2Enabled = false, layer3Enabled = false)).target)
    }

    @Test fun disabledYoloIsNeverCalled() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null, badge) },
            l3yolo = { error("Disabled") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, AppSettings()).target)
    }
}
