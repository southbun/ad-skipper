package com.adskipper.ui.apps

import org.junit.Assert.*
import org.junit.Test

class InstalledAppSearchTest {
    private val weibo = InstalledApp("com.sina.weibo", "微博")
    private val taobao = InstalledApp("com.taobao.taobao", "淘宝")
    private val browser = InstalledApp("com.android.chrome", "Chrome", isProtected = true)
    private val apps = listOf(weibo, taobao, browser)

    @Test fun searchesInstalledAppNamesAndPackageNamesWithoutCaseSensitivity() {
        assertEquals(listOf(taobao), filterInstalledApps(apps, " 淘宝 ", emptySet(), false))
        assertEquals(listOf(weibo), filterInstalledApps(apps, "SINA.WEIBO", emptySet(), false))
        assertEquals(listOf(browser), filterInstalledApps(apps, "chrome", emptySet(), false))
        assertTrue(filterInstalledApps(apps, "not-installed", emptySet(), false).isEmpty())
    }

    @Test fun enabledOnlyStartsEmptyAndReflectsExactlyTheSelectedApps() {
        assertTrue(filterInstalledApps(apps, "", emptySet(), true).isEmpty())
        assertEquals(listOf(weibo), filterInstalledApps(apps, "", setOf(weibo.packageName), true))
        assertTrue(filterInstalledApps(apps, "淘宝", setOf(weibo.packageName), true).isEmpty())
    }

    @Test fun protectedAppsNeverAppearAsEnabledEvenWithAnOldStoredEntry() {
        assertTrue(filterInstalledApps(apps, "", setOf(browser.packageName), true).isEmpty())
    }
}
