package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object NodeMatcher {
    fun findSkipNode(root: AccessibilityNodeInfo?, keywords: Collection<String>, excluded: Collection<String> = emptySet()): Rect? {
        root ?: return null
        val screen = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val clickable = ArrayList<Rect>()
        val other = ArrayList<Rect>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 300) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (
                    KeywordMatcher.matches(node.text?.toString(), keywords, excluded) != null ||
                    KeywordMatcher.matches(node.contentDescription?.toString(), keywords, excluded) != null ||
                    KeywordMatcher.matches(node.viewIdResourceName?.substringAfterLast('/'), keywords) != null)) {
                val box = Rect().also(node::getBoundsInScreen)
                if (!box.isEmpty && screen.contains(box) && box.width() <= screen.width() * 0.4f &&
                    box.height() <= screen.height() * 0.12f &&
                    box.width().toLong() * box.height() <= screen.width().toLong() * screen.height() / 20 &&
                    SkipSafety.isCorner(box.exactCenterX() - screen.left, box.exactCenterY() - screen.top, screen.width(), screen.height())) {
                    (if (node.isClickable) clickable else other).add(box)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return clickable.firstOrNull() ?: other.firstOrNull()
    }

    fun hasAdEvidence(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 120) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (
                    KeywordMatcher.isAdEvidence(node.text?.toString()) ||
                    KeywordMatcher.isAdEvidence(node.contentDescription?.toString()) ||
                    AdSdkSignatures.matchesClassName(node.className?.toString()) ||
                    AdSdkSignatures.matchesViewId(node.viewIdResourceName))) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }
}
