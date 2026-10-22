package com.adskipper.core.detect

object KeywordMatcher {
    private val whitespace = Regex("\\s+")
    private val leadingCountdown = Regex("^\\d{1,2}\\s*(?:s|秒)?\\s*[|·]?\\s*", RegexOption.IGNORE_CASE)
    private val trailingCountdown = Regex("\\s*[|·]?\\s*\\d{1,2}\\s*(?:s|秒)?$", RegexOption.IGNORE_CASE)
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

    fun isAdEvidence(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val value = normalized(text).replace(" ", "").trim('|', '·', '[', ']', '【', '】')
        return value == "广告" || value == "advertisement" ||
            matches(text, listOf("跳过广告", "关闭广告", "Skip Ad", "skip_ad")) != null
    }

    fun findSkipLine(lines: List<OcrLine>, keywords: Collection<String>, excluded: Collection<String> = emptySet()): OcrLine? =
        lines.firstOrNull { matches(it.text, keywords, excluded) != null }
}
