package com.kingzcheung.xime.settings

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 个人英文词记忆（中文 26 键手打回车、`cyime_english` 方案以及英文直输模式的生词观察/转正共用）：
 * - [FILE_NAME] (`cyime_en_memory.txt`)：常驻个人英文词库，保留原样大小写，参与英文点按补全与滑行解码。
 * - [OBSERVED_FILE_NAME] (`cyime_en_observed.txt`)：首次手打生词的观察池（最多 [MAX_OBSERVED] 条），
 *   观察期即可参与点按补全；若再次手打或点选补全上屏则立刻转正进入常驻词库（与滑行）。
 * - [DEMOTED_FILE_NAME] (`cyime_en_demoted.txt`)：中文 26 键长按降权的内置英文词。
 */
object EnglishWordMemory {
    const val FILE_NAME = "cyime_en_memory.txt"
    const val OBSERVED_FILE_NAME = "cyime_en_observed.txt"

    /**
     * Dictionary English words the user long-press deleted in Chinese 26 keys (2026-11-13: 删不掉的英文首选). They
     * have no learning record to forget, so lua/cyime_en_memory.lua puts them behind the first four candidates
     * instead; picking one again, or typing it with Enter, takes it off this list. Lowercase, one per line.
     */
    const val DEMOTED_FILE_NAME = "cyime_en_demoted.txt"
    private const val MAX_WORDS = 2000
    private const val MAX_OBSERVED = 500
    private val WORD = Regex("[A-Za-z]{2,40}")
    /** What the file holds: a word, or a word with the digits typed right after it (Abc123, 2026-11-25). */
    private val STORED = Regex("[A-Za-z]{2,40}[0-9]{0,12}")
    /** A word with digits right before the caret: letters not glued to more letters or digits in front. */
    private val WORD_DIGITS = Regex("(?<![A-Za-z0-9])([A-Za-z]{2,40})([0-9]{1,12})$")

    enum class RecordOutcome { IGNORED, OBSERVED, PROMOTED, REFRESHED }

    private data class FileStamp(val exists: Boolean, val length: Long, val modified: Long)

    private val revisionCounter = AtomicLong(0L)
    private var cachedDirPath: String? = null
    private var memoryStamp: FileStamp? = null
    private var observedStamp: FileStamp? = null
    private val memoryWords = ArrayList<String>()
    private val observedWords = ArrayList<String>()
    private var memoryDirty = false
    private var observedDirty = false

    fun revision(): Long = revisionCounter.get()

    /** A word with a capital, or of four letters and more; raw lowercase ab/abc is mostly a jianpin slip. */
    internal fun isLearnable(text: String): Boolean =
        WORD.matches(text) && (text.any { it.isUpperCase() } || text.length >= 4)

    /** 英文直输模式可进入观察池的词：含大写（AI/UI/Gemini）或 3 个字母及以上。 */
    internal fun isObservable(text: String): Boolean =
        WORD.matches(text) && (text.any { it.isUpperCase() } || text.length >= 3)

    private fun stampOf(file: File): FileStamp =
        if (file.isFile) FileStamp(true, file.length(), file.lastModified()) else FileStamp(false, 0L, 0L)

    private fun dirKey(rimeDir: File): String =
        runCatching { rimeDir.canonicalPath }.getOrElse { rimeDir.absolutePath }

    private fun ensureLoaded(rimeDir: File) {
        val key = dirKey(rimeDir)
        val memFile = File(rimeDir, FILE_NAME)
        val obsFile = File(rimeDir, OBSERVED_FILE_NAME)
        val curMemStamp = stampOf(memFile)
        val curObsStamp = stampOf(obsFile)
        if (cachedDirPath != key) {
            cachedDirPath = key
            memoryWords.clear()
            observedWords.clear()
            if (memFile.isFile) {
                runCatching { memoryWords.addAll(compactByLowercase(memFile.readLines(), MAX_WORDS)) }
            }
            if (obsFile.isFile) {
                runCatching { observedWords.addAll(compactByLowercase(obsFile.readLines(), MAX_OBSERVED)) }
            }
            memoryStamp = curMemStamp
            observedStamp = curObsStamp
            memoryDirty = false
            observedDirty = false
            return
        }
        if (!memoryDirty && memoryStamp != curMemStamp) {
            memoryWords.clear()
            if (memFile.isFile) {
                runCatching { memoryWords.addAll(compactByLowercase(memFile.readLines(), MAX_WORDS)) }
            }
            memoryStamp = curMemStamp
        }
        if (!observedDirty && observedStamp != curObsStamp) {
            observedWords.clear()
            if (obsFile.isFile) {
                runCatching { observedWords.addAll(compactByLowercase(obsFile.readLines(), MAX_OBSERVED)) }
            }
            observedStamp = curObsStamp
        }
    }

    private fun preferCasing(incoming: String, existing: String): String =
        if (incoming.none(Char::isUpperCase) && existing.any(Char::isUpperCase)) existing else incoming

    /** 兼容旧路径：中文 26 键 / 英文单词方案按回车提交生词直接记入常驻库。 */
    @Synchronized
    fun remember(rimeDir: File, text: String) {
        // A word, or a word with digits after it (Abc123 + Enter, 11-25).
        if (!STORED.matches(text) || !isLearnable(text.trimEnd { it in '0'..'9' })) return
        ensureLoaded(rimeDir)
        val idx = memoryWords.indexOfFirst { it.equals(text, ignoreCase = true) }
        val sameTail = memoryWords.lastOrNull() == text
        val removedObs = observedWords.removeAll { it.equals(text, ignoreCase = true) }
        if (removedObs) observedDirty = true
        if (!sameTail) {
            if (idx >= 0) memoryWords.removeAt(idx)
            memoryWords.add(text)
            while (memoryWords.size > MAX_WORDS) memoryWords.removeAt(0)
            val memFile = File(rimeDir, FILE_NAME)
            runCatching {
                write(memFile, memoryWords)
                memoryStamp = stampOf(memFile)
                memoryDirty = false
            }
            revisionCounter.incrementAndGet()
        }
        undemote(rimeDir, text)
    }

    /**
     * 英文直输手打结束一个词（空格/回车/标点）：
     * - 内置词库已有的全小写常用词跳过；
     * - 已经在常驻库中则更新近用顺位；
     * - 在观察池中见过的词直接转正进入常驻库并刷新版本；
     * - 首次见到的生词写入观察池（不阻塞写盘，待后台 flush 或转正时持久化）。
     */
    @Synchronized
    fun recordTypedWord(rimeDir: File, text: String, builtinKnown: Boolean = false): RecordOutcome {
        if (!isObservable(text)) return RecordOutcome.IGNORED
        val hasInternalUpper = text.drop(1).any(Char::isUpperCase)
        if (builtinKnown && !hasInternalUpper) return RecordOutcome.IGNORED
        ensureLoaded(rimeDir)

        val memIdx = memoryWords.indexOfFirst { it.equals(text, ignoreCase = true) }
        if (memIdx >= 0) {
            val existing = memoryWords[memIdx]
            val chosen = preferCasing(text, existing)
            if (memIdx != memoryWords.lastIndex || chosen != existing) {
                memoryWords.removeAt(memIdx)
                memoryWords.add(chosen)
                memoryDirty = true
                if (chosen != existing) revisionCounter.incrementAndGet()
            }
            undemote(rimeDir, chosen)
            return RecordOutcome.REFRESHED
        }

        val obsIdx = observedWords.indexOfFirst { it.equals(text, ignoreCase = true) }
        if (obsIdx >= 0) {
            val existing = observedWords.removeAt(obsIdx)
            val chosen = preferCasing(text, existing)
            memoryWords.add(chosen)
            while (memoryWords.size > MAX_WORDS) memoryWords.removeAt(0)
            persistNow(rimeDir, memory = true, observed = true)
            undemote(rimeDir, chosen)
            revisionCounter.incrementAndGet()
            return RecordOutcome.PROMOTED
        }

        observedWords.add(text)
        while (observedWords.size > MAX_OBSERVED) observedWords.removeAt(0)
        observedDirty = true
        return RecordOutcome.OBSERVED
    }

    /**
     * 用户主动在候选栏点选英文补全候选：
     * - 若该词还在观察池里，或是不在内置词库的新词，立刻转正进常驻库（滑行随之可用）。
     */
    @Synchronized
    fun recordPickedWord(rimeDir: File, text: String, builtinKnown: Boolean = false): RecordOutcome {
        if (!isObservable(text)) return RecordOutcome.IGNORED
        ensureLoaded(rimeDir)

        val memIdx = memoryWords.indexOfFirst { it.equals(text, ignoreCase = true) }
        if (memIdx >= 0) {
            val existing = memoryWords[memIdx]
            val chosen = preferCasing(text, existing)
            if (memIdx != memoryWords.lastIndex || chosen != existing) {
                memoryWords.removeAt(memIdx)
                memoryWords.add(chosen)
                memoryDirty = true
                if (chosen != existing) revisionCounter.incrementAndGet()
            }
            undemote(rimeDir, chosen)
            return RecordOutcome.REFRESHED
        }

        val obsIdx = observedWords.indexOfFirst { it.equals(text, ignoreCase = true) }
        if (obsIdx >= 0) {
            val existing = observedWords.removeAt(obsIdx)
            val chosen = preferCasing(text, existing)
            memoryWords.add(chosen)
            while (memoryWords.size > MAX_WORDS) memoryWords.removeAt(0)
            persistNow(rimeDir, memory = true, observed = true)
            undemote(rimeDir, chosen)
            revisionCounter.incrementAndGet()
            return RecordOutcome.PROMOTED
        }

        val hasInternalUpper = text.drop(1).any(Char::isUpperCase)
        if (!builtinKnown || hasInternalUpper) {
            memoryWords.add(text)
            while (memoryWords.size > MAX_WORDS) memoryWords.removeAt(0)
            persistNow(rimeDir, memory = true, observed = false)
            undemote(rimeDir, text)
            revisionCounter.incrementAndGet()
            return RecordOutcome.PROMOTED
        }
        return RecordOutcome.IGNORED
    }

    /** 点按英文前缀补全：先返回常驻库匹配词，再返回观察池匹配词（均按最近使用优先）。 */
    @Synchronized
    fun completions(rimeDir: File, prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        ensureLoaded(rimeDir)
        if (memoryWords.isEmpty() && observedWords.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<String>(limit)
        fun collectFrom(source: List<String>) {
            for (i in source.lastIndex downTo 0) {
                val word = source[i]
                if (!word.startsWith(prefix, ignoreCase = true)) continue
                val formatted = formatCompletionCase(word, prefix)
                if (seen.add(formatted.lowercase())) {
                    result.add(formatted)
                    if (result.size >= limit) return
                }
            }
        }
        collectFrom(memoryWords)
        if (result.size < limit) collectFrom(observedWords)
        return result
    }

    private fun formatCompletionCase(stored: String, typedPrefix: String): String = when {
        typedPrefix.length > 1 && typedPrefix.all(Char::isUpperCase) -> stored.uppercase()
        stored.any(Char::isUpperCase) -> stored
        typedPrefix.firstOrNull()?.isUpperCase() == true -> stored.replaceFirstChar(Char::uppercaseChar)
        else -> stored
    }

    /** 常驻个人英文词列表（最近使用在前，保留原始大小写）。 */
    @Synchronized
    fun promotedWords(rimeDir: File): List<String> {
        ensureLoaded(rimeDir)
        return memoryWords.asReversed().toList()
    }

    /** 词是否在常驻库或观察池中（供长按候选择机判断能否遗忘）。 */
    @Synchronized
    fun contains(rimeDir: File, word: String): Boolean {
        if (!STORED.matches(word)) return false
        ensureLoaded(rimeDir)
        return memoryWords.any { it.equals(word, ignoreCase = true) } ||
            observedWords.any { it.equals(word, ignoreCase = true) }
    }

    @Synchronized
    fun flush(rimeDir: File) {
        val key = dirKey(rimeDir)
        if (cachedDirPath != key) return
        persistNow(rimeDir, memory = memoryDirty, observed = observedDirty)
    }

    private fun persistNow(rimeDir: File, memory: Boolean, observed: Boolean) {
        if (memory) {
            val memFile = File(rimeDir, FILE_NAME)
            runCatching {
                write(memFile, memoryWords)
                memoryStamp = stampOf(memFile)
                memoryDirty = false
            }
        }
        if (observed) {
            val obsFile = File(rimeDir, OBSERVED_FILE_NAME)
            runCatching {
                write(obsFile, observedWords)
                observedStamp = stampOf(obsFile)
                observedDirty = false
            }
        }
    }

    /**
     * The word and the digits right before the caret (text before it: "见 Abc123" -> Abc, 123), when the word is one
     * worth remembering ([isLearnable]); null otherwise.
     */
    internal fun wordWithDigits(before: String): Pair<String, String>? {
        val match = WORD_DIGITS.find(before) ?: return null
        val word = match.groupValues[1]
        return if (isLearnable(word)) word to match.groupValues[2] else null
    }

    /**
     * Digits typed right after an English word (Abc, then 1 2 3 on the number row) are kept with it, so typing abc
     * offers Abc123. Each digit extends the newest line (Abc1 -> Abc12 -> Abc123) instead of adding another.
     */
    @Synchronized
    fun rememberWithDigits(rimeDir: File, word: String, digits: String) {
        if (!isLearnable(word) || digits.isEmpty() || digits.length > 12 || digits.any { it !in '0'..'9' }) return
        val token = word + digits
        ensureLoaded(rimeDir)
        val last = memoryWords.lastOrNull()
        if (last == token) return
        if (last != null && last.length > word.length && token.startsWith(last) && last.startsWith(word) &&
            last.drop(word.length).all { it in '0'..'9' }) {
            memoryWords[memoryWords.lastIndex] = token
        } else {
            val idx = memoryWords.indexOfFirst { it.equals(token, ignoreCase = true) }
            if (idx >= 0) memoryWords.removeAt(idx)
            memoryWords.add(token)
            while (memoryWords.size > MAX_WORDS) memoryWords.removeAt(0)
        }
        persistNow(rimeDir, memory = true, observed = false)
        revisionCounter.incrementAndGet()
        undemote(rimeDir, token)
    }

    /**
     * Any keyboard, any mode (11-25 他：英文直出、九键、14 键里打的 Abc123 也要记住): called after one character that is not a
     * letter went into the document, [before] being the text before the caret with that character last. A digit extends
     * the word right before it (Abc1 -> Abc12, [rememberWithDigits]); a space or a mark ends the word before it, which is
     * remembered when it has digits or a capital after its first letter (iPhone, CyIME, GPT4). Plain words (hello, The)
     * are not: those are dictionary words or a capital at the start of a sentence.
     */
    @Synchronized
    fun rememberTyped(rimeDir: File, before: String) {
        val last = before.lastOrNull() ?: return
        if (last.isLetter()) return
        if (last in '0'..'9') {
            wordWithDigits(before)?.let { (word, digits) -> rememberWithDigits(rimeDir, word, digits) }
            return
        }
        val token = TYPED.find(before.dropLast(1))?.value ?: return
        val word = token.trimEnd { it in '0'..'9' }
        val shaped = token.length > word.length || word.drop(1).any { it.isUpperCase() }
        if (shaped && isLearnable(word) && token.length <= 52) remember(rimeDir, token)
    }

    /** A word, maybe with digits, right at the end: not glued to more letters or digits in front. */
    private val TYPED = Regex("(?<![A-Za-z0-9])[A-Za-z]{2,40}[0-9]{0,12}$")
    /** Long-press delete of a dictionary English word: it stays typable, behind the first four candidates. */
    @Synchronized
    fun demote(rimeDir: File, text: String): Boolean {
        if (!WORD.matches(text)) return false
        val file = File(rimeDir, DEMOTED_FILE_NAME)
        val word = text.lowercase()
        return runCatching {
            val lines = if (file.exists()) file.readLines().map { it.trim() } else emptyList()
            if (word !in lines) {
                if (lines.size >= MAX_WORDS) write(file, (lines + word).takeLast(MAX_WORDS)) else file.appendText("$word\n")
            }
            true
        }.getOrDefault(false)
    }

    /** Typing the word again with Enter brings it back to its dictionary place. */
    @Synchronized
    fun undemote(rimeDir: File, text: String) {
        val file = File(rimeDir, DEMOTED_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val lines = file.readLines().map { it.trim() }
            val kept = lines.filter { it.isNotEmpty() && !it.equals(text, ignoreCase = true) }
            if (kept.size != lines.size) write(file, kept)
        }
    }

    /** Long-press delete forgets the word in every spelling; false when it was never remembered or observed. */
    @Synchronized
    fun forget(rimeDir: File, text: String): Boolean {
        if (!STORED.matches(text)) return false
        ensureLoaded(rimeDir)
        val removedMem = memoryWords.removeAll { it.equals(text, ignoreCase = true) }
        val removedObs = observedWords.removeAll { it.equals(text, ignoreCase = true) }
        if (!removedMem && !removedObs) return false
        persistNow(rimeDir, memory = removedMem, observed = removedObs)
        if (removedMem) revisionCounter.incrementAndGet()
        return true
    }

    private fun compactByLowercase(lines: List<String>, maxCount: Int): List<String> {
        val seenLower = HashSet<String>()
        val result = ArrayList<String>()
        for (i in lines.lastIndex downTo 0) {
            val word = lines[i].trim()
            if (!STORED.matches(word)) continue
            if (seenLower.add(word.lowercase())) {
                result.add(word)
                if (result.size >= maxCount) break
            }
        }
        result.reverse()
        return result
    }

    /** Latest spelling of each word last, at most [MAX_WORDS]. */
    internal fun compact(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { STORED.matches(it) }.asReversed().distinct().take(MAX_WORDS).asReversed()

    private fun write(file: File, words: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(words.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
