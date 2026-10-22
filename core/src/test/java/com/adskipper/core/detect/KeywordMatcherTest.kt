package com.adskipper.core.detect

import com.adskipper.core.data.AppSettings
import org.junit.Assert.*
import org.junit.Test

class KeywordMatcherTest {
    private val keywords = AppSettings.DEFAULT_KEYWORDS

    @Test fun recognizesSkipWithCountdown() {
        for (text in listOf("跳过 3s", "跳过 | 5", "3秒 跳过", "SKIP AD", "Skip 5s", "SKIP_AD")) {
            assertNotNull(text, KeywordMatcher.matches(text, keywords))
        }
    }

    @Test fun refusesNormalBusinessAndNegatedText() {
        for (text in listOf("跳过绑定", "跳过教程", "不要跳过", "一键跳过", "关闭广告推送", "Skip login", "Skipper", "点击跳过这一步")) {
            assertNull(text, KeywordMatcher.matches(text, keywords))
        }
    }

    @Test fun emptyTextAndExcludedLabelsAreRejected() {
        assertNull(KeywordMatcher.matches(null, keywords))
        assertNull(KeywordMatcher.matches(" ", keywords))
        assertNull(KeywordMatcher.matches("跳过", keywords, setOf("跳过")))
    }

    @Test fun adEvidenceIsExplicit() {
        for (text in listOf("广告", "[广告]", "Advertisement", "跳过广告 3s", "Skip Ad")) {
            assertTrue(text, KeywordMatcher.isAdEvidence(text))
        }
        for (text in listOf("跳过", "关闭广告推送", "个性化广告", "广告设置", "3s", "12:34")) {
            assertFalse(text, KeywordMatcher.isAdEvidence(text))
        }
    }
}
