package com.kingzcheung.xime.glide

import android.content.Context
import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme

/**
 * 滑行词表与模板缓存。
 * - 英文：随包的 english.txt（按词频排序的常用英文词，与英文补全共用）加上缩写的去撇号写法和
 *   english_glide_words.txt 的补充词（english.txt 没有的 fuck、alright 等，只给滑行用）；
 *   连着划时用 english_bigrams.txt 给前后两个词整对打分（[rankGlideChoices]）。
 * - 中文：随包的 glide_pinyin.txt（雾凇词频合并同音后的常用拼音编码，音节用空格隔开，
 *   由 tools/glide_lab/build_pinyin_lexicon.py 生成）。全拼直接连写；双拼按当前方案逐音节换码。
 * 每种词表按布局缓存一份模板。
 */
object GlideLexicon {
    private const val FILE = "english.txt"
    private const val PINYIN_FILE = "glide_pinyin.txt"
    private const val PINYIN_WORDS_FILE = "glide_pinyin_words.txt"
    @Volatile private var pinyinWords: List<String>? = null
    /** 九键编码 → 词表第几项（按 [T9Glide.codes] 的顺序）与同一条线的编码；全拼和各双拼方案各一份。 */
    private val t9Codes = HashMap<Any, T9Codes>()

    private class T9Codes(val index: LinkedHashMap<String, Int>) {
        val lines: Map<String, List<String>> by lazy { T9Glide.lines(index.keys) }
        val shapes: Map<String, List<String>> by lazy { T9Glide.shapes(index.keys) }
    }
    private data class ExtraEnglishLexicon(
        val key: Pair<Long, Long>,
        val personalWords: List<String>,
        val cellWords: List<String>,
        val displayForms: Map<String, String>,
        val personalSet: Set<String>,
        val cellSet: Set<String>,
    )

    @Volatile private var words: List<String>? = null
    @Volatile private var baseVocabulary: List<String>? = null
    @Volatile private var extraLexicon: ExtraEnglishLexicon? = null
    @Volatile private var mergedWords: Pair<Pair<Long, Long>, List<String>>? = null
    @Volatile private var pinyin: List<List<String>>? = null
    private const val CONTRACTIONS_FILE = "english_contractions.txt"
    private const val BIGRAMS_FILE = "english_bigrams.txt"
    private const val EXTRA_FILE = "english_glide_words.txt"
    @Volatile private var contractions: EnglishContractions? = null
    @Volatile private var bigrams: EnglishBigrams? = null
    /** 英文、全拼、双拼各留一份；双拼换方案（或改了键位）时按方案内容重建。 */
    private val cached = HashMap<String, Pair<Any?, GlideDecoder>>()

    private fun baseWords(context: Context): List<String> = words ?: synchronized(this) {
        words ?: vocabulary(context).filter { it.length >= 2 && it.all { char -> char in 'a'..'z' } }.also { words = it }
    }

    private fun extraRevisionKey(context: Context): Pair<Long, Long> =
        com.kingzcheung.xime.settings.EnglishWordMemory.revision() to
            com.kingzcheung.xime.settings.CellDictionaryStore.englishRevision()

    private fun extraEnglishLexicon(context: Context, key: Pair<Long, Long>): ExtraEnglishLexicon {
        extraLexicon?.takeIf { it.key == key }?.let { return it }
        return synchronized(this) {
            extraLexicon?.takeIf { it.key == key }?.let { return@synchronized it }
            val base = baseWords(context)
            val baseSet = base.toHashSet()
            val display = HashMap<String, String>()
            val personalList = ArrayList<String>()
            val personalSet = HashSet<String>()
            val cellList = ArrayList<String>()
            val cellSet = HashSet<String>()

            fun isGlidable(lower: String): Boolean =
                lower.length in 2..GlideDecoder.MAX_WORD && lower.all { it in 'a'..'z' }

            runCatching {
                val rimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context)
                com.kingzcheung.xime.settings.EnglishWordMemory.promotedWords(rimeDir)
            }.getOrDefault(emptyList()).forEach { raw ->
                val lower = raw.lowercase()
                if (!isGlidable(lower)) return@forEach
                if (raw != lower) display.putIfAbsent(lower, raw)
                if (lower !in baseSet && personalSet.add(lower)) personalList.add(lower)
            }

            runCatching {
                com.kingzcheung.xime.settings.CellDictionaryManager.store(context).activeEnglishWords()
            }.getOrDefault(emptyList()).forEach { raw ->
                val lower = raw.lowercase()
                if (!isGlidable(lower)) return@forEach
                if (raw != lower) display.putIfAbsent(lower, raw)
                if (lower !in baseSet && lower !in personalSet && cellSet.add(lower)) cellList.add(lower)
            }

            ExtraEnglishLexicon(key, personalList, cellList, display, personalSet, cellSet).also { extraLexicon = it }
        }
    }

    fun words(context: Context): List<String> {
        val key = extraRevisionKey(context)
        mergedWords?.takeIf { it.first == key }?.let { return it.second }
        return synchronized(this) {
            mergedWords?.takeIf { it.first == key }?.let { return@synchronized it.second }
            val base = baseWords(context)
            val extra = extraEnglishLexicon(context, key)
            val combined = if (extra.personalWords.isEmpty() && extra.cellWords.isEmpty()) base else {
                val insertAt = minOf(500, base.size)
                ArrayList<String>(base.size + extra.personalWords.size + extra.cellWords.size).apply {
                    addAll(base.subList(0, insertAt))
                    addAll(extra.personalWords)
                    addAll(base.subList(insertAt, base.size))
                    addAll(extra.cellWords)
                }
            }
            mergedWords = key to combined
            combined
        }
    }

    fun displayEnglishWord(word: String): String = extraLexicon?.displayForms?.get(word) ?: word

    fun extraEnglishCost(word: String): Float? {
        val extra = extraLexicon ?: return null
        return when (word) {
            in extra.personalSet -> 10.5f
            in extra.cellSet -> 12.5f
            else -> null
        }
    }

    /** english.txt 加上插进去的缩写写法（didnt、youre）和滑行补充词（fuck、alright），滑行词表和双词表共用这一个排名。 */
    private fun vocabulary(context: Context): List<String> = baseVocabulary ?: synchronized(this) {
        baseVocabulary ?: contractions(context).vocabulary(
            context.applicationContext.assets.open(FILE).bufferedReader().useLines { it.toList() },
            context.applicationContext.assets.open(EXTRA_FILE).bufferedReader().useLines { glideExtraWords(it) }
        ).also { baseVocabulary = it }
    }

    fun contractions(context: Context): EnglishContractions = contractions ?: synchronized(this) {
        contractions ?: context.applicationContext.assets.open(CONTRACTIONS_FILE).bufferedReader()
            .useLines { EnglishContractions(it.toList().asSequence()) }.also { contractions = it }
    }

    /** 英文双词表；须在后台线程调用。 */
    fun bigrams(context: Context): EnglishBigrams = bigrams ?: synchronized(this) {
        bigrams ?: context.applicationContext.assets.open(BIGRAMS_FILE).bufferedReader()
            .useLines { EnglishBigrams(vocabulary(context), it) }.also { bigrams = it }
    }

    /** 已读入的双词表与缩写表；还没读完时为空，这一笔就不按上下文重排、不换缩写。 */
    fun loadedBigrams(): EnglishBigrams? = bigrams
    fun loadedContractions(): EnglishContractions? = contractions

    /** 常用拼音编码，每项是一个词的音节，按常用程度排列。 */
    fun pinyin(context: Context): List<List<String>> = pinyin ?: synchronized(this) {
        pinyin ?: context.applicationContext.assets.open(PINYIN_FILE).bufferedReader().useLines { lines ->
            // 只有四百多个不同音节：共用同一个字符串，三万个词只多几 MB 的列表。
            val syllables = HashMap<String, String>()
            lines.map { it.trim() }.filter { it.isNotEmpty() }
                .map { line -> line.split(' ').map { syllables.getOrPut(it) { it } } }.toList()
        }.also { pinyin = it }
    }

    /** glide_pinyin.txt 每个编码最常用的词，一行对一行（同一脚本生成）。须在后台线程调用。 */
    private fun pinyinWords(context: Context): List<String> = pinyinWords ?: synchronized(this) {
        pinyinWords ?: context.applicationContext.assets.open(PINYIN_WORDS_FILE).bufferedReader()
            .useLines { lines -> lines.map { it.trim() }.toList() }.also { pinyinWords = it }
    }

    /** 布局或个人/细胞英文词变化时重建单份模板；其余情况复用。须在后台线程调用。 */
    fun decoder(context: Context, keyboard: GlideKeyboard): GlideDecoder =
        cachedDecoder("en", extraRevisionKey(context), keyboard) { words(context) }

    /**
     * 中文滑行：[doublePinyin] 为空时按全拼，否则按该双拼方案的编码。词表里没有的连划另按音节逐键认（#294）。
     * 若开启个人校准，将合并个人高频连划短语并应用调优后的音节解码参数。须在后台线程调用。
     */
    fun chineseDecoder(
        context: Context,
        keyboard: GlideKeyboard,
        doublePinyin: DoublePinyinScheme?,
        schemaKey: String = "",
        personalCalibration: Boolean = false,
    ): GlideDecoder {
        val baseKind = if (doublePinyin == null) "py" else "sp"
        // 校准态与非校准态分槽缓存，避免任意非校准调用踢掉已构建的 3 万条模板。
        val cacheKind = if (personalCalibration) "$baseKind:cal:$schemaKey" else baseKind
        val baseKey: Any? = if (personalCalibration) {
            Triple(doublePinyin, PersonalGlideCalibration.phraseRevision(), PersonalGlideCalibration.paramsRevision())
        } else {
            doublePinyin
        }
        val params = if (personalCalibration) PersonalGlideCalibration.params(context.applicationContext.filesDir)
            else SyllableGlideParams()
        return cachedDecoder(
            cacheKind,
            baseKey,
            keyboard,
            syllables = { glideSyllables(pinyin(context), doublePinyin) },
            syllableParams = params,
        ) {
            val baseCodes = pinyinCodes(pinyin(context), doublePinyin)
            if (!personalCalibration || schemaKey.isEmpty()) baseCodes
            else {
                val personal = PersonalGlideCalibration.hotPhraseCodes(context.applicationContext.filesDir, schemaKey)
                if (personal.isEmpty()) baseCodes else (personal + baseCodes).distinct()
            }
        }
    }

    /**
     * 移动中实时预览专用入口：与 [decodeStroke] 复用同一份偏移键盘和模板缓存槽，
     * 只收窄解码候选上限，杜绝预览与抬手交替重建三万套模板。
     */
    fun decodePreview(
        context: Context,
        keyboard: GlideKeyboard,
        doublePinyin: DoublePinyinScheme?,
        xs: FloatArray,
        ys: FloatArray,
        left: String? = null,
        ts: LongArray? = null,
        schemaKey: String = "",
        bucket: String = "p",
        personalCalibration: Boolean = false,
    ): List<GlideCandidate> = decodeOrEmpty {
        val filesDir = context.applicationContext.filesDir
        val calibratedKeyboard = if (personalCalibration) PersonalGlideCalibration.applyOffsets(filesDir, bucket, keyboard) else keyboard
        val decoder = chineseDecoder(context, calibratedKeyboard, doublePinyin, schemaKey, personalCalibration)
        val previewLimit = if (left.isNullOrEmpty()) 1 else 6
        val raw = decoder.decode(xs, ys, limit = previewLimit, ts = ts)
        if (raw.isEmpty()) return@decodeOrEmpty raw
        val adjusted = if (!personalCalibration || schemaKey.isEmpty() || raw.size < 2) raw else {
            val allCodes = raw.map { it.word }
            raw.map { c ->
                val delta = PersonalGlideCalibration.candidateCostDelta(filesDir, schemaKey, c.word, allCodes)
                if (delta == 0f) c else c.copy(cost = c.cost + delta)
            }.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word })
        }
        if (left.isNullOrEmpty() || adjusted.size < 2) adjusted
        else ChineseGlideContext.rerank(adjusted, left, chineseWordOf(context, doublePinyin),
            ChineseGlideContext.model(filesDir))
    }

    /**
     * 抬手后的整笔识别。随包词表读不出来时（1.15.82 有人遇到 glide_pinyin.txt 找不到，一滑整个输入法闪退）
     * 不闪退：这一笔当没认出，日志只记第一次（用户 11-12）。须在后台线程调用。
     */
    fun decodeStroke(
        context: Context,
        keyboard: GlideKeyboard,
        chinese: Boolean,
        doublePinyin: DoublePinyinScheme?,
        xs: FloatArray,
        ys: FloatArray,
        left: String? = null,
        ts: LongArray? = null,
        schemaKey: String = "",
        bucket: String = "p",
        personalCalibration: Boolean = false,
    ): List<GlideCandidate> = decodeOrEmpty {
        if (!chinese) return@decodeOrEmpty decodeTrimmingTail(xs, ys, keyboard.keyUnit) { rx, ry ->
            decoder(context, keyboard).decode(rx, ry)
        }
        val filesDir = context.applicationContext.filesDir
        val calibratedKeyboard = if (personalCalibration) PersonalGlideCalibration.applyOffsets(filesDir, bucket, keyboard) else keyboard
        val decoder = chineseDecoder(context, calibratedKeyboard, doublePinyin, schemaKey, personalCalibration)
        val raw = decodeTrimmingTail(xs, ys, calibratedKeyboard.keyUnit, ts = ts, softCompeteThreshold = 1.05f) { rx, ry, rts ->
            decoder.decode(rx, ry, limit = 10, ts = rts)
        }
        val adjusted = if (!personalCalibration || raw.isEmpty() || schemaKey.isEmpty()) raw else {
            val allCodes = raw.map { it.word }
            raw.map { c ->
                val delta = PersonalGlideCalibration.candidateCostDelta(filesDir, schemaKey, c.word, allCodes)
                if (delta == 0f) c else c.copy(cost = c.cost + delta)
            }.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word })
        }
        // 中文按前文重排（[ChineseGlideContext]）；没有前文就不碰词组模型。
        if (left.isNullOrEmpty() || adjusted.size < 2) adjusted
        else ChineseGlideContext.rerank(adjusted, left, chineseWordOf(context, doublePinyin),
            ChineseGlideContext.model(filesDir))
    }

    /** 中文滑行编码（全拼或双拼）→ 它最常用的词；全拼、各双拼方案各一份。两份词表行数对不上时一律查不到。 */
    private val chineseWords = HashMap<Any, Map<String, String>>()

    private fun chineseWordOf(context: Context, doublePinyin: DoublePinyinScheme?): (String) -> String? {
        val map = synchronized(this) {
            val key: Any = doublePinyin ?: "py"
            chineseWords[key] ?: run {
                val entries = pinyin(context); val words = pinyinWords(context)
                val out = HashMap<String, String>()
                if (words.size == entries.size) entries.forEachIndexed { index, syllables ->
                    val code = if (doublePinyin == null) syllables.joinToString("")
                        else syllables.map { doublePinyin.code(it) ?: return@forEachIndexed }.joinToString("")
                    out.putIfAbsent(code, words[index])
                }
                out.also { chineseWords[key] = it }
            }
        }
        return { map[it] }
    }

    /**
     * 整笔认不出时去掉末尾一段再认（11-17 他报「划的时候候选已经对了，一松手字没了」）：快划时抬手那一下手指还在往前甩，
     * 轨迹末端冲出最后一个键一个键宽左右，起止点对不上任何词，整笔就没有候选，什么都不出。
     * 依次去掉末尾 [TAIL_CUTS] 个键宽各认一次，取首选代价最低的那次；去掉后剩下的不到一个键宽就不再试（短划照旧按点按算）。
     * 整笔本来认得出时不走这里，结果不变。
     */
    internal fun decodeTrimmingTail(
        xs: FloatArray,
        ys: FloatArray,
        keyUnit: Float,
        decode: (FloatArray, FloatArray) -> List<GlideCandidate>,
    ): List<GlideCandidate> = decodeTrimmingTail(xs, ys, keyUnit, ts = null, softCompeteThreshold = Float.POSITIVE_INFINITY) { rx, ry, _ ->
        decode(rx, ry)
    }

    internal fun decodeTrimmingTail(
        xs: FloatArray,
        ys: FloatArray,
        keyUnit: Float,
        ts: LongArray?,
        softCompeteThreshold: Float,
        decode: (FloatArray, FloatArray, LongArray?) -> List<GlideCandidate>,
    ): List<GlideCandidate> {
        val validTs = ts?.takeIf { it.size == xs.size }
        val whole = decode(xs, ys, validTs)
        val needSoftCompete = whole.isNotEmpty() && whole.first().cost > softCompeteThreshold
        if ((whole.isNotEmpty() && !needSoftCompete) || xs.size < 3 || xs.size != ys.size || keyUnit <= 0f) return whole
        var length = 0f
        for (i in 1 until xs.size) length += kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val cuts = if (needSoftCompete) SOFT_TAIL_CUTS else TAIL_CUTS
        var kept = xs.size
        var best = whole
        var bestScore = whole.firstOrNull()?.cost ?: Float.POSITIVE_INFINITY
        for (cut in cuts) {
            if (length - cut * keyUnit < keyUnit) break
            val count = keptAfterCut(xs, ys, cut * keyUnit)
            if (count == kept || count < 2) continue
            kept = count
            val trimmed = decode(xs.copyOf(count), ys.copyOf(count), validTs?.copyOf(count))
            if (trimmed.isEmpty()) continue
            if (needSoftCompete) {
                // 软竞争加轻量截尾惩罚，只有甩尾明显拉垮整笔几何时才胜出，并把两条路的候选融合。
                val penalized = trimmed.map { it.copy(cost = it.cost + SOFT_TAIL_PENALTY) }
                if (penalized.first().cost < bestScore) {
                    val merged = LinkedHashMap<String, GlideCandidate>()
                    for (c in best) merged[c.word] = c
                    for (c in penalized) {
                        val old = merged[c.word]
                        if (old == null || c.cost < old.cost) merged[c.word] = c
                    }
                    best = merged.values.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word }).take(10)
                    bestScore = best.first().cost
                }
            } else if (best.isEmpty() || trimmed.first().cost < bestScore) {
                best = trimmed
                bestScore = trimmed.first().cost
            }
        }
        return best
    }

    /** 抬手前甩出去的那段最多试去掉几个键宽。 */
    internal val TAIL_CUTS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 2.5f)
    private val SOFT_TAIL_CUTS = floatArrayOf(0.5f, 1f)
    private const val SOFT_TAIL_PENALTY = 0.08f

    /** 从末尾往回去掉至少 [cut] 长的一段后还剩几个点。 */
    private fun keptAfterCut(xs: FloatArray, ys: FloatArray, cut: Float): Int {
        var removed = 0f
        var i = xs.size - 1
        while (i > 0 && removed < cut) {
            removed += kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            i--
        }
        return i + 1
    }

    @Volatile private var failureLogged = false

    /** 识别出错（多半是词表读不出来）时返回空；协程取消照常抛出。 */
    internal fun decodeOrEmpty(decode: () -> List<GlideCandidate>): List<GlideCandidate> = try {
        decode()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!failureLogged) {
            failureLogged = true
            com.kingzcheung.xime.util.FileLogger.w("GlideLexicon", "滑行词表读取或识别失败，这一笔不处理", e)
        }
        emptyList()
    }

    /**
     * 九键滑行出的数字串：全拼、双拼按词表比对整条线最像哪个词的编码（[dictionary] 为真）；
     * 认不出或不是拼音方案时只取起点、拐弯和终点的键。须在后台线程调用。
     */
    fun t9Digits(context: Context, keys: Map<Char, LetterKeyRect>, xs: FloatArray, ys: FloatArray,
        dictionary: Boolean, doublePinyin: DoublePinyinScheme?): String =
        t9Glide(context, keys, xs, ys, dictionary, doublePinyin, alternatives = 0).digits

    /**
     * 九键滑行：[t9Digits] 的数字串，再加同一笔排在其后的 [alternatives] 个编码各自最常用的词（#214）。
     * 九键上只差连按同一个键的词划出来是同一条线（高兴 4269464 和 高中 42694664、晚安 92626 和 早餐 926226），
     * 只能靠词频分先后，而几份词频在这几对上都不可靠；首选照常打入，这几个词放进候选栏首选之后，点了直接上屏。
     * [before] 是这一笔之前组合里已有的数字（没有就是空）：同一条线上按它挑编码（[T9Glide.preferJoint]，
     * 先打 hua 再划 xing 出 滑行 不出 华中），备选给的是整个组合的词（[T9Glide.alternativeChoices]）。
     * 词表读不出时没有备选，数字串照常。须在后台线程调用。
     */
    fun t9Glide(context: Context, keys: Map<Char, LetterKeyRect>, xs: FloatArray, ys: FloatArray,
        dictionary: Boolean, doublePinyin: DoublePinyinScheme?, alternatives: Int = T9_ALTERNATIVES,
        before: String = ""): T9GlideResult {
        if (dictionary) {
            val decoder = cachedDecoder(if (doublePinyin == null) "t9" else "t9sp", doublePinyin, T9Glide.keyboard(keys),
                T9Glide.MAX_ENDPOINT, minPath = T9Glide.MIN_PATH) { t9Codes(context, doublePinyin).index.keys.toList() }
            val found = decoder.decode(xs, ys, limit = 1 + alternatives.coerceAtLeast(0)).map { it.word }
            if (found.isNotEmpty()) {
                // 这条线上次点过备选的，先出点过的那个词（[T9GlideMemory]）；记不住、查不到时照旧。
                val remembered = runCatching {
                    val codes = t9Codes(context, doublePinyin)
                    val line = GlideDecoder.collapseRepeats(found.first())
                    T9GlideMemory.promote(found, T9GlideMemory.preferred(context.filesDir, line),
                        codes.lines[line].orEmpty(), t9WordOf(context, doublePinyin))
                }.getOrElse { found }
                // 前面已有组合时，同一条线上挑能和它连成词的编码；连不成照旧。
                val ranked = if (before.isEmpty()) remembered else runCatching {
                    val codes = t9Codes(context, doublePinyin)
                    val entries = pinyin(context)
                    // 词表第几项各音节的数字（双拼按方案换码）；有换不了的音节时为空。
                    val syllableDigits: (Int) -> List<String>? = { index ->
                        entries.getOrNull(index)?.let { syllables ->
                            syllables.map { syllable ->
                                val letters = if (doublePinyin == null) syllable else doublePinyin.code(syllable) ?: return@let null
                                T9Glide.digits(letters) ?: return@let null
                            }
                        }
                    }
                    T9Glide.preferJoint(remembered, before, { codes.shapes[it].orEmpty() }, { codes.index[it] }, syllableDigits)
                }.getOrElse { remembered }
                val words = if (alternatives <= 0) emptyList() else runCatching {
                    val codes = t9Codes(context, doublePinyin)
                    val syllableWords = t9SyllableWords(context, doublePinyin)
                    T9Glide.alternativesWithSyllable(ranked, before, alternatives, { codes.lines[it].orEmpty() },
                        { codes.shapes[it].orEmpty() }, t9WordOf(context, doublePinyin)) { syllableWords[it] }.map { it.second }
                }.getOrElse { emptyList() }
                // 左栏读音用的编码（#582）：和打入的划出来是同一条线的那些，按常用程度。
                val lineCodes = if (alternatives <= 0) emptyList() else runCatching {
                    t9Codes(context, doublePinyin).lines[GlideDecoder.collapseRepeats(ranked.first())].orEmpty()
                }.getOrElse { emptyList() }
                return T9GlideResult(ranked.first(), words, lineCodes)
            }
        }
        return T9GlideResult(T9Glide.turnDigits(xs, ys, keys), emptyList())
    }

    /** 九键编码 → 它最常用的词；两份词表行数对不上（不是同一次生成的）时一律查不到。 */
    private fun t9WordOf(context: Context, doublePinyin: DoublePinyinScheme?): (String) -> String? {
        val index = t9Codes(context, doublePinyin).index
        val words = pinyinWords(context)
        if (words.size != pinyin(context).size) return { null }
        return { code -> index[code]?.let { words.getOrNull(it) } }
    }

    /** 九键编码 → 它最常用的单音节词（一体 和 追 都是 9484，这里给 追）；全拼、各双拼方案各一份。两份词表行数对不上时为空。 */
    private val t9SyllableWords = HashMap<Any, Map<String, String>>()

    private fun t9SyllableWords(context: Context, doublePinyin: DoublePinyinScheme?): Map<String, String> = synchronized(this) {
        val key: Any = doublePinyin ?: "t9"
        t9SyllableWords[key] ?: run {
            val entries = pinyin(context); val words = pinyinWords(context)
            val out = HashMap<String, String>()
            if (words.size == entries.size) entries.forEachIndexed { index, syllables ->
                if (syllables.size != 1) return@forEachIndexed
                val letters = if (doublePinyin == null) syllables[0] else doublePinyin.code(syllables[0]) ?: return@forEachIndexed
                T9Glide.digits(letters)?.let { out.putIfAbsent(it, words[index]) }
            }
            out.also { t9SyllableWords[key] = it }
        }
    }

    private fun t9Codes(context: Context, doublePinyin: DoublePinyinScheme?): T9Codes =
        synchronized(this) {
            val key: Any = doublePinyin ?: "t9"
            t9Codes[key] ?: T9Codes(LinkedHashMap<String, Int>().apply {
                for ((code, entry) in T9Glide.codeEntries(pinyin(context), doublePinyin)) put(code, entry)
            }).also { t9Codes[key] = it }
        }

    /** 点了九键滑行的备选 [word]（这一笔打入的是 [digits]）：记下来，下次划同一条线先出它。须在后台线程调用。 */
    fun rememberT9Choice(context: Context, digits: String, word: String) {
        if (digits.isEmpty()) return
        T9GlideMemory.remember(context.filesDir, GlideDecoder.collapseRepeats(digits), word)
    }

    /** 九键滑行在首选之后给几个备选（同一条线上的单音节另可再补一个，见 [T9Glide.alternativesWithSyllable]）。 */
    const val T9_ALTERNATIVES = 2

    private fun cachedDecoder(kind: String, source: Any?, keyboard: GlideKeyboard,
        maxEndpoint: Float = GlideDecoder.MAX_ENDPOINT, syllables: (() -> List<String>)? = null,
        syllableParams: SyllableGlideParams = SyllableGlideParams(),
        minPath: Float = GlideDecoder.MIN_PATH_KEYS,
        words: () -> List<String>): GlideDecoder =
        synchronized(this) {
            cached[kind]?.takeIf { (cachedSource, decoder) -> cachedSource == source && decoder.revision == keyboard.revision }?.second
                ?: GlideDecoder(keyboard, words(), maxEndpoint, syllables?.invoke(), syllableParams, minPath).also { cached[kind] = source to it }
        }

    fun keyboard(keys: Map<Char, LetterKeyRect>): GlideKeyboard {
        val widths = keys.values.map { it.width }.sorted()
        return GlideKeyboard(widths[widths.size / 2],
            keys.mapValues { (_, rect) -> (rect.x + rect.width / 2f) to (rect.y + rect.height / 2f) })
    }
}

/**
 * 九键滑行一笔的结果：打入的数字串 [digits]，同一笔其后几个编码最常用的词 [alternatives]（可为空），
 * 和划出来是同一条线的编码 [codes]（左栏读音用，见 [T9Glide.lineReadings]）。
 */
data class T9GlideResult(val digits: String, val alternatives: List<String>, val codes: List<String> = emptyList())

/** 词的音节连成要划的字母串；同一字母串只留最常用的一次（xi an 与 xian 划法相同，交给 Rime 区分）。 */
internal fun pinyinCodes(entries: List<List<String>>, doublePinyin: DoublePinyinScheme?): List<String> =
    entries.mapNotNull { syllables ->
        if (doublePinyin == null) syllables.joinToString("")
        else syllables.map { doublePinyin.code(it) ?: return@mapNotNull null }.joinToString("")
    }.distinct()

/** 音节逐键识别用的音节编码：词表里单字的读音按常用程度在前，其余标准音节随后；双拼换成方案编码。 */
internal fun glideSyllables(entries: List<List<String>>, doublePinyin: DoublePinyinScheme?): List<String> =
    (entries.asSequence().filter { it.size == 1 }.map { it[0] } + DoublePinyinScheme.SYLLABLES.asSequence())
        .distinct()
        .mapNotNull { syllable -> if (doublePinyin == null) syllable else doublePinyin.code(syllable) }
        .distinct().toList()

/** Use the engine's reading to resolve codes such as xian (xian or xi an). */
internal fun chineseGlideSyllables(
    code: String, doublePinyin: Boolean, readings: List<String>, entries: List<List<String>>,
): List<String>? {
    if (code.isEmpty()) return null
    if (doublePinyin) return code.chunked(2).takeIf { code.length % 2 == 0 }
    val matches = entries.asSequence().filter { syllables ->
        var offset = 0
        syllables.all { syllable ->
            val matches = code.regionMatches(offset, syllable, 0, syllable.length)
            offset += syllable.length
            matches
        } && offset == code.length
    }.distinct().toList()
    for ((readingIndex, reading) in readings.withIndex()) {
        val normalized = java.text.Normalizer.normalize(reading.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("u\u0308", "v").replace(Regex("\\p{M}+"), "").replace(Regex("[1-5]"), "")
        val tokens = normalized.split(Regex("[\\s'’]+"))
        for (start in tokens.indices) {
            val suffix = tokens.drop(start)
            // An unsegmented preedit can be the raw code rather than the chosen reading.
            if (readingIndex > 0 && suffix.size == 1 && matches.size != 1) continue
            if (suffix.joinToString("") == code &&
                (suffix in matches || suffix.all { DoublePinyinScheme.FLYPY.code(it) != null })) return suffix
        }
    }
    return matches.singleOrNull()
}

/** Keep explicit apostrophes with the following syllable so deleting it also removes its separator. */
internal fun chineseCodeSyllables(
    raw: String, doublePinyin: Boolean, readings: List<String>, entries: List<List<String>>,
    allowIncomplete: Boolean = false,
): List<String>? {
    val code = raw.replace("'", "")
    fun mapToRaw(syllables: List<String>): List<String>? {
        var offset = 0
        val result = syllables.map { syllable ->
            val start = offset
            while (offset < raw.length && raw[offset] == '\'') offset++
            if (!raw.regionMatches(offset, syllable, 0, syllable.length)) return null
            offset += syllable.length
            raw.substring(start, offset)
        }.toMutableList()
        if (offset < raw.length && result.isNotEmpty() && raw.drop(offset).all { it == '\'' }) {
            result[result.lastIndex] += raw.substring(offset)
            offset = raw.length
        }
        return result.takeIf { offset == raw.length }
    }
    if (doublePinyin && allowIncomplete && code.isNotEmpty()) {
        // Prefer the engine's actual key boundaries; full-pinyin comments will not match the keys.
        for (reading in readings) {
            val tokens = reading.lowercase().split(Regex("[\\s'’]+"))
            for (start in tokens.indices) {
                val suffix = tokens.drop(start)
                if (suffix.all { it.length in 1..2 && it.all { c -> c in 'a'..'z' || c == ';' } } &&
                    suffix.joinToString("") == code) mapToRaw(suffix)?.let { return it }
            }
        }
        // Explicit separators are boundaries; an unfinished last key remains one deletion.
        val parts = raw.split('\'').filter { it.isNotEmpty() }
        return mapToRaw(parts.flatMap { it.chunked(2) })
    }
    return mapToRaw(chineseGlideSyllables(code, doublePinyin, readings, entries) ?: return null)
}

/**
 * 滑行接管后这一笔什么都没出时记一行日志（11-17「松手字没了」查不到原因：这几处出口以前都不留痕）。
 * 只记原因、点数、轨迹长几个键宽、起点在不在字母键上、有没有实时预览，不记坐标和字。
 */
fun glideDropNote(reason: String, keys: Map<Char, com.kingzcheung.xime.keyboard.LetterKeyRect>, xs: FloatArray, ys: FloatArray, preview: Boolean): String {
    val unit = keys.values.map { minOf(it.width, it.height) }.sorted().let { it.getOrNull(it.size / 2) } ?: 0f
    var length = 0f
    for (i in 1 until minOf(xs.size, ys.size)) length += kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    val onLetter = xs.isNotEmpty() && ys.isNotEmpty() &&
        keys.values.any { r -> xs[0] >= r.x && xs[0] < r.x + r.width && ys[0] >= r.y && ys[0] < r.y + r.height }
    val keysLong = if (unit > 0f) "%.1f".format(java.util.Locale.ROOT, length / unit) else "?"
    return "glide dropped reason=$reason points=${xs.size} lengthKeys=$keysLong startOnLetter=$onLetter preview=$preview keys=${keys.size}"
}
