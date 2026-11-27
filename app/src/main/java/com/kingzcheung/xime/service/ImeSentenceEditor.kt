package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SENTENCE_CODE_HIGHLIGHT_COLOR = 0x663399ff
internal const val SENTENCE_CODE_HIGHLIGHT_COLOR_ALT = 0x663399fe
/** 非零极低透明底色（1/255 灰，奇偶交替最低位）：阻止 Android TextLine / Compose TextField 把相邻同色 BackgroundColorSpan 合并成整段，确保选区边界与末音节字偶距全程零跳动。 */
internal const val SENTENCE_CODE_NEUTRAL_BG = 0x01808080
internal const val SENTENCE_CODE_NEUTRAL_BG_ALT = 0x01808081

/** Use parcelable system spans and keep the same character boundaries for each selection. */
internal fun sentenceCodeHighlight(
    text: String,
    from: Int,
    to: Int,
    underlineRanges: List<IntRange> = emptyList(),
): android.text.SpannableString =
    android.text.SpannableString(text).apply {
        // 显式附带 NORMAL 样式阻止宿主 EditText 默认给整段组合编码加下划线，仅对滑行片段区间画下划线。
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (r in underlineRanges) {
            val s = r.first.coerceIn(0, text.length)
            val e = (r.last + 1).coerceIn(s, text.length)
            if (e > s) setSpan(android.text.style.UnderlineSpan(), s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var index = 0
        var charStep = 0
        while (index < text.length) {
            val next = index + Character.charCount(text.codePointAt(index))
            val odd = (charStep and 1) != 0
            val selected = to > from && index < to && next > from
            val color = when {
                selected && odd -> SENTENCE_CODE_HIGHLIGHT_COLOR_ALT
                selected -> SENTENCE_CODE_HIGHLIGHT_COLOR
                odd -> SENTENCE_CODE_NEUTRAL_BG_ALT
                else -> SENTENCE_CODE_NEUTRAL_BG
            }
            setSpan(android.text.style.BackgroundColorSpan(color), index, next, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index = next
            charStep++
        }
    }

private fun remapSentenceUnderlineRanges(
    baseRanges: List<IntRange>,
    editStart: Int,
    editEnd: Int,
    replacementLength: Int,
    newLength: Int,
    keepInside: Boolean,
    replacementUnderlines: List<IntRange> = emptyList(),
    rightDelta: Int = replacementLength - (editEnd - editStart),
): List<IntRange> {
    if ((baseRanges.isEmpty() && replacementUnderlines.isEmpty()) || newLength <= 0) return emptyList()
    val out = ArrayList<IntRange>(baseRanges.size + replacementUnderlines.size)
    for (r in baseRanges) {
        val rs = r.first
        val re = r.last + 1
        // 左侧未改动段
        val leftEnd = minOf(re, editStart)
        if (leftEnd > rs) {
            val s = rs.coerceIn(0, newLength)
            val e = leftEnd.coerceIn(s, newLength)
            if (e > s) out.add(s until e)
        }
        // 中间选中段（仅在编码未替换时保留原有滑行下划线）
        if (keepInside) {
            val midStart = maxOf(rs, editStart)
            val midEnd = minOf(re, editEnd)
            if (midEnd > midStart) {
                val s = midStart.coerceIn(0, newLength)
                val e = midEnd.coerceIn(s, newLength)
                if (e > s) out.add(s until e)
            }
        }
        // 右侧未改动段（按实际右移偏移平移，避免清空段时折叠分隔符造成 1 字符错位）
        val rightStart = maxOf(rs, editEnd)
        if (re > rightStart) {
            val s = (rightStart + rightDelta).coerceIn(0, newLength)
            val e = (re + rightDelta).coerceIn(s, newLength)
            if (e > s) out.add(s until e)
        }
    }
    if (!keepInside && replacementLength > 0) {
        for (r in replacementUnderlines) {
            val s = (editStart + r.first).coerceIn(editStart, minOf(editStart + replacementLength, newLength))
            val e = (editStart + r.last + 1).coerceIn(s, minOf(editStart + replacementLength, newLength))
            if (e > s) out.add(s until e)
        }
    }
    return out
}

/** An isolated candidate lookup; the live composition changes only on confirmation. */
data class SentenceEditSession(
        val inputSessionId: Long,
        val schema: String,
        val snapshot: RimeEngine.SentenceEditSnapshot,
        val start: Int,
        val end: Int,
    ) {
        val originalCode: String get() = snapshot.input.substring(snapshot.boundaries[start], snapshot.boundaries[end])
        val originalWord: String get() = snapshot.sentence.substring(start, end)
        fun sentence(word: String) = snapshot.sentence.take(start) + word + snapshot.sentence.drop(end)
    }

/** Original character where the remaining unchanged code begins, or null after unrelated edits. */
internal fun SentenceEditSession.originalRemainingStart(code: String, confirmedCode: String): Int? {
    fun keys(value: String) = value.filterNot { it == '\'' || it.isWhitespace() }
    val input = snapshot.input
    val bounds = snapshot.boundaries
    val first = bounds[start]
    return (start until end).firstOrNull { index ->
        keys(input.substring(first, bounds[index])) == keys(confirmedCode) &&
            keys(input.substring(bounds[index], bounds[end])) == keys(code)
    }
}

/** Map sentence characters to the currently displayed spelling, only when the mapping is reliable. */
internal fun SentenceEditSession.displaySelection(text: String): IntRange? {
    val syllables = Regex("[A-Za-züÜ;]+").findAll(text).toList()
    val range = if (syllables.size == snapshot.sentence.length) {
        syllables[start].range.first until (syllables[end - 1].range.last + 1)
    } else {
        fun keys(s: String) = s.filterNot { it == '\'' || it.isWhitespace() }.lowercase()
        if (keys(text) != keys(snapshot.input)) return null
        val display = text.replace(' ', '\'')
        com.kingzcheung.xime.rime.mapCodeOffset(snapshot.input, display, snapshot.boundaries[start]) until
            com.kingzcheung.xime.rime.mapCodeOffset(snapshot.input, display, snapshot.boundaries[end])
    }
    return range.takeIf { it.first >= 0 && it.last < text.length && !it.isEmpty() }
}

internal class ImeSentenceEditor(private val service: XimeInputMethodService) {
    private var previewOwner: SentenceEditSession? = null
    private var previewConnection: android.view.inputmethod.InputConnection? = null
    private var previewBase: String? = null
    private var previewBaseUnderlines: List<IntRange> = emptyList()
    private var previewWritten: String? = null
    private var previewCaret = 0
    /** 记录最近一次局部修改替换段的逐字滑行标记（不依赖输入框模式，供 apply 精准回写下划线）。 */
    private var lastReplacementOwner: SentenceEditSession? = null
    private var lastReplacementCode: String = ""
    private var lastReplacementFlags: List<Boolean> = emptyList()
    private var dragConnection: android.view.inputmethod.InputConnection? = null
    private var dragText: String? = null
    private var dragCaret = 0
    private var dragOwner: SentenceEditSession? = null
    private var pendingDragOwner: SentenceEditSession? = null
    private var dragPreviewScheduled = false
    private val dragPreviewTask = Runnable {
        dragPreviewScheduled = false
        pendingDragOwner?.let(::applyDragPreview)
    }

    private fun current(owner: SentenceEditSession): Boolean = service.uiState.value.let {
        it.inputSessionId == owner.inputSessionId && it.currentSchemaId == owner.schema && !it.isAsciiMode
    }

    /** Highlight existing composing text without changing its characters or final cursor position. */
    fun dragPreview(owner: SentenceEditSession?) {
        if (owner == null) {
            service.mainHandler.removeCallbacks(dragPreviewTask)
            pendingDragOwner = null
            dragPreviewScheduled = false
            applyDragPreview(null)
        } else {
            pendingDragOwner = owner
            if (!dragPreviewScheduled) {
                dragPreviewScheduled = true
                service.mainHandler.postDelayed(dragPreviewTask, 16)
            }
        }
    }

    private fun applyDragPreview(owner: SentenceEditSession?) {
        val ic = service.currentInputConnection
        if (owner == null) {
            val text = dragText
            if (previewOwner == null && ic != null && ic === dragConnection && text != null && service.inlinePinyinText() == text &&
                service.inlinePinyinCaret() == dragCaret && dragOwner?.let(::current) == true &&
                runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
                ic.beginBatchEdit()
                try {
                    val restored = sentenceCodeHighlight(text, 0, 0, service.candidateState.value.glideUnderlineRanges)
                    if (ic.setComposingText(restored, 1)) {
                        service.markInputBoxComposing(text)
                        service.placeInlinePinyinCaret(text, dragCaret)
                    }
                } finally { ic.endBatchEdit() }
            }
            dragConnection = null; dragText = null; dragOwner = null
            return
        }
        if (!current(owner) || service.uiState.value.inputTarget.isInternal ||
            TerminalEditor.inputTextLocation(service) != com.kingzcheung.xime.settings.SettingsPreferences.INPUT_TEXT_INPUT_BOX || ic == null) return
        if (dragConnection == null) {
            dragText = service.inlinePinyinText() ?: return
            dragCaret = service.inlinePinyinCaret()
            dragConnection = ic
        }
        val text = dragText ?: return
        if (ic !== dragConnection || service.inlinePinyinText() != text || service.inlinePinyinCaret() != dragCaret) return
        val range = owner.displaySelection(text) ?: return
        val shown = dragOwner?.let { it.displaySelection(text) }
        if (shown == range) { dragOwner = owner; return }
        if (dragOwner == null && runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val styled = sentenceCodeHighlight(text, range.first, range.last + 1, service.candidateState.value.glideUnderlineRanges)
        ic.beginBatchEdit()
        try {
            if (ic.setComposingText(styled, 1)) {
                service.markInputBoxComposing(text)
                if (!service.placeInlinePinyinCaret(text, dragCaret)) dragCaret = text.length
                dragOwner = owner
            }
        } finally { ic.endBatchEdit() }
    }

    /** Only replace our own composing span; never touch surrounding host text. */
    fun preview(owner: SentenceEditSession, code: String, replacementUnderlines: List<IntRange> = emptyList()) {
        lastReplacementOwner = owner
        lastReplacementCode = code
        lastReplacementFlags = if (code.isEmpty() || replacementUnderlines.isEmpty()) List(code.length) { false }
            else List(code.length) { i -> replacementUnderlines.any { i in it } }
        if (!current(owner) || service.uiState.value.inputTarget.isInternal ||
            TerminalEditor.inputTextLocation(service) != com.kingzcheung.xime.settings.SettingsPreferences.INPUT_TEXT_INPUT_BOX) return
        val ic = service.currentInputConnection ?: return
        if (previewOwner == null) {
            service.mainHandler.removeCallbacks(dragPreviewTask)
            pendingDragOwner = null
            dragPreviewScheduled = false
        }
        if (previewOwner !== owner) {
            val base = service.inlinePinyinText() ?: return
            fun keys(s: String) = s.filterNot { it == '\'' || it.isWhitespace() }.lowercase()
            val syllables = Regex("[A-Za-züÜ;]+").findAll(base).toList()
            if (keys(base) != keys(owner.snapshot.input) && syllables.size != owner.snapshot.sentence.length) return
            previewOwner = owner; previewConnection = ic; previewBase = base; previewWritten = base
            previewBaseUnderlines = service.candidateState.value.glideUnderlineRanges
            previewCaret = service.inlinePinyinCaret()
        }
        if (previewConnection !== ic || service.inlinePinyinText() != previewWritten ||
            service.inlinePinyinCaret() != previewCaret || runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val base = previewBase ?: return
        val from = owner.snapshot.boundaries[owner.start]
        val to = owner.snapshot.boundaries[owner.end]
        val visible = base.replace(' ', '\'')
        val syllables = Regex("[A-Za-züÜ;]+").findAll(base).toList()
        val syllableDisplay = syllables.size == owner.snapshot.sentence.length
        val start = if (syllableDisplay) syllables[owner.start].range.first
            else com.kingzcheung.xime.rime.mapCodeOffset(owner.snapshot.input, visible, from)
        val end = if (syllableDisplay) syllables[owner.end - 1].range.last + 1
            else com.kingzcheung.xime.rime.mapCodeOffset(owner.snapshot.input, visible, to)
        if (start !in 0..end || end > base.length) return
        val before = base.take(start)
        val after = base.drop(end)
        // Keep the original syllable separators while the selected code is unchanged.
        val shownCode = if (code == owner.originalCode) base.substring(start, end) else code
        val leftTrimmed = before.trimEnd(' ', '\'')
        val rightTrimmed = after.trimStart(' ', '\'')
        val text = if (code.isNotEmpty()) before + shownCode + after else {
            val separator = before.lastOrNull()?.takeIf { it == ' ' || it == '\'' }
                ?: after.firstOrNull()?.takeIf { it == ' ' || it == '\'' }
            leftTrimmed + (if (leftTrimmed.isNotEmpty() && rightTrimmed.isNotEmpty()) separator?.toString().orEmpty() else "") + rightTrimmed
        }
        val caret = if (code.isNotEmpty()) start + shownCode.length else leftTrimmed.length.coerceAtMost(text.length)
        if (code.isEmpty() && text == previewWritten && caret == previewCaret) return
        val rightDelta = if (code.isNotEmpty()) {
            shownCode.length - (end - start)
        } else {
            val oldRightTrimmedStart = base.length - rightTrimmed.length
            val newRightTrimmedStart = text.length - rightTrimmed.length
            newRightTrimmedStart - oldRightTrimmedStart
        }
        val underlines = remapSentenceUnderlineRanges(
            baseRanges = previewBaseUnderlines,
            editStart = start,
            editEnd = end,
            replacementLength = if (code.isNotEmpty()) shownCode.length else 0,
            newLength = text.length,
            keepInside = code == owner.originalCode && replacementUnderlines.isEmpty(),
            replacementUnderlines = replacementUnderlines,
            rightDelta = rightDelta,
        )
        val styled = if (code.isNotEmpty()) sentenceCodeHighlight(text, start, start + shownCode.length, underlines)
            else if (text.isNotEmpty()) sentenceCodeHighlight(text, 0, 0, underlines) else text
        ic.beginBatchEdit()
        try {
            if (runCatching { ic.setComposingText(styled, 1) }.getOrDefault(false)) {
                previewWritten = text
                service.markInputBoxComposing(text)
                previewCaret = if (service.placeInlinePinyinCaret(text, caret)) caret else text.length
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Rebuild the remaining keys as ordinary, unselected composing input. */
    fun prepareEmptyClear(owner: SentenceEditSession, reply: (Boolean) -> Unit) {
        service.keyRouter.postRimeJob("sentence_edit_empty_clear") {
            val snapshot = owner.snapshot
            val remaining = snapshot.input.removeRange(snapshot.boundaries[owner.start], snapshot.boundaries[owner.end])
            val applied = current(owner) && service.rimeEngine.isCandidateRevisionCurrent(snapshot.revision) &&
                service.rimeEngine.setInput(remaining)
            withContext(Dispatchers.Main) { reply(applied && current(owner)) }
        }
    }

    fun endPreview(owner: SentenceEditSession, restore: Boolean) {
        val ic = service.currentInputConnection
        if (restore && previewOwner === owner && previewConnection === ic && ic != null && current(owner) &&
            service.inlinePinyinText() == previewWritten && service.inlinePinyinCaret() == previewCaret &&
            runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
            previewWritten?.let { service.placeInlinePinyinCaret(it, it.length) }
            service.sessionController.refreshInputBoxComposition()
        }
        if (previewOwner === owner) {
            previewOwner = null; previewConnection = null; previewBase = null; previewBaseUnderlines = emptyList(); previewWritten = null; previewCaret = 0
        }
        if (lastReplacementOwner === owner) {
            lastReplacementOwner = null; lastReplacementCode = ""; lastReplacementFlags = emptyList()
        }
    }

    fun open(start: Int, end: Int, revision: Long, reply: (SentenceEditSession?) -> Unit) {
        val state = service.uiState.value
        if (state.isAsciiMode || !com.kingzcheung.xime.ui.keyboard.usesSentenceDragEdit(state.inputProfile)) { reply(null); return }
        service.keyRouter.postRimeJob("sentence_edit_open") {
            val snapshot = service.rimeEngine.sentenceEditSnapshot(revision)
            val owner = snapshot?.takeIf { start >= 0 && start < end && end < it.boundaries.size &&
                it.sentence == service.candidateState.value.candidates.firstOrNull() }
                ?.let { SentenceEditSession(state.inputSessionId, state.currentSchemaId, it, start, end) }
            withContext(Dispatchers.Main) { reply(owner?.takeIf(::current)) }
        }
    }

    fun candidates(owner: SentenceEditSession, code: String, selected: List<com.kingzcheung.xime.rime.PinyinSyllableOption>,
                   confirmedCode: String, reply: (RimeEngine.SentenceEditCandidates) -> Unit) {
        service.keyRouter.postRimeJob("sentence_edit_lookup") {
            val originalStart = owner.originalRemainingStart(code, confirmedCode)
            val result = if (current(owner)) service.rimeEngine.sentenceEditCandidates(owner.snapshot, code, selected,
                originalStart ?: -1, if (originalStart != null) owner.end else -1)
                else RimeEngine.SentenceEditCandidates(emptyList(), emptyList())
            withContext(Dispatchers.Main) { if (current(owner)) reply(result) }
        }
    }

    fun apply(owner: SentenceEditSession, segments: List<RimeEngine.SentenceEditCandidate>, commit: Boolean, reply: (Boolean) -> Unit) {
        val leftCtx = service.predictionManager.lastCommittedText
        val schemaKey = service.keyRouter.currentGlideLearningKey()
        val learnGlide = service.keyRouter.personalChineseGlideLearningActive()
        service.keyRouter.postRimeJob("sentence_edit_apply") {
            val result = if (current(owner)) service.rimeEngine.applySentenceEdit(owner.snapshot,
                owner.start, owner.end, segments) else null
            if (result != null) {
                val goodWord = segments.joinToString("") { it.text }
                val goodCode = segments.joinToString("") { it.code }
                val sentenceAfter = owner.sentence(goodWord)
                if (goodWord != owner.originalWord || goodCode != owner.originalCode) {
                    service.predictionManager.recordSentenceEditAssociationFeedback(
                        leftCtx, owner.snapshot.sentence, owner.start, owner.end,
                        owner.originalWord, goodWord, sentenceAfter
                    )
                    if (learnGlide) {
                        // 仅在内存中更新待提交轨迹的真实编码与原始错码（同步执行避免与紧随其后的 selectCandidateAsync 竞态），
                        // 待真正 onCommitted 上屏时按最终句一次性落盘，消除多步改错中间态。
                        com.kingzcheung.xime.glide.PersonalGlideCalibration.onSentenceEditApplied(
                            service.filesDir, owner.inputSessionId, schemaKey,
                            owner.snapshot.sentence, owner.originalCode, goodCode, sentenceAfter,
                            fullInputCode = owner.snapshot.input,
                            deferredUntilCommit = true,
                        )
                    }
                }
                val repFlagsSnapshot = if (lastReplacementOwner === owner && lastReplacementCode == goodCode) lastReplacementFlags else null
                withContext(Dispatchers.Main) {
                    if (current(owner)) {
                        service.sessionController.applyRawGlideEdit(
                            oldRaw = owner.snapshot.input,
                            editStart = owner.snapshot.boundaries[owner.start],
                            editEnd = owner.snapshot.boundaries[owner.end],
                            replacement = goodCode,
                            replacementFlags = repFlagsSnapshot,
                        )
                        service.sessionController.updateUIWithResult(result)
                    }
                }
            }
            if (result != null && commit && result.candidates.isNotEmpty() && current(owner)) service.keyRouter.selectCandidateAsync(0)
            withContext(Dispatchers.Main) {
                reply(result != null)
            }
        }
    }
}
