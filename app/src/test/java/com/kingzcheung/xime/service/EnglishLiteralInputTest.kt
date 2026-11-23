package com.kingzcheung.xime.service

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class EnglishLiteralInputTest {
    @Test fun continuingAWordKeepsCompletionsUntilReplacementArrives() {
        val state = CandidateState(pendingEnglishText = "he", associationCandidates = listOf("hello", "help"))
        val next = requireNotNull(commitEnglishLetter(state, "l", false, { true }) { true })
        assertEquals("hel", next.pendingEnglishText)
        assertEquals(state.associationCandidates, next.associationCandidates)
        val mixed = CandidateState(pendingEnglishText = "i", associationCandidates = listOf("information", "is"))
        val narrowed = requireNotNull(commitEnglishLetter(mixed, "n", false, { true }) { true })
        assertEquals(listOf("information"), narrowed.associationCandidates)
        val newWord = requireNotNull(commitEnglishLetter(state, "w", false, { true }, afterGlide = true) { true })
        assertTrue(newWord.associationCandidates.isEmpty())
        val unsupported = requireNotNull(commitEnglishLetter(state, "l", false, { false }) { true })
        assertTrue(unsupported.associationCandidates.isEmpty())
    }
    @Test fun sentenceCapsHonorsParagraphResponsesWithoutRequestingWordCapitalization() {
        val app = mock<android.content.Context>()
        val prefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.view.inputmethod.InputConnection>()
        whenever(app.getSharedPreferences("kime_settings", 0)).thenReturn(prefs)
        assertFalse(englishSentenceStart(app, editor))
        verifyNoInteractions(editor)
        whenever(prefs.getBoolean(ENGLISH_SENTENCE_CAPS, false)).thenReturn(true)
        val mode = android.text.TextUtils.CAP_MODE_SENTENCES
        whenever(editor.getCursorCapsMode(mode)).thenReturn(android.text.TextUtils.CAP_MODE_WORDS, mode, 0)
        assertTrue(englishSentenceStart(app, editor))
        assertTrue(englishSentenceStart(app, editor))
        assertFalse(englishSentenceStart(app, editor))
        whenever(editor.getCursorCapsMode(mode)).thenThrow(IllegalStateException("closed editor"))
        assertFalse(englishSentenceStart(app, editor))
    }

    @Test fun sentenceOpenersOfOtherLanguagesStillStartASentence() {
        // 光标紧跟 « ¿ ¡ „ “ ：前面是输入框开头、换行或句末加空格，就是句首。
        for (before in listOf("«", "¿", "¡", "„", "“", "‹", "(«", "Он ушёл. «", "¿Qué? ¡", "Hola.\n¿",
                "Ende! „", "Fin.» «", "Done.\" “", "   «")) {
            assertTrue(before, sentenceStartsAfterOpener(before))
        }
        // 反例：开头符号紧贴前面的字、前面没有句末、缩写的点、正在打的字、只有空格没有开头符号。
        for (before in listOf("a¿", "Он сказал «", "Hola, ¿", "a.m. «", "e.g. „", "При", "Hello. ", "", null,
                " ".repeat(40) + "«")) {
            assertFalse(before.toString(), sentenceStartsAfterOpener(before))
        }
    }

    @Test fun openerFallbackOnlyAtWordStartAndOnlyWhenSwitchIsOn() {
        val app = mock<android.content.Context>()
        val prefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.view.inputmethod.InputConnection>()
        whenever(app.getSharedPreferences("kime_settings", 0)).thenReturn(prefs)
        whenever(editor.getTextBeforeCursor(any(), any())).thenReturn("Да. «")
        // 开关关着：俄语、西班牙语键盘也不自动大写，不碰输入框。
        assertFalse(englishSentenceStart(app, editor, wordStart = true))
        verifyNoInteractions(editor)
        whenever(prefs.getBoolean(ENGLISH_SENTENCE_CAPS, false)).thenReturn(true)
        whenever(editor.getCursorCapsMode(android.text.TextUtils.CAP_MODE_SENTENCES)).thenReturn(0)
        assertTrue(englishSentenceStart(app, editor, wordStart = true))
        // 打到词中间：只问一次系统，不多读光标前的文字。
        clearInvocations(editor)
        assertFalse(englishSentenceStart(app, editor, wordStart = false))
        verify(editor, never()).getTextBeforeCursor(any(), any())
    }

    @Test fun lettersCommitLiterallyEvenWithStaleChineseDecoderCandidates() {
        for (word in listOf("hello", "fuck", "you", "Hello")) {
            var state = CandidateState(candidates = listOf("核力量哦"), isComposing = true, inputText = "old")
            val editor = StringBuilder()
            for (letter in word) {
                state = requireNotNull(commitEnglishLetter(state, letter.toString(), letter.isUpperCase(), { true }) {
                    editor.append(it); true
                })
            }
            assertEquals(word, editor.toString())
            assertEquals(word, state.pendingEnglishText)
            assertFalse(state.isComposing)
            assertTrue(state.candidates.isEmpty())
        }
    }

    @Test fun letterRightAfterAGlidedWordStartsANewWordWithASpace() {
        val glided = CandidateState(pendingEnglishText = "hello", glidedWord = "hello", associationCandidates = listOf("hero"))
        val editor = StringBuilder("hello")
        val next = requireNotNull(commitEnglishLetter(glided, "w", false, { true }, afterGlide = true) {
            editor.append(it); true
        })
        assertEquals("hello w", editor.toString())
        assertEquals("w", next.pendingEnglishText)
        assertEquals("", next.glidedWord)
        // 之后的字母照常接在新词后面，不再补空格。
        val after = requireNotNull(commitEnglishLetter(next, "o", false, { true }) { editor.append(it); true })
        assertEquals("hello wo", editor.toString())
        assertEquals("wo", after.pendingEnglishText)
    }

    @Test fun rejectedLetterDoesNotAdvanceThePendingWord() {
        val state = CandidateState(pendingEnglishText = "hell")
        assertNull(commitEnglishLetter(state, "o", false, { error("Rejected input must not probe the editor") }) { false })
        assertEquals("hell", state.pendingEnglishText)
    }

    @Test fun letterIsDeliveredBeforeOptionalCompletionProbe() {
        val events = mutableListOf<String>()
        val next = commitEnglishLetter(CandidateState(), "a", false,
            replaceSupported = { events += "probe"; false },
            commit = { events += "commit:$it"; true })
        assertEquals(listOf("commit:a", "probe"), events)
        assertEquals("a", next?.pendingEnglishText)
        assertEquals(false, next?.englishReplaceSupported)
    }

    @Test fun rapidLiteralInputDoesNotQueryTheHostPerLetter() {
        val capability = EditorReadCapability()
        val connection = Any()
        var probes = 0
        var state = CandidateState()
        val editor = StringBuilder()
        val text = "qwertyuiopasdfghjklzxcvbnm".repeat(20)
        text.forEach { letter ->
            state = requireNotNull(commitEnglishLetter(state, letter.toString(), false,
                replaceSupported = { capability.supports(1L, connection) { probes++; true } },
                commit = { editor.append(it); true }))
        }
        assertEquals(text, editor.toString())
        assertEquals(text, state.pendingEnglishText)
        assertEquals(1, probes)
    }

    @Test fun autoCappedFirstLetterIsNotLearnedAsTyped() {
        assertEquals("xyzw", spellingWithoutAutoCap("Xyzw"))
        assertEquals("obsidian", spellingWithoutAutoCap("Obsidian"))
        // 后面还有大写是自己打的，照原样。
        assertEquals("McDonald", spellingWithoutAutoCap("McDonald"))
        assertEquals("NASA", spellingWithoutAutoCap("NASA"))
        assertEquals("hello", spellingWithoutAutoCap("hello"))
        assertEquals("I", spellingWithoutAutoCap("I"))
    }

    /** 用户 11-16：英文打 what's 时撇号接进词里，补全按整个词接着出。 */
    @Test fun apostropheJoinsTheWordBeingTyped() {
        val english = com.kingzcheung.xime.settings.InputLanguage.ENGLISH
        assertTrue(apostropheJoinsWord(english, "what"))
        assertFalse(apostropheJoinsWord(english, ""))
        assertFalse(apostropheJoinsWord(english, "what'"))
        assertFalse(apostropheJoinsWord(english, "3"))
        for (language in listOf(com.kingzcheung.xime.settings.InputLanguage.CHINESE, com.kingzcheung.xime.settings.InputLanguage.CANTONESE))
            assertFalse(language.id, apostropheJoinsWord(language, "abc"))

        val editor = StringBuilder()
        var state = CandidateState()
        for (key in listOf("W", "h", "a", "t", "'", "s")) {
            state = requireNotNull(commitEnglishLetter(state, key, key == "W", { true }) { editor.append(it); true })
        }
        assertEquals("What's", editor.toString())
        assertEquals("What's", state.pendingEnglishText)
        // 撇号不会被当成大写，也不补空格。
        val glided = CandidateState(pendingEnglishText = "don", glidedWord = "don")
        assertEquals("don'", requireNotNull(commitEnglishLetter(glided, "'", true, { true }) { true }).pendingEnglishText)
    }
}
