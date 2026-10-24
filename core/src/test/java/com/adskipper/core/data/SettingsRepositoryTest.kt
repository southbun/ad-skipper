package com.adskipper.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(file: File) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it },
        produceFile = { file },
    )

    @After fun cleanup() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun noApplicationIsEnabledInNewOrLegacySettings() = runBlocking {
        val data = store(File(temp.newFolder(), "settings.preferences_pb"))
        val repo = SettingsRepository(data)
        assertTrue(repo.settings.first().enabledPackages.isEmpty())
        data.edit {
            it[booleanPreferencesKey("master_enabled")] = true
            it[stringSetPreferencesKey("whitelist")] = setOf("com.tencent.mm")
        }
        val migrated = repo.settings.first()
        assertTrue(migrated.masterEnabled)
        assertTrue(migrated.enabledPackages.isEmpty())
        repo.setAppEnabled("com.tencent.mm", true)
        assertEquals(setOf("com.tencent.mm"), repo.settings.first().enabledPackages)
    }

    @Test fun rapidIndependentTogglesNeverOverwriteOtherApps() = runBlocking {
        val repo = SettingsRepository(store(File(temp.newFolder(), "settings.preferences_pb")))
        val packages = (1..10).map { "test.app.$it" }.toSet()
        coroutineScope { packages.forEach { pkg -> launch { repo.setAppEnabled(pkg, true) } } }
        assertEquals(packages, repo.settings.first().enabledPackages)
        repo.setAppEnabled("test.app.1", false)
        assertEquals(packages - "test.app.1", repo.settings.first().enabledPackages)
    }

    @Test fun selectionsSurviveClosingAndReopeningTheStore() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        SettingsRepository(store(file)).setAppEnabled("com.sina.weibo", true)
        scopes.last().coroutineContext[Job]!!.cancelAndJoin()
        val reopened = SettingsRepository(store(file))
        assertEquals(setOf("com.sina.weibo"), reopened.settings.first().enabledPackages)
        reopened.setAppEnabled("com.sina.weibo", false)
        assertTrue(reopened.settings.first().enabledPackages.isEmpty())
    }

    @Test fun demonstrationOptInPersistsIndependentlyAndNeverEnablesAnApp() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val repo = SettingsRepository(store(file))
        assertTrue(repo.settings.first().demonstrationPackages.isEmpty())
        repo.setDemonstrationEnabled("com.sina.weibo", true)
        repo.setDemonstrationEnabled("com.taobao.taobao", true)
        assertTrue(repo.settings.first().enabledPackages.isEmpty())
        scopes.last().coroutineContext[Job]!!.cancelAndJoin()
        val reopened = SettingsRepository(store(file))
        assertEquals(setOf("com.sina.weibo", "com.taobao.taobao"), reopened.settings.first().demonstrationPackages)
        reopened.setDemonstrationEnabled("com.sina.weibo", false)
        assertEquals(setOf("com.taobao.taobao"), reopened.settings.first().demonstrationPackages)
    }
}
