package com.adskipper.core.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val masterEnabled: Boolean = true,
    val layer1Enabled: Boolean = true,
    val layer2Enabled: Boolean = true,
    val layer3Enabled: Boolean = false,
    val vlmEnabled: Boolean = false,
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    val whitelist: Set<String> = DEFAULT_WHITELIST,
    val debugOverlay: Boolean = false,
    val selfTest: Boolean = false,
) {
    companion object {
        val DEFAULT_KEYWORDS = setOf("跳过", "Skip", "Skip Ad", "关闭广告", "跳过广告", "skip_ad")
        val DEFAULT_WHITELIST = setOf(
            "com.android.systemui", "com.android.settings", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.miui.home", "com.huawei.android.launcher",
            "com.oppo.launcher", "com.vivo.launcher", "com.sec.android.app.launcher", "net.oneplus.launcher",
        )
    }
}

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            masterEnabled = p[KEY_MASTER] ?: true,
            layer1Enabled = p[KEY_L1] ?: true,
            layer2Enabled = p[KEY_L2] ?: true,
            layer3Enabled = p[KEY_L3] ?: false,
            vlmEnabled = p[KEY_VLM] ?: false,
            keywords = p[KEY_KEYWORDS] ?: AppSettings.DEFAULT_KEYWORDS,
            whitelist = p[KEY_WHITELIST] ?: AppSettings.DEFAULT_WHITELIST,
            debugOverlay = p[KEY_DEBUG] ?: false,
            selfTest = p[KEY_SELF_TEST] ?: false,
        )
    }

    suspend fun setMasterEnabled(v: Boolean) { context.dataStore.edit { it[KEY_MASTER] = v } }
    suspend fun setLayer1Enabled(v: Boolean) { context.dataStore.edit { it[KEY_L1] = v } }
    suspend fun setLayer2Enabled(v: Boolean) { context.dataStore.edit { it[KEY_L2] = v } }
    suspend fun setLayer3Enabled(v: Boolean) { context.dataStore.edit { it[KEY_L3] = v } }
    suspend fun setVlmEnabled(v: Boolean) { context.dataStore.edit { it[KEY_VLM] = v } }
    suspend fun setKeywords(v: Set<String>) { context.dataStore.edit { it[KEY_KEYWORDS] = v } }
    suspend fun setWhitelist(v: Set<String>) { context.dataStore.edit { it[KEY_WHITELIST] = v } }
    suspend fun setDebugOverlay(v: Boolean) { context.dataStore.edit { it[KEY_DEBUG] = v } }
    suspend fun setSelfTest(v: Boolean) { context.dataStore.edit { it[KEY_SELF_TEST] = v } }

    @Suppress("DEPRECATION")
    suspend fun seedDefaultLauncher() {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.takeIf { it != "android" } ?: return
        context.dataStore.edit { p ->
            if (p[KEY_LAUNCHER_SEEDED] != true) {
                p[KEY_WHITELIST] = (p[KEY_WHITELIST] ?: AppSettings.DEFAULT_WHITELIST) + home
                p[KEY_LAUNCHER_SEEDED] = true
            }
        }
    }

    private companion object {
        val KEY_MASTER = booleanPreferencesKey("master_enabled")
        val KEY_L1 = booleanPreferencesKey("layer1_enabled")
        val KEY_L2 = booleanPreferencesKey("layer2_enabled")
        val KEY_L3 = booleanPreferencesKey("layer3_enabled")
        val KEY_VLM = booleanPreferencesKey("vlm_enabled")
        val KEY_KEYWORDS = stringSetPreferencesKey("keywords")
        val KEY_WHITELIST = stringSetPreferencesKey("whitelist")
        val KEY_DEBUG = booleanPreferencesKey("debug_overlay")
        val KEY_SELF_TEST = booleanPreferencesKey("self_test")
        val KEY_LAUNCHER_SEEDED = booleanPreferencesKey("launcher_seeded")
    }
}
