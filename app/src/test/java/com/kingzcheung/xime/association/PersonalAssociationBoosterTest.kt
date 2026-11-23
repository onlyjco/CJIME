package com.kingzcheung.xime.association

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PersonalAssociationBoosterTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("personal_assoc_test_").toFile()
        PersonalAssociationBooster.clear(tempDir)
    }

    @After
    fun tearDown() {
        PersonalAssociationBooster.clear(tempDir)
        tempDir.deleteRecursively()
    }

    @Test
    fun pickedAndIgnoredFeedbackReranksAndInjectsPersonalContinuations() {
        val candidates = mutableMapOf(
            "问题" to 0.40f,
            "安排" to 0.35f,
        )
        // 用户在“明天会议”后，忽略了默认第一的“问题”，专门点选了“安排”；随后又主动打出新搭档“议程”。
        PersonalAssociationBooster.recordSelection(
            tempDir,
            sessionId = 1L,
            contextText = "明天会议",
            pickedWord = "安排",
            shownWords = listOf("问题", "安排"),
        )
        PersonalAssociationBooster.onShownAndCommittedOther(
            tempDir,
            shownContext = "明天会议",
            shownWords = listOf("问题", "日程"),
            committedWord = "议程",
        )

        PersonalAssociationBooster.applyToCandidates(tempDir, "明天会议", candidates)
        assertTrue("安排 score (${candidates["安排"]}) should exceed 问题 (${candidates["问题"]})",
            candidates.getValue("安排") > candidates.getValue("问题"))
        assertTrue("Personal-only word '议程' should be injected into candidates", candidates.containsKey("议程"))
    }

    @Test
    fun sentenceEditCorrectionDemotesBadWordAndBoostsGoodWord() {
        PersonalAssociationBooster.onSentenceEditCorrection(
            dir = tempDir,
            leftContext = "",
            sentenceBefore = "你在干马啊",
            start = 2,
            end = 4,
            badWord = "干马",
            goodWord = "干嘛",
            sentenceAfter = "你在干嘛啊",
        )

        val candidates = mutableMapOf(
            "干马" to 0.55f,
            "干嘛" to 0.45f,
        )
        PersonalAssociationBooster.applyToCandidates(tempDir, "你在", candidates)
        assertTrue("Expected corrected '干嘛' (${candidates["干嘛"]}) > '干马' (${candidates["干马"]})",
            candidates.getValue("干嘛") > candidates.getValue("干马"))
    }

    @Test
    fun undoPickedAssociationPenalizesIt() {
        PersonalAssociationBooster.recordSelection(
            tempDir,
            sessionId = 9L,
            contextText = "收到",
            pickedWord = "回复",
            shownWords = listOf("回复", "谢谢"),
        )
        val beforeUndo = mutableMapOf("回复" to 0.50f, "谢谢" to 0.48f)
        PersonalAssociationBooster.applyToCandidates(tempDir, "收到", beforeUndo)
        assertTrue(beforeUndo.getValue("回复") > beforeUndo.getValue("谢谢"))

        PersonalAssociationBooster.noteAssociationUndone(tempDir, sessionId = 9L)
        val afterUndo = mutableMapOf("回复" to 0.50f, "谢谢" to 0.48f)
        PersonalAssociationBooster.applyToCandidates(tempDir, "收到", afterUndo)
        assertTrue(
            "Undoing picked association should drop '回复' (${afterUndo["回复"]}) below beforeUndo (${beforeUndo["回复"]})",
            afterUndo.getValue("回复") < beforeUndo.getValue("回复"),
        )
        assertEquals(true, PersonalAssociationBooster.stats(tempDir).penaltyCount >= 1)
    }
}
