package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NgramFusionEngine(private val context: Context, private val userNgramCache: UserNgramCache = UserNgramCache(context)) {
    companion object {
        private const val TAG = "NgramFusionEngine"
        private const val DEFAULT_LAMBDA = 0.7f
        /** Mixture weights over probabilities: bundled n-gram table, personal data. */
        const val BASE_WEIGHT = 0.7f
        const val USER_WEIGHT = 0.15f
        /** Typed observations reach full weight after this many events on the same context. */
        const val USER_CONFIDENCE_EVENTS = 8f
        /** 复用 rime/t9_ngram.lm 的三元残差增益与 3–4 字整词二元补齐权重。 */
        const val TRIGRAM_RESIDUAL_WEIGHT = 0.042f
        const val WHOLE_WORD_BIGRAM_WEIGHT = 0.032f
    }
    
    private var lambda = DEFAULT_LAMBDA
    private var isInitialized = false
    private var baseModel: BaseAssociationModel? = null
    /** 整词后面常接什么（从内置词库生成）；安装包里没有这个文件时为 null，联想照旧只用联想表。 */
    private var wordTable: WordContinuationTable? = null
    
    suspend fun initialize(): Boolean {
        if (isInitialized) return true
        
        val result = userNgramCache.initialize()
        baseModel = withContext(Dispatchers.IO) {
            // Without the marks the table still works; after a whole word it then behaves as before.
            val wordCompletions = try {
                openTextAsset(BaseAssociationModel.WORD_COMPLETIONS_ASSET).use(BaseAssociationModel::readWordCompletions)
            } catch (error: Exception) {
                Log.e(TAG, "Association word completion marks failed to load", error)
                emptyMap()
            }
            try {
                context.assets.open(BaseAssociationModel.ASSET).use { input ->
                    java.util.zip.GZIPInputStream(input).reader(Charsets.UTF_8).use {
                        BaseAssociationModel.readWithWordCompletions(it, wordCompletions)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Bundled association model failed to load", error)
                null
            }
        }
        wordTable = withContext(Dispatchers.IO) {
            try {
                openTextAsset(WordContinuationTable.ASSET).use(WordContinuationTable::read)
            } catch (error: Exception) {
                Log.e(TAG, "Word continuation table failed to load", error)
                null
            }
        }
        isInitialized = result || baseModel != null
        return isInitialized
    }
    
    /**
     * 打包时 .gz 资源会被解压、去掉 .gz 后缀放进安装包（AssetManager 只认去掉后缀的名字），
     * 所以先按去掉后缀的名字读纯文本，找不到再按原名读 gzip。
     */
    private fun openTextAsset(name: String): java.io.Reader {
        val plain = name.removeSuffix(".gz")
        if (plain != name) {
            try {
                return context.assets.open(plain).reader(Charsets.UTF_8)
            } catch (_: java.io.FileNotFoundException) {
            }
        }
        return java.util.zip.GZIPInputStream(context.assets.open(name)).reader(Charsets.UTF_8)
    }

    fun recordUserInput(text: String) {
        userNgramCache.recordInput(text)
    }
    
    /** [afterWholeWord]: see [BaseAssociationModel.predict]; only the corpus table depends on it. */
    fun fuseCandidates(
        context: String,
        afterWholeWord: Boolean = false,
    ): List<AssociationCandidate> {
        if (!isInitialized) return emptyList()
        
        // One scale: every source contributes a probability of the continuation given the
        // context, mixed with fixed weights. The corpus table leads; personal observations only
        // add a bounded share, so a rare long phrase cannot outrank 个/是/些 after 这 on its own.
        val allCandidates = mutableMapOf<String, Float>()
        baseModel?.predict(context, BaseAssociationModel.MAX_PER_CONTEXT, afterWholeWord)?.forEach {
            allCandidates[it.text] = BASE_WEIGHT * it.score
        }
        val baseCtxLen = baseModel?.contextLength(context) ?: 0
        // The corpus table knows the last one or two characters only. When the context ends with a longer
        // dictionary word (厄尔尼诺), what follows that whole word (现象) joins on the same scale.
        mergeWordContinuations(allCandidates, baseCtxLen, wordTable?.lookup(context), BASE_WEIGHT)
        // Imported personal corpora carry their own counts (0.4·share); typed observations are
        // shares over few events, so they earn weight gradually (n/USER_CONFIDENCE_EVENTS).
        userNgramCache.profileCandidates(context).forEach { candidate ->
            allCandidates[candidate.text] = (allCandidates[candidate.text] ?: 0f) + USER_WEIGHT * candidate.score / 0.4f
        }
        val userCandidates = userNgramCache.getUserCandidates(context, 10)
        val confidence = minOf(1f, userNgramCache.observationCount(context) / USER_CONFIDENCE_EVENTS)
        val tailCjkLen = context.takeLastWhile { it in '\u4e00'..'\u9fff' }.length
        userCandidates.forEach { (word, userScore) ->
            // 整词上屏后，UserNgramCache 的单字预测若不在语料候选中则温和降权，避免半截字抢占整词位。
            val singleFactor = if (afterWholeWord && tailCjkLen >= 2 && word.length == 1 && word !in allCandidates) 0.55f else 1f
            allCandidates[word] = (allCandidates[word] ?: 0f) + USER_WEIGHT * confidence * userScore * singleFactor
        }

        val filesDir = (this.context.applicationContext ?: this.context).filesDir
        // 复用现成内存映射词组 Trigram 模型（rime/t9_ngram.lm）：
        // 1) 叠加「trigram(h2, h1, w) - bigram(h1, w)」纯三元残差，把 1–2 字眼界扩展到整句前两词；
        // 2) 对 3–4 字整词（baseModel 只看到末 1–2 字）补齐整词 bigram 提升。
        applyWordNgramRerank(filesDir, context, baseCtxLen, allCandidates)

        if (com.kingzcheung.xime.settings.SettingsPreferences.isPersonalAssociationLearningEnabled(this.context)) {
            runCatching {
                PersonalAssociationBooster.applyToCandidates(filesDir, context, allCandidates, afterWholeWord)
            }
        }

        return diversifyTopCandidates(
            allCandidates.map { (word, score) -> AssociationCandidate(word, score) }
                .sortedByDescending { it.score }
        )
    }

    private fun applyWordNgramRerank(
        filesDir: java.io.File,
        context: String,
        baseCtxLen: Int,
        candidates: MutableMap<String, Float>,
    ) {
        if (candidates.size < 2) return
        val lm = com.kingzcheung.xime.glide.ChineseGlideContext.model(filesDir) ?: return
        val cjkTail = context.takeLastWhile { it in '\u4e00'..'\u9fff' }.takeLast(12)
        if (cjkTail.length < 2) return
        val words = com.kingzcheung.xime.glide.ChineseSentenceGlideScorer.segmentWords(cjkTail, lm)
        if (words.isEmpty()) return
        val h1Word = words.last()
        val h1 = lm.id(h1Word)
        if (h1 < 0) return
        val h2Word = words.getOrNull(words.size - 2)
        val h2 = h2Word?.let(lm::id) ?: -1
        val needsWholeWordBoost = h1Word.length >= 3 && baseCtxLen < h1Word.length
        if (h2 < 0 && !needsWholeWordBoost) return

        for (entry in candidates.entries) {
            val word = entry.key
            val wid = lm.id(word)
            if (wid < 0) continue
            var delta = 0f
            if (h2 >= 0) {
                val residual = (lm.trigram(h2, h1, wid) - lm.bigram(h1, wid)).coerceIn(-1.5, 4.0)
                delta += (residual * TRIGRAM_RESIDUAL_WEIGHT).toFloat()
            }
            if (needsWholeWordBoost) {
                val wholeLift = (lm.bigram(h1, wid) - lm.unigram(wid)).coerceIn(-1.0, 5.0)
                delta += (wholeLift * WHOLE_WORD_BIGRAM_WEIGHT).toFloat()
            }
            if (delta != 0f) {
                entry.setValue((entry.value + delta).coerceAtLeast(0.0001f))
            }
        }
    }

    /**
     * 温和整理前排多样性：
     * 1) 若已有分差接近的 2–4 字词（如「晚上」），把同首字单字碎片（如「晚」）轻微后移；
     * 2) 前排已出现 2 个同首字候选（如「了」「了吗」）时，把第 3 个同首字变形轻微后移，腾出黄金首屏位。
     */
    private fun diversifyTopCandidates(sorted: List<AssociationCandidate>): List<AssociationCandidate> {
        if (sorted.size < 3) return sorted
        val bestMultiByHead = HashMap<Char, Float>()
        for (c in sorted.take(12)) {
            if (c.text.length in 2..4) {
                val head = c.text[0]
                val prev = bestMultiByHead[head] ?: 0f
                if (c.score > prev) bestMultiByHead[head] = c.score
            }
        }
        val headCounts = HashMap<Char, Int>()
        val adjusted = sorted.mapIndexed { idx, c ->
            var score = c.score
            val head = c.text.firstOrNull()
            if (head != null && idx < 10) {
                if (c.text.length == 1) {
                    val multiScore = bestMultiByHead[head] ?: 0f
                    if (multiScore >= score * 0.78f) score *= 0.82f
                }
                val seen = headCounts[head] ?: 0
                if (seen >= 2) score *= 0.86f
                headCounts[head] = seen + 1
            }
            if (score == c.score) c else AssociationCandidate(c.text, score)
        }
        return adjusted.sortedByDescending { it.score }
    }
    
    suspend fun saveCache() {
        userNgramCache.save()
    }
    
    fun clearCache() {
        userNgramCache.clear()
    }
    
    fun getCacheSize(): Int = userNgramCache.getCacheSize()
    fun learningData(): PersonalLearningData = userNgramCache.snapshot()
    suspend fun importLearningData(data: PersonalLearningData, merge: Boolean = false) = userNgramCache.importData(data, merge)
    
    fun isInitialized(): Boolean = isInitialized
}
