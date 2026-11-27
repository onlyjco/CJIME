package com.kingzcheung.xime.service

import android.graphics.Typeface
import android.view.inputmethod.InputConnection
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/** Routes transient glide text without changing the engine's candidate state. */
internal class GlidePreviewRouter(
    private val session: () -> Long,
    private val useInputBox: () -> Boolean,
    private val input: (String, Long) -> Unit,
    private val clearInput: () -> Unit,
    private val candidate: (String) -> Unit,
) {
    fun show(text: String, expectedSession: Long) {
        if (session() != expectedSession) return
        if (useInputBox()) {
            candidate("")
            input(text, expectedSession)
        } else {
            clearInput()
            candidate(text.trim())
        }
    }

    fun end() { candidate(""); clearInput() }
}

/** Temporary composing text owned by one glide and one editor connection. */
internal class GlideInputPreview(
    private val connection: () -> InputConnection?,
    private val session: () -> Long,
    private val baseline: () -> String?,
    private val canStart: () -> Boolean,
    private val mark: (String) -> Unit,
    private val owns: (String) -> Boolean,
    private val clear: () -> Unit,
    private val existingRanges: () -> List<IntRange> = { emptyList() },
) {
    private var owner: InputConnection? = null
    private var ownerSession = 0L
    private var original: String? = null
    private var originalRanges: List<IntRange> = emptyList()
    private var written: String? = null

    fun show(text: String, expectedSession: Long) = show(text, expectedSession, complete = false)
    fun showComplete(text: String, expectedSession: Long) = show(text, expectedSession, complete = true)

    private fun styledComposing(text: String, ranges: List<IntRange>): CharSequence {
        if (text.isEmpty()) return text
        // 与正式输入和局部修改共用同一套逐字底色边界与滑行下划线，避免滑行转正式或进入局部修改时字距跳动。
        return sentenceCodeHighlight(text, 0, 0, ranges)
    }

    private fun glidePrefixBoundary(base: String, full: String): Int {
        if (base.isEmpty()) return 0
        var fi = 0
        var matchedAll = true
        for (ch in base) {
            if (ch == ' ' || ch == '\'') continue
            while (fi < full.length && (full[fi] == ' ' || full[fi] == '\'')) fi++
            if (fi < full.length && full[fi] == ch) {
                fi++
            } else {
                matchedAll = false
                break
            }
        }
        var start = if (matchedAll) fi else base.commonPrefixWith(full).length
        while (start < full.length && (full[start] == ' ' || full[start] == '\'')) start++
        return start.coerceAtMost(full.length)
    }

    private fun show(text: String, expectedSession: Long, complete: Boolean) {
        if (session() != expectedSession) return
        if (text.isEmpty()) { end(); return }
        if (owner == null) {
            if (!canStart()) return
            val ic = connection() ?: return
            if (runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
            owner = ic
            ownerSession = session()
            original = baseline()
            originalRanges = existingRanges()
        }
        val ic = owner ?: return
        if (connection() !== ic || session() != ownerSession || written?.let { !owns(it) } == true ||
            runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return
        val prefix = original.orEmpty()
        val separator = if (prefix.isNotEmpty() && !prefix.last().isWhitespace() && !text.first().isWhitespace()) " " else ""
        val preview = if (complete) text else prefix + separator + text
        if (preview == written) return
        val ranges = ArrayList<IntRange>(originalRanges.size + 1)
        for (r in originalRanges) {
            val s = r.first.coerceIn(0, preview.length)
            val e = (r.last + 1).coerceIn(s, preview.length)
            if (e > s) ranges.add(s until e)
        }
        val newStart = if (!complete) {
            var idx = (prefix.length + separator.length).coerceAtMost(preview.length)
            while (idx < preview.length && preview[idx].isWhitespace()) idx++
            idx
        } else {
            glidePrefixBoundary(prefix, preview)
        }
        if (preview.length > newStart) ranges.add(newStart until preview.length)
        val shown = styledComposing(preview, ranges)
        if (runCatching { ic.setComposingText(shown, 1) }.getOrDefault(false)) {
            written = preview
            mark(preview)
        }
    }

    /** Read the editor context before our temporary text, only while we still own it. */
    fun textBeforePreview(limit: Int): String? {
        val ic = owner ?: return null
        val preview = written ?: return null
        if (original != null || connection() !== ic || session() != ownerSession || !owns(preview) ||
            !runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) return null
        val before = runCatching { ic.getTextBeforeCursor(limit + preview.length, 0)?.toString() }.getOrNull() ?: return null
        if (!before.endsWith(preview)) return null
        return before.dropLast(preview.length).takeLast(limit)
    }

    fun end() {
        val ic = owner
        val text = written
        if (ic != null && text != null && connection() === ic && session() == ownerSession && owns(text) &&
            runCatching { ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)) {
            val before = original
            if (before != null && runCatching { ic.setComposingText(styledComposing(before, originalRanges), 1) }.getOrDefault(false)) mark(before)
            else if (before == null) clear()
        }
        owner = null; original = null; originalRanges = emptyList(); written = null
    }

    fun release() { owner = null; original = null; originalRanges = emptyList(); written = null }
}
