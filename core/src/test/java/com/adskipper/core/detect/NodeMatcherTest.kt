package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class NodeMatcherTest {
    private val screen = Rect(0, 0, 1080, 2400)
    private val buttonBox = Rect(850, 100, 1050, 200)
    private val labelBox = Rect(900, 120, 1000, 180)
    private val keywords = listOf("跳过")

    @Suppress("DEPRECATION")
    private fun node(box: Rect, text: String? = null, clickable: Boolean = false,
                     enabled: Boolean = true) = AccessibilityNodeInfo.obtain().apply {
        setBoundsInScreen(box)
        this.text = text
        isVisibleToUser = true
        isEnabled = enabled
        isClickable = clickable
        packageName = "test.ad.app"
    }

    @Test fun labelInsideButtonUsesTheButtonBounds() {
        val root = node(screen)
        val button = node(buttonBox, clickable = true)
        val label = node(labelBox, "跳过 5")
        shadowOf(root).addChild(button)
        shadowOf(button).addChild(label)
        assertEquals(buttonBox, NodeMatcher.findSkipNode(root, keywords))
    }

    @Test fun disabledSkipControlIsNotAClickCandidate() {
        val root = node(screen)
        shadowOf(root).addChild(node(buttonBox, "跳过", clickable = true, enabled = false))
        assertNull(NodeMatcher.findSkipNode(root, keywords))
    }

    @Test fun disabledParentCannotBeBypassedByItsTextChild() {
        val root = node(screen)
        val button = node(buttonBox, clickable = true, enabled = false)
        shadowOf(root).addChild(button)
        shadowOf(button).addChild(node(labelBox, "跳过"))
        assertNull(NodeMatcher.findSkipNode(root, keywords))
    }

    @Test fun matchedTextKeepsItsParentClickAction() {
        val root = node(screen)
        val button = node(buttonBox, clickable = true)
        shadowOf(root).addChild(button)
        shadowOf(button).addChild(node(labelBox, "跳过 5"))
        var receivedAction = -1
        shadowOf(button).setOnPerformActionListener { action, _ ->
            receivedAction = action
            true
        }
        val target = NodeMatcher.findSkipNodeInfo(root, keywords)
        assertNotNull(target)
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, receivedAction)
    }

    @Test fun fullScreenClickableParentIsRejected() {
        val root = node(screen)
        val container = node(screen, clickable = true)
        shadowOf(root).addChild(container)
        shadowOf(container).addChild(node(labelBox, "跳过"))
        assertNull(NodeMatcher.findSkipNodeInfo(root, keywords))
    }

    @Test fun parentFromAnotherPackageIsRejected() {
        val root = node(screen)
        val button = node(buttonBox, clickable = true).apply { packageName = "other.app" }
        shadowOf(root).addChild(button)
        shadowOf(button).addChild(node(labelBox, "跳过"))
        assertNull(NodeMatcher.findSkipNodeInfo(root, keywords))
    }
}
