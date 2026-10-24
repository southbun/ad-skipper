package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object NodeMatcher {
    fun findSkipNode(root: AccessibilityNodeInfo?, keywords: Collection<String>, excluded: Collection<String> = emptySet()): Rect? {
        val node = findSkipNodeInfo(root, keywords, excluded) ?: return null
        return Rect().also(node::getBoundsInScreen)
    }

    fun findSkipNodeInfo(root: AccessibilityNodeInfo?, keywords: Collection<String>, excluded: Collection<String> = emptySet()): AccessibilityNodeInfo? {
        root ?: return null
        val screen = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val clickable = ArrayList<AccessibilityNodeInfo>()
        val other = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 300) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (
                    KeywordMatcher.matches(node.text?.toString(), keywords, excluded) != null ||
                    KeywordMatcher.matches(node.contentDescription?.toString(), keywords, excluded) != null ||
                    KeywordMatcher.matches(node.viewIdResourceName?.substringAfterLast('/'), keywords) != null)) {
                val target = clickTarget(node, root, screen)
                if (target != null) {
                    (if (target.isClickable) clickable else other).add(target)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return clickable.firstOrNull() ?: other.firstOrNull()
    }

    private fun safeBounds(node: AccessibilityNodeInfo, screen: Rect): Rect? {
        val box = Rect().also(node::getBoundsInScreen)
        return box.takeIf {
            !it.isEmpty && screen.contains(it) && it.width() <= screen.width() * 0.4f &&
                it.height() <= screen.height() * 0.12f &&
                it.width().toLong() * it.height() <= screen.width().toLong() * screen.height() / 20
        }
    }

    private fun clickTarget(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        val label = safeBounds(node, screen) ?: return null
        var current = node
        repeat(4) {
            if (!current.isEnabled || !current.isVisibleToUser || current.windowId != root.windowId ||
                (current.packageName != null && current.packageName != root.packageName)) return null
            if (current.isClickable) {
                return current.takeIf { safeBounds(it, screen)?.contains(label) == true }
            }
            val parent = current.parent ?: return node
            if (parent == root) return node.takeIf { parent.isEnabled && !parent.isClickable }
            current = parent
        }
        return null
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
