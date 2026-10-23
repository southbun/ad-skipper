package com.adskipper.core.learn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class LearningControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        LearningController.discard()
        ReflectionHelpers.setStaticField(LearnedRuleRepository::class.java, "instance", null)
    }
    @After fun cleanup() { LearningController.discard(); Dispatchers.resetMain() }
    private suspend fun capture() {
        LearningController.arm("test.ad.app")
        val id = LearningController.capturing()!!
        val image = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        try { LearningController.captured(context, id, image, emptyList(), 7) } finally { image.recycle() }
    }
    private fun message(id: String, type: Int, bounds: IntArray? = null) = Message.obtain().apply {
        what = type
        data = Bundle().apply { putString("id", id); putString("message", "result"); putIntArray("box", bounds) }
    }

    @Test fun armingPausesOnlyTheSelectedAppAndExpires() = runTest(dispatcher) {
        LearningController.arm("test.ad.app")
        assertTrue(LearningController.collecting("test.ad.app"))
        assertFalse(LearningController.collecting("other.app"))
        dispatcher.scheduler.runCurrent()
        advanceTimeBy(60_001)
        assertEquals(LearningController.Phase.ERROR, LearningController.state.value.phase)
        assertFalse(LearningController.collecting("test.ad.app"))
    }

    @Test fun aCapturedImageRequiresConfirmationAndIsRemovedOnDiscard() = runTest(dispatcher) {
        capture()
        val file = java.io.File(LearningController.state.value.imagePath!!)
        assertTrue(file.isFile)
        assertEquals(LearningController.Phase.CAPTURED, LearningController.state.value.phase)
        LearningController.select(Rect(240, 55, 290, 85), 320, 640)
        assertEquals(LearningController.Phase.REVIEW, LearningController.state.value.phase)
        LearningController.discard()
        assertFalse(file.exists())
    }

    @Test fun aLateCaptureCannotReviveADiscardedSession() = runTest(dispatcher) {
        LearningController.arm("test.ad.app")
        val old = LearningController.capturing()!!
        LearningController.discard()
        LearningController.captured(context, old, Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888), emptyList(), 7)
        assertEquals(LearningController.Phase.IDLE, LearningController.state.value.phase)
        assertNull(LearningController.state.value.imagePath)
    }

    @Test fun staleModelRepliesAreIgnoredAfterManualSelection() = runTest(dispatcher) {
        capture()
        LearningController.analyze(context)
        val id = LearningController.state.value.id
        LearningController.select(Rect(240, 55, 290, 85), 320, 640)
        LearningController.receive(id, message(id, LearningWorkerService.RESULT, intArrayOf(0, 0, 320, 640)), 100)
        assertEquals(Rect(240, 55, 290, 85), LearningController.state.value.box)
        assertEquals(LearningController.Phase.REVIEW, LearningController.state.value.phase)
    }

    @Test fun reanalysisRejectsThePreviousWorkersQueuedReply() = runTest(dispatcher) {
        capture()
        LearningController.analyze(context)
        val old = LearningController.state.value.id
        LearningController.fail("retry")
        LearningController.analyze(context)
        val current = LearningController.state.value.id
        assertNotEquals(old, current)
        LearningController.receive(old, message(old, LearningWorkerService.RESULT, intArrayOf(240, 55, 290, 85)), 10)
        assertNull(LearningController.state.value.box)
    }

    @Test fun invalidModelGeometryIsRejectedWithoutLosingTheSample() = runTest(dispatcher) {
        capture()
        LearningController.analyze(context)
        val id = LearningController.state.value.id
        LearningController.receive(id, message(id, LearningWorkerService.RESULT, intArrayOf(0, 0, 320, 640)), 10)
        assertEquals(LearningController.Phase.ERROR, LearningController.state.value.phase)
        assertNotNull(LearningController.state.value.imagePath)
        assertNull(LearningController.state.value.box)
    }

    @Test fun aModelResultOnlyCreatesAPreviewAndNeverSavesARule() = runTest(dispatcher) {
        capture()
        LearningController.analyze(context)
        val id = LearningController.state.value.id
        LearningController.receive(id, message(id, LearningWorkerService.INFER), 10)
        assertEquals(1, LearningController.state.value.inferenceCount)
        LearningController.receive(id, message(id, LearningWorkerService.RESULT, intArrayOf(240, 55, 290, 85)), 50)
        assertEquals(LearningController.Phase.REVIEW, LearningController.state.value.phase)
        assertEquals(Rect(240, 55, 290, 85), LearningController.state.value.box)
        assertFalse(java.io.File(context.filesDir, "learned-rules.json").exists())
    }

    @Test fun oversizedManualSelectionsDoNotReplaceTheConfirmedBox() = runTest(dispatcher) {
        capture()
        LearningController.select(Rect(240, 55, 290, 85), 320, 640)
        LearningController.select(Rect(0, 0, 320, 640), 320, 640)
        assertEquals(Rect(240, 55, 290, 85), LearningController.state.value.box)
    }

    @Test fun confirmingSavesAReusableRuleAndDeletesTheFullScreenshot() = runTest(dispatcher) {
        capture()
        val file = java.io.File(LearningController.state.value.imagePath!!)
        LearningController.analyze(context)
        val id = LearningController.state.value.id
        LearningController.receive(id, message(id, LearningWorkerService.INFER), 10)
        LearningController.receive(id, message(id, LearningWorkerService.RESULT, intArrayOf(240, 55, 290, 85)), 50)
        LearningController.save(context)
        assertEquals(LearningController.Phase.SAVED, LearningController.state.value.phase)
        assertEquals(1, LearningController.state.value.inferenceCount)
        assertEquals(50L, LearningController.state.value.elapsedMs)
        assertFalse(file.exists())
        val rules = LearnedRuleRepository(java.io.File(context.filesDir, "learned-rules.json")).rules.value
        assertEquals(1, rules.size)
        assertEquals("test.ad.app", rules.single().packageName)
        assertEquals(7L, rules.single().version)
    }

    @Test fun aHungWorkerTimesOutWithoutSavingTheProposal() = runTest(dispatcher) {
        capture()
        LearningController.analyze(context)
        dispatcher.scheduler.runCurrent()
        advanceTimeBy(90_001)
        assertEquals(LearningController.Phase.ERROR, LearningController.state.value.phase)
        assertNull(LearningController.state.value.box)
        assertNotNull(LearningController.state.value.imagePath)
    }
}
