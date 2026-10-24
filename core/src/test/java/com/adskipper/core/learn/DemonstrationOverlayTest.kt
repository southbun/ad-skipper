package com.adskipper.core.learn

import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import com.adskipper.core.service.AdSkipperService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class DemonstrationOverlayTest {
    private val service get() = Robolectric.buildService(AdSkipperService::class.java).create().get()
    private fun view(overlay: DemonstrationOverlay): FrameLayout = ReflectionHelpers.getField(overlay, "view")
    private fun event(page: View, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(100, time, action, x, y, 0)
        try { page.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun oneTapIsRecordedThenTheTouchLayerIsRemoved() {
        val overlay = DemonstrationOverlay(service)
        var recorded: DemonstrationTouch? = null
        assertTrue(overlay.show({ recorded = it }, { fail("Unexpected cancellation") }))
        val page = view(overlay)
        event(page, MotionEvent.ACTION_DOWN, 260f, 400f, 100)
        event(page, MotionEvent.ACTION_UP, 260f, 400f, 180)
        assertTrue(recorded!!.tap)
        assertEquals(260f, recorded!!.x, 0f)
        assertEquals(400f, recorded!!.y, 0f)
        assertFalse(overlay.showing)
    }

    @Test fun scrollingIsForwardedAsAGestureWithoutBecomingATap() {
        val overlay = DemonstrationOverlay(service)
        var recorded: DemonstrationTouch? = null
        overlay.show({ recorded = it }, {})
        val page = view(overlay)
        event(page, MotionEvent.ACTION_DOWN, 260f, 400f, 100)
        event(page, MotionEvent.ACTION_MOVE, 260f, 250f, 250)
        event(page, MotionEvent.ACTION_UP, 260f, 200f, 350)
        assertNotNull(recorded)
        assertFalse(recorded!!.tap)
        assertFalse(overlay.showing)
    }

    @Test fun noAdCancellationNeverRecordsAClick() {
        val overlay = DemonstrationOverlay(service)
        var cancelled = false
        overlay.show({ fail("Cancel must not be recorded") }, { cancelled = true })
        (view(overlay).getChildAt(0) as Button).performClick()
        assertTrue(cancelled)
        assertFalse(overlay.showing)
    }

    @Test fun multTouchCancelsAndLateEventsAfterHidingAreIgnored() {
        val overlay = DemonstrationOverlay(service)
        var cancelled = false
        overlay.show({ fail("Invalid interaction must not be recorded") }, { cancelled = true })
        val page = view(overlay)
        event(page, MotionEvent.ACTION_DOWN, 260f, 400f, 100)
        event(page, MotionEvent.ACTION_POINTER_DOWN, 260f, 400f, 150)
        event(page, MotionEvent.ACTION_UP, 260f, 400f, 180)
        assertTrue(cancelled)
        assertFalse(overlay.showing)
    }
}
