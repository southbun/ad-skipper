package com.adskipper.core.learn

import android.content.Context
import android.graphics.Rect
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LearnedRuleRepository(file: File) {
    private val file = AtomicFile(file)
    private var readError: Exception? = null
    private val mutableRules = MutableStateFlow(read())
    val rules: StateFlow<List<LearnedRule>> = mutableRules
    val error: String? get() = readError?.let { "规则文件无法读取，请保留文件并检查存储" }

    private fun read(): List<LearnedRule> = try {
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("schema") == 1)
        val array = json.getJSONArray("rules")
        List(array.length()) { decode(array.getJSONObject(it)) }.also { require(it.all(LearnedRule::valid)) }
    } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) readError = error
        emptyList()
    } catch (error: Exception) {
        readError = error
        emptyList()
    }

    @Synchronized fun save(rule: LearnedRule) {
        require(rule.valid())
        val current = mutableRules.value
        val saved = current.lastOrNull { LearnedMatcher.sameTemplate(it, rule) }?.let { rule.copy(id = it.id) } ?: rule
        val retained = current.filter { it.packageName != rule.packageName } +
            current.filter { it.packageName == rule.packageName && it.id != saved.id }.takeLast(7) + saved
        write(retained)
    }

    @Synchronized fun delete(id: String) = write(mutableRules.value.filter { it.id != id })

    private fun write(rules: List<LearnedRule>) {
        check(readError == null) { "规则文件读取失败，已停止覆盖" }
        val text = JSONObject().put("schema", 1).put("rules", JSONArray(rules.map(::encode))).toString()
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        mutableRules.value = rules
    }

    companion object {
        @Volatile private var instance: LearnedRuleRepository? = null
        fun get(context: Context): LearnedRuleRepository = instance ?: synchronized(this) {
            instance ?: LearnedRuleRepository(File(context.filesDir, "learned-rules.json")).also { instance = it }
        }

        private fun rect(box: Rect) = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
        private fun box(array: JSONArray) = Rect(array.getInt(0), array.getInt(1), array.getInt(2), array.getInt(3))
        private fun ints(array: JSONArray) = List(array.length()) { array.getInt(it) }
        private fun encode(rule: LearnedRule): JSONObject = JSONObject().apply {
            put("id", rule.id); put("package", rule.packageName); put("version", rule.version)
            put("created", rule.createdAt); put("width", rule.width); put("height", rule.height)
            put("box", rect(rule.box)); put("layout", rule.layout)
            put("page", JSONArray(rule.page)); put("button", JSONArray(rule.button))
            rule.node?.let { put("node", JSONObject().put("id", it.viewId).put("class", it.className)
                .put("label", it.label).put("box", rect(it.box))) }
        }
        private fun decode(json: JSONObject): LearnedRule {
            val node = json.optJSONObject("node")?.let { LearnedNode(it.getString("id"), it.getString("class"),
                it.getString("label"), box(it.getJSONArray("box"))) }
            return LearnedRule(json.getString("id"), json.getString("package"), json.getLong("version"),
                json.getLong("created"), json.getInt("width"), json.getInt("height"), box(json.getJSONArray("box")),
                json.getString("layout"), node, ints(json.getJSONArray("page")), ints(json.getJSONArray("button")))
        }
    }
}
