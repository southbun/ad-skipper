package com.adskipper.core.detect

object KeywordMatcher {
    private val whitespace = Regex("\\s+")
    private const val countdownToken = "[（(\\[【]?\\s*(\\d{1,2})\\s*(?:s|秒)?\\s*[）)\\]】]?"
    private val leadingCountdown = Regex("^$countdownToken\\s*[|·]?\\s*", RegexOption.IGNORE_CASE)
    private val trailingCountdown = Regex("\\s*[|·]?\\s*$countdownToken$", RegexOption.IGNORE_CASE)
    private val countdownLabels = listOf("跳过", "Skip", "跳过广告", "关闭广告", "Skip Ad", "skip_ad")
    private fun normalized(text: String) = text.trim().lowercase().replace(whitespace, " ")

    fun matches(text: String?, keywords: Collection<String>, excluded: Collection<String> = emptySet()): String? {
        if (!isPlausibleButtonText(text)) return null
        val value = normalized(text!!)
        if (excluded.any { value == normalized(it) }) return null
        val label = value.replace(leadingCountdown, "").replace(trailingCountdown, "").trim()
        return keywords.firstOrNull { it.isNotBlank() && label == normalized(it) }
    }

    fun isPlausibleButtonText(text: String?): Boolean =
        !text.isNullOrBlank() && text.trim().length <= 16

    fun countdown(text: String?): Int? {
        if (matches(text, countdownLabels) == null) return null
        val value = normalized(text!!)
        val leading = leadingCountdown.find(value)
        val trailing = trailingCountdown.find(value)
        if (leading != null && trailing != null) return null
        return (leading ?: trailing)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..30 }
    }

    fun isAdEvidence(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val value = normalized(text).replace(" ", "").trim('|', '·', '[', ']', '【', '】')
        return value == "广告" || value == "advertisement" ||
            matches(text, listOf("跳过广告", "关闭广告", "Skip Ad", "skip_ad")) != null
    }

    fun findSkipLine(lines: List<OcrLine>, keywords: Collection<String>, excluded: Collection<String> = emptySet()): OcrLine? =
        lines.firstOrNull { matches(it.text, keywords, excluded) != null }
}
