package com.adskipper.core.learn

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class LearnedRuleRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun rule(pkg: String = "test.ad.app") = LearnedMatcher.create(pkg, 7,
        Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888), Rect(240, 55, 290, 85), emptyList())

    @Test fun confirmedRulesSurviveReopeningAndCanBeDeleted() {
        val file = java.io.File(temp.root, "rules.json")
        val first = LearnedRuleRepository(file)
        assertTrue(first.rules.value.isEmpty())
        val rule = rule()
        first.save(rule)
        val second = LearnedRuleRepository(file)
        assertEquals(rule, second.rules.value.single())
        second.delete(rule.id)
        assertTrue(LearnedRuleRepository(file).rules.value.isEmpty())
    }

    @Test fun eachAppRetainsItsOwnLatestEightTemplates() {
        val repo = LearnedRuleRepository(java.io.File(temp.root, "rules.json"))
        val other = rule("another.app")
        repo.save(other)
        val rules = List(10) { i -> rule().copy(box = Rect(10 + i * 20, 55, 25 + i * 20, 85)) }
        rules.forEach(repo::save)
        assertEquals(rules.takeLast(8).map { it.id }, repo.rules.value.filter { it.packageName == "test.ad.app" }.map { it.id })
        assertTrue(other in repo.rules.value)
    }

    @Test fun repeatedDemonstrationsUpdateTheSameTemplateButKeepOtherPositions() {
        val file = java.io.File(temp.root, "rules.json")
        val repo = LearnedRuleRepository(file)
        val upper = rule()
        val lower = rule().copy(box = Rect(240, 500, 290, 530))
        repo.save(upper)
        repo.save(lower)
        repo.save(upper.copy(id = "new-demonstration", createdAt = upper.createdAt + 1))
        val reopened = LearnedRuleRepository(file).rules.value
        assertEquals(listOf(lower.id, upper.id), reopened.map { it.id })
        assertEquals(upper.createdAt + 1, reopened.last().createdAt)
    }

    @Test fun corruptDataIsPreservedInsteadOfSilentlyOverwritten() {
        val file = temp.newFile("rules.json").apply { writeText("broken existing rules") }
        val repo = LearnedRuleRepository(file)
        assertNotNull(repo.error)
        assertThrows(IllegalStateException::class.java) { repo.save(rule()) }
        assertEquals("broken existing rules", file.readText())
    }

    @Test fun invalidRuleCannotEnterTheLibrary() {
        val repo = LearnedRuleRepository(java.io.File(temp.root, "rules.json"))
        assertThrows(IllegalArgumentException::class.java) { repo.save(rule().copy(page = emptyList())) }
        assertTrue(repo.rules.value.isEmpty())
    }
}
