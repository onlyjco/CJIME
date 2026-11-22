package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishWordMemoryTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun words(dir: File) = File(dir, EnglishWordMemory.FILE_NAME).readLines()

    @Test
    fun remembersWordsAsTyped() {
        val dir = tempDir.root
        EnglishWordMemory.remember(dir, "Obsidian")
        EnglishWordMemory.remember(dir, "Obsidian")
        EnglishWordMemory.remember(dir, "transformer")
        assertEquals(listOf("Obsidian", "transformer"), words(dir))
    }

    @Test
    fun skipsShortLowercaseAndNonLetters() {
        val dir = tempDir.root
        listOf("abc", "nh", "a", "hello world", "C++", "123", "zhong'guo").forEach { EnglishWordMemory.remember(dir, it) }
        EnglishWordMemory.remember(dir, "KFC")
        assertEquals(listOf("KFC"), words(dir))
    }

    @Test
    fun forgetRemovesEverySpelling() {
        val dir = tempDir.root
        listOf("Obsidian", "Notion", "OBSIDIAN").forEach { EnglishWordMemory.remember(dir, it) }
        assertTrue(EnglishWordMemory.forget(dir, "Obsidian"))
        assertEquals(listOf("Notion"), words(dir))
        assertFalse(EnglishWordMemory.forget(dir, "Obsidian"))
        assertFalse(EnglishWordMemory.forget(dir, "你好"))
    }

    @Test
    fun compactKeepsLatestOrder() {
        assertEquals(listOf("Notion", "Obsidian"), EnglishWordMemory.compact(listOf("Obsidian", "Notion", "Obsidian", "x y")))
    }

    @Test
    fun demoteKeepsOneLowercaseLineAndPickingBringsItBack() {
        val dir = tempDir.root
        val file = File(dir, EnglishWordMemory.DEMOTED_FILE_NAME)
        assertTrue(EnglishWordMemory.demote(dir, "Nude"))
        assertTrue(EnglishWordMemory.demote(dir, "nude"))
        assertTrue(EnglishWordMemory.demote(dir, "same"))
        assertEquals(listOf("nude", "same"), file.readLines())
        assertFalse(EnglishWordMemory.demote(dir, "你好"))
        assertFalse(EnglishWordMemory.demote(dir, "B站"))
        // Typed again with Enter: remembered, and back in its dictionary place.
        EnglishWordMemory.remember(dir, "Nude")
        assertEquals(listOf("same"), file.readLines())
        EnglishWordMemory.undemote(dir, "SAME")
        assertEquals(emptyList<String>(), file.readLines())
    }

    @Test
    fun backupIncludesTheWordFile() {
        assertTrue(BackupManager.isRimePersonalFile(EnglishWordMemory.FILE_NAME))
        assertTrue(BackupManager.isRimePersonalFile(EnglishWordMemory.DEMOTED_FILE_NAME))
    }

    @Test
    fun observationPoolSupportsCompletionAndPromotesOnSecondType() {
        val dir = tempDir.root
        val rev0 = EnglishWordMemory.revision()
        assertEquals(
            EnglishWordMemory.RecordOutcome.OBSERVED,
            EnglishWordMemory.recordTypedWord(dir, "antigravity", builtinKnown = false)
        )
        assertEquals(rev0, EnglishWordMemory.revision())
        assertEquals(listOf("antigravity"), EnglishWordMemory.completions(dir, "anti", 5))
        assertTrue(EnglishWordMemory.promotedWords(dir).isEmpty())
        assertTrue(EnglishWordMemory.contains(dir, "antigravity"))

        assertEquals(
            EnglishWordMemory.RecordOutcome.PROMOTED,
            EnglishWordMemory.recordTypedWord(dir, "antigravity", builtinKnown = false)
        )
        assertTrue(EnglishWordMemory.revision() > rev0)
        assertEquals(listOf("antigravity"), EnglishWordMemory.promotedWords(dir))
        assertEquals(listOf("antigravity"), words(dir))
    }

    @Test
    fun pickingObservedWordPromotesImmediatelyAndKeepsIntentionalCase() {
        val dir = tempDir.root
        assertEquals(
            EnglishWordMemory.RecordOutcome.OBSERVED,
            EnglishWordMemory.recordTypedWord(dir, "Gemini", builtinKnown = false)
        )
        assertEquals(listOf("Gemini"), EnglishWordMemory.completions(dir, "ge", 5))
        assertEquals(
            EnglishWordMemory.RecordOutcome.PROMOTED,
            EnglishWordMemory.recordPickedWord(dir, "Gemini", builtinKnown = false)
        )
        assertEquals(listOf("Gemini"), EnglishWordMemory.promotedWords(dir))

        // Typing lowercase later preserves existing capitalized spelling.
        assertEquals(
            EnglishWordMemory.RecordOutcome.REFRESHED,
            EnglishWordMemory.recordTypedWord(dir, "gemini", builtinKnown = false)
        )
        assertEquals(listOf("Gemini"), EnglishWordMemory.promotedWords(dir))
        assertEquals(listOf("GEMINI"), EnglishWordMemory.completions(dir, "GE", 5))
    }

    @Test
    fun builtinLowercaseWordsSkipObservationAndForgetClearsBothStores() {
        val dir = tempDir.root
        assertEquals(
            EnglishWordMemory.RecordOutcome.IGNORED,
            EnglishWordMemory.recordTypedWord(dir, "hello", builtinKnown = true)
        )
        assertTrue(EnglishWordMemory.completions(dir, "he", 5).isEmpty())

        EnglishWordMemory.recordTypedWord(dir, "antigravity", builtinKnown = false)
        assertTrue(EnglishWordMemory.forget(dir, "antigravity"))
        assertFalse(EnglishWordMemory.contains(dir, "antigravity"))
        assertTrue(EnglishWordMemory.completions(dir, "anti", 5).isEmpty())
    }
}
