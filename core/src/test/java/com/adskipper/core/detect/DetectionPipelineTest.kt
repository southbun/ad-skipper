package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DetectionPipelineTest {
    private val allOn = AppSettings(layer3Enabled = true)
    private val point = 950f to 100f
    private val badge = listOf(OcrLine("���", 40, 100, 40, 30))

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
            l3yolo = { null }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, allOn).target)
    }

    @Test fun ocrBadgeAllowsSkip() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(point, badge) }, clock = { 0L },
        )
        assertEquals(SkipLayer.L2_OCR, pipeline.detect(null, { Unit }, allOn).target?.layer)
    }

    @Test fun yoloRunsWithOrWithoutAnOcrAdBadge() = runTest {
        var lines = badge
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null, lines) },
            l3yolo = { point }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
        lines = emptyList()
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
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
            AppSettings(layer2Enabled = false, layer3Enabled = false, vlmEnabled = false)).target)
    }

    @Test fun disabledYoloIsNeverCalled() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null, badge) },
            l3yolo = { error("Disabled") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, AppSettings(layer3Enabled = false)).target)
    }

    @Test fun explicitSkipCountdownWithoutAdBadgeProducesCandidate() = runTest {
        val lines = listOf(OcrLine("���� 2", 1005, 181, 200, 75))
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null },
            l2 = { _, _ -> DetectionPipeline.OcrPass(1005f to 181f, lines) }, clock = { 0L },
        )
        val target = pipeline.detect(null, { Unit }, AppSettings()).target
        assertNotNull(target)
        assertEquals(2, target?.countdown)
        assertTrue(target!!.requiresCountdownProof)
    }

    @Test fun unrelatedNumberCannotProveABareSkipLabel() = runTest {
        val lines = listOf(OcrLine("����", 950, 100, 100, 40), OcrLine("2", 50, 600, 30, 40))
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(point, lines) }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, AppSettings()).target)
    }

    @Test fun businessTextWithANumberIsNotAdEvidence() = runTest {
        val lines = listOf(OcrLine("������ 2", 950, 100, 100, 40))
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(point, lines) }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, AppSettings()).target)
    }

    @Test fun sideCountdownNeedsProofEvenWhenThereIsAnAdBadge() = runTest {
        val lines = badge + OcrLine("���� 5", 950, 1200, 100, 40)
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null },
            l2 = { _, _ -> DetectionPipeline.OcrPass(950f to 1200f, lines, requiresCountdownProof = true) },
            clock = { 0L },
        )
        assertTrue(pipeline.detect(null, { Unit }, AppSettings()).target!!.requiresCountdownProof)
    }

    @Test fun imageFallbackDoesNotDependOnACountdown() = runTest {
        val lines = listOf(OcrLine("���� 5", 950, 100, 100, 40))
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null, lines) },
            l3yolo = { point }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
    }

    @Test fun vlmRunsWhenYoloMissesAndThereIsNoReadableAdBadge() = runTest {
        var budget = 0L
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null) },
            l3yolo = { null }, l3vlm = { _, timeout -> budget = timeout; point }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_VLM, pipeline.detect(null, { Unit }, allOn).target?.layer)
        assertEquals(4_000L, budget)
    }

    @Test fun yoloHitDoesNotSpendTimeOnTheLargeModel() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null) },
            l3yolo = { point }, l3vlm = { _, _ -> error("YOLO already found a button") }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
    }

    @Test fun disabledVlmIsNeverCalled() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> DetectionPipeline.OcrPass(null) },
            l3vlm = { _, _ -> error("VLM disabled") }, clock = { 0L },
        )
        assertNull(pipeline.detect(null, { Unit }, AppSettings(vlmEnabled = false)).target)
    }

    @Test fun ocrFailureStillAllowsVisualFallback() = runTest {
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null }, l2 = { _, _ -> throw IllegalStateException("OCR unavailable") },
            l3yolo = { point }, clock = { 0L },
        )
        assertEquals(SkipLayer.L3_YOLO, pipeline.detect(null, { Unit }, allOn).target?.layer)
    }

    @Test fun vlmReceivesOnlyTheRemainingSessionTime() = runTest {
        var now = 0L
        var budget = 0L
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null },
            l2 = { _, _ -> now += 5_000; DetectionPipeline.OcrPass(null) },
            l3yolo = { now += 2_000; null },
            l3vlm = { _, timeout -> budget = timeout; point },
            clock = { now },
        )
        pipeline.detect(null, { Unit }, allOn)
        assertEquals(1_000L, budget)
    }

    @Test fun expiredPassNeverStartsAnotherModel() = runTest {
        var now = 0L
        val pipeline = DetectionPipeline<Unit>(
            l1 = { _, _ -> null },
            l2 = { _, _ -> now = 8_000; DetectionPipeline.OcrPass(null) },
            l3yolo = { error("Session expired") },
            l3vlm = { _, _ -> error("Session expired") },
            clock = { now },
        )
        assertNull(pipeline.detect(null, { Unit }, allOn).target)
    }
}
