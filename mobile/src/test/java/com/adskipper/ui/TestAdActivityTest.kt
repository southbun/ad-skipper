package com.adskipper.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TestAdActivityTest {
    @Test fun skipButtonStaysOutsideStatusBarsAndDisplayCutout() {
        val controller = Robolectric.buildActivity(TestAdActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as ViewGroup
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 140, 0, 100))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 160, 20, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            val skip = (0 until root.childCount).map(root::getChildAt).filterIsInstance<Button>().single()
            assertEquals(160, root.paddingTop)
            assertEquals(20, root.paddingRight)
            assertEquals(100, root.paddingBottom)
            assertTrue(skip.top >= 160)
            assertTrue(skip.right <= 1060)
            assertTrue(skip.bottom <= 2300)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun nativeAccessibilityClickClosesTheSimulatedAd() {
        val controller = Robolectric.buildActivity(TestAdActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as ViewGroup
            val skip = (0 until root.childCount).map(root::getChildAt).filterIsInstance<Button>().single()
            assertFalse(activity.isFinishing)
            assertTrue(skip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
