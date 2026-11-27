package com.kingzcheung.xime.glide

import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.speech.WordLanguageModel
import kotlin.math.ln

/**
 * 音节序列 → 中文词前缀树（1～4 音节），预查 [WordLanguageModel] 词 ID 与词频先验，
 * 供 [ChineseLatticeBeam] 在轨迹晶格剪枝前直接做「空间几何 × 词组语言模型」联合束搜索。
 */
internal class ChineseWordTrie private constructor(
    private val children: Array<HashMap<Int, Int>?>,
    private val wordLmId: IntArray,
    private val wordRankPrior: FloatArray,
    private val syllableCount: ByteArray,
    val syllableStrings: List<String>,
) {
    fun child(node: Int, sylId: Int): Int = children[node]?.get(sylId) ?: -1
    fun hasWord(node: Int): Boolean = syllableCount[node] > 0
    fun hasChildren(node: Int): Boolean = children[node]?.isNotEmpty() == true
    fun lmId(node: Int): Int = wordLmId[node]
    fun rankPrior(node: Int): Float = wordRankPrior[node]
    fun syllables(node: Int): Int = syllableCount[node].toInt()

    companion object {
        const val MAX_WORD_SYLLABLES = 4

        fun build(
            entries: List<List<String>>,
            words: List<String>,
            doublePinyin: DoublePinyinScheme?,
            syllableList: List<String>,
            syllableToId: Map<String, Int>,
            lm: WordLanguageModel?,
        ): ChineseWordTrie {
            val childList = ArrayList<HashMap<Int, Int>?>(32768)
            val lmIds = ArrayList<Int>(32768)
            val priors = ArrayList<Float>(32768)
            val counts = ArrayList<Byte>(32768)

            fun addNode(): Int {
                childList.add(null)
                lmIds.add(-1)
                priors.add(1f)
                counts.add(0)
                return childList.size - 1
            }
            addNode() // root = 0

            val hasWords = words.size == entries.size
            val logMax = ln((entries.size + 1).toDouble()).toFloat().coerceAtLeast(1f)
            val mappedIds = IntArray(MAX_WORD_SYLLABLES)

            entries.forEachIndexed { rank, syllables ->
                if (syllables.isEmpty() || syllables.size > MAX_WORD_SYLLABLES) return@forEachIndexed
                var valid = true
                for (idx in syllables.indices) {
                    val code = if (doublePinyin == null) syllables[idx] else (doublePinyin.code(syllables[idx]) ?: run {
                        valid = false; break
                    })
                    val id = syllableToId[code] ?: run { valid = false; break }
                    mappedIds[idx] = id
                }
                if (!valid) return@forEachIndexed

                var node = 0
                for (idx in syllables.indices) {
                    val sid = mappedIds[idx]
                    val map = childList[node] ?: HashMap<Int, Int>(4).also { childList[node] = it }
                    node = map.getOrPut(sid) { addNode() }
                }
                if (counts[node].toInt() == 0) {
                    val text = if (hasWords) words[rank] else ""
                    lmIds[node] = if (lm != null && text.isNotEmpty()) lm.id(text) else -1
                    priors[node] = (ln(rank + 2.0).toFloat() / logMax).coerceIn(0f, 1f)
                    counts[node] = syllables.size.toByte()
                }
            }

            // 兜底保证每一个合法单音节都可作为 1 音节 OOV 词通过，避免生僻音节断路。
            for (sid in syllableList.indices) {
                val rootMap = childList[0] ?: HashMap<Int, Int>(512).also { childList[0] = it }
                val node = rootMap.getOrPut(sid) { addNode() }
                if (counts[node].toInt() == 0) {
                    lmIds[node] = -1
                    priors[node] = 1f
                    counts[node] = 1
                }
            }

            return ChineseWordTrie(
                children = childList.toTypedArray(),
                wordLmId = lmIds.toIntArray(),
                wordRankPrior = priors.toFloatArray(),
                syllableCount = counts.toByteArray(),
                syllableStrings = syllableList,
            )
        }
    }
}

/**
 * P1.5 帧同步联合束搜索：
 * 复用外部共享的 [SyllableGlide.LatticeContext]，在词边界（1～4 音节词）前向推进并同步计入
 * 几何路径代价与 [WordLanguageModel] Trigram 概率；使用原始数组缓冲消除热循环分配，
 * 返回分别记录 geomCost 与 lmCost 的 [ScoredCode] 供 [GlideDecoder] 精确融合。
 */
internal object ChineseLatticeBeam {
    const val LM_COST_SCALE = 0.08f
    private const val MIN_NEG_LOGP = 2.0
    private const val MAX_NEG_LOGP = 14.5
    private const val OOV_NEG_LOGP = 13.5
    private const val NO_LM_WORD_BASE = 0.28f
    private const val NO_LM_WORD_PRIOR = 0.52f
    const val RELEASE_BEAM_WIDTH = 28
    const val PREVIEW_BEAM_WIDTH = 14
    private const val MAX_PER_SLOT_LM = 3
    private const val MAX_PER_SLOT_NO_LM = 8
    private const val MAX_WORD_SPANS_PER_ORIGIN = 40
    private const val GLOBAL_PRUNE_SLACK = 5.5f

    private class WordSpan(
        val endFrame: Int,
        val lastKey: Int,
        val wordLmId: Int,
        val wordRankPrior: Float,
        val syllableCount: Int,
        val geomCost: Float,
        val code: String,
        val rankCost: Float,
    )

    private class BeamState(
        val lastKey: Int,
        val h2: Int,
        val h1: Int,
        val geomCost: Float,
        val lmCost: Float,
        val code: String,
    ) {
        val totalCost: Float get() = geomCost + lmCost
    }

    /** 兼容旧签名：内部构建一次性 LatticeContext。 */
    fun decode(
        syllableGlide: SyllableGlide,
        wordTrie: ChineseWordTrie,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray?,
        lm: WordLanguageModel?,
        leftContext: String?,
        limit: Int,
        beamWidth: Int = RELEASE_BEAM_WIDTH,
        budgetMs: Long = 18L,
    ): List<ScoredCode> {
        val ctx = syllableGlide.buildLatticeContext(xs, ys, ts) ?: return emptyList()
        return decodeFromContext(ctx, wordTrie, lm, leftContext, limit, beamWidth, budgetMs)
    }

    /** 复用已构建的 [ctx] 执行联合束搜索；[budgetMs] 仅计入束搜索自身耗时，不再被反向 DP 提前吃光。 */
    fun decodeFromContext(
        ctx: SyllableGlide.LatticeContext,
        wordTrie: ChineseWordTrie,
        lm: WordLanguageModel?,
        leftContext: String?,
        limit: Int,
        beamWidth: Int = RELEASE_BEAM_WIDTH,
        budgetMs: Long = 18L,
    ): List<ScoredCode> {
        if (limit <= 0) return emptyList()
        val deadlineNs = System.nanoTime() + budgetMs * 1_000_000L
        val n = ctx.n
        val last = ctx.last

        val leftWords = if (lm != null && !leftContext.isNullOrEmpty()) {
            ChineseSentenceGlideScorer.segmentWords(leftContext.trimEnd(), lm).takeLast(2)
        } else emptyList()
        val h2Init = leftWords.getOrNull(leftWords.size - 2)?.let(lm!!::id) ?: -1
        val h1Init = leftWords.lastOrNull()?.let(lm!!::id) ?: -1

        val beam = Array<ArrayList<BeamState>?>(n) { null }
        val completed = ArrayList<BeamState>(beamWidth * 2)
        val geomPruneLimit = ctx.bestGlobalCost + GLOBAL_PRUNE_SLACK
        val maxPerSlot = if (lm != null) MAX_PER_SLOT_LM else MAX_PER_SLOT_NO_LM

        // 按深度复用的起点转移原始数组缓冲，消除 Triple 装箱分配。
        val maxDepth = ChineseWordTrie.MAX_WORD_SYLLABLES + 1
        val bufFrames = Array(maxDepth) { IntArray(64) }
        val bufKeys = Array(maxDepth) { IntArray(64) }
        val bufCosts = Array(maxDepth) { FloatArray(64) }

        fun wordLmCost(h2: Int, h1: Int, wid: Int, rankPrior: Float): Float {
            if (lm == null) {
                return NO_LM_WORD_BASE + NO_LM_WORD_PRIOR * rankPrior
            }
            val negLogP = if (wid >= 0) {
                (-lm.trigram(h2, h1, wid)).coerceIn(MIN_NEG_LOGP, MAX_NEG_LOGP)
            } else {
                (OOV_NEG_LOGP - (1f - rankPrior) * 2.0).coerceIn(MIN_NEG_LOGP, MAX_NEG_LOGP)
            }
            return (negLogP * LM_COST_SCALE).toFloat()
        }

        fun wordPriorEstimate(wid: Int, rankPrior: Float): Float {
            if (lm == null) return NO_LM_WORD_BASE + NO_LM_WORD_PRIOR * rankPrior
            val negLogP = if (wid >= 0) {
                (-lm.unigram(wid)).coerceIn(MIN_NEG_LOGP, MAX_NEG_LOGP)
            } else {
                (OOV_NEG_LOGP - (1f - rankPrior) * 2.0).coerceIn(MIN_NEG_LOGP, MAX_NEG_LOGP)
            }
            return (negLogP * LM_COST_SCALE).toFloat()
        }

        var expandTicks = 0

        fun expandWordsFrom(originFrame: Int, originKey: Int, baseGeom: Float): List<WordSpan> {
            val bestByEndCode = HashMap<Int, HashMap<String, WordSpan>>(16)
            val codeBuf = StringBuilder(24)

            fun emitNextSyllable(
                prevFrame: Int,
                prevKey: Int,
                trieNode: Int,
                accGeom: Float,
                depth: Int,
            ) {
                if (depth > ChineseWordTrie.MAX_WORD_SYLLABLES) return
                if ((++expandTicks and 127) == 0 && System.nanoTime() >= deadlineNs) return

                var fArr = bufFrames[depth]
                var kArr = bufKeys[depth]
                var cArr = bufCosts[depth]
                var count = 0

                fun addStep(sf: Int, sk: Int, sc: Float) {
                    if (count >= fArr.size) {
                        val nextCap = fArr.size * 2
                        fArr = fArr.copyOf(nextCap); bufFrames[depth] = fArr
                        kArr = kArr.copyOf(nextCap); bufKeys[depth] = kArr
                        cArr = cArr.copyOf(nextCap); bufCosts[depth] = cArr
                    }
                    fArr[count] = sf
                    kArr[count] = sk
                    cArr[count] = sc
                    count++
                }

                if (prevKey < 0) {
                    val near0 = ctx.nearKeys[0]
                    for (idx in near0.indices) {
                        val k = near0[idx]
                        addStep(0, k, ctx.startHitCost(0, k))
                    }
                } else {
                    // 跨音节同键重复（如 xin·nian 双 n）
                    addStep(prevFrame, prevKey, ctx.repeatEdgeCost)
                    val maxJ = ctx.straightUntil[prevFrame]
                    for (j in prevFrame + 1..maxJ) {
                        val cands = ctx.nearKeys[j]
                        for (idx in cands.indices) {
                            val nk = cands[idx]
                            if (nk == prevKey) continue
                            addStep(j, nk, ctx.moveTransitionCost(prevFrame, j, prevKey, nk))
                        }
                    }
                }

                for (eIdx in 0 until count) {
                    val startF = fArr[eIdx]
                    val startK = kArr[eIdx]
                    val transCost = cArr[eIdx]
                    val spans = ctx.syllablesFrom(startF, startK)
                    for (sIdx in spans.indices) {
                        val sp = spans[sIdx]
                        val nextNode = wordTrie.child(trieNode, sp.sylId)
                        if (nextNode < 0) continue
                        val newGeom = accGeom + transCost + sp.cost
                        val remAfter = ctx.minRemainingAfterBoundary(sp.endFrame, sp.lastKey)
                        val totalGeomEst = baseGeom + newGeom + remAfter
                        if (totalGeomEst > geomPruneLimit) continue

                        val sylStr = wordTrie.syllableStrings[sp.sylId]
                        val oldLen = codeBuf.length
                        codeBuf.append(sylStr)
                        if (wordTrie.hasWord(nextNode) && (originKey < 0 || sp.endFrame > originFrame)) {
                            val spanCode = codeBuf.toString()
                            val wid = wordTrie.lmId(nextNode)
                            val rPrior = wordTrie.rankPrior(nextNode)
                            // 按「累计几何 + 剩余下界 + 词先验」排名，避免短跨度挤掉完整多音节词。
                            val rankCost = newGeom + remAfter + wordPriorEstimate(wid, rPrior)
                            val bucket = bestByEndCode.getOrPut(sp.endFrame) { HashMap(8) }
                            val old = bucket[spanCode]
                            if (old == null || newGeom < old.geomCost) {
                                bucket[spanCode] = WordSpan(
                                    endFrame = sp.endFrame,
                                    lastKey = sp.lastKey,
                                    wordLmId = wid,
                                    wordRankPrior = rPrior,
                                    syllableCount = depth,
                                    geomCost = newGeom,
                                    code = spanCode,
                                    rankCost = rankCost,
                                )
                            }
                        }
                        if (sp.endFrame < last && depth < ChineseWordTrie.MAX_WORD_SYLLABLES && wordTrie.hasChildren(nextNode)) {
                            emitNextSyllable(sp.endFrame, sp.lastKey, nextNode, newGeom, depth + 1)
                        }
                        codeBuf.setLength(oldLen)
                    }
                }
            }

            emitNextSyllable(originFrame, originKey, 0, 0f, 1)
            if (bestByEndCode.isEmpty()) return emptyList()
            val all = ArrayList<WordSpan>(64)
            for (map in bestByEndCode.values) all.addAll(map.values)
            if (all.size <= MAX_WORD_SPANS_PER_ORIGIN) return all
            all.sortBy { it.rankCost }
            return all.subList(0, MAX_WORD_SPANS_PER_ORIGIN)
        }

        fun pushState(targetFrame: Int, state: BeamState) {
            if (targetFrame == last) {
                completed += state
            } else {
                val bucket = beam[targetFrame] ?: ArrayList<BeamState>(beamWidth * 2).also { beam[targetFrame] = it }
                bucket += state
            }
        }

        fun pruneFrame(list: ArrayList<BeamState>): List<BeamState> {
            if (list.size <= 1) return list
            list.sortBy { it.totalCost }
            val out = ArrayList<BeamState>(beamWidth)
            // 先查槽位配额再去重（含 h1 区分词历史），避免不同词历史误合并或被拒项占用去重键。
            val seen = HashSet<String>(beamWidth * 2)
            val slotCounts = HashMap<Long, Int>(beamWidth * 2)
            for (st in list) {
                val slotKey = (st.lastKey.toLong() shl 32) or (st.h1.toLong() and 0xFFFFFFFFL)
                val cnt = slotCounts[slotKey] ?: 0
                if (cnt >= maxPerSlot) continue
                val dedupKey = "${st.lastKey}:${st.h1}:${st.code}"
                if (!seen.add(dedupKey)) continue
                slotCounts[slotKey] = cnt + 1
                out += st
                if (out.size >= beamWidth) break
            }
            return out
        }

        val initialSpans = expandWordsFrom(0, -1, 0f)
        for (sp in initialSpans) {
            val lCost = wordLmCost(h2Init, h1Init, sp.wordLmId, sp.wordRankPrior)
            val nextH2 = if (sp.wordLmId >= 0) h1Init else -1
            val nextH1 = sp.wordLmId
            pushState(
                sp.endFrame,
                BeamState(sp.lastKey, nextH2, nextH1, sp.geomCost, lCost, sp.code),
            )
        }

        for (frame in 0 until last) {
            val rawStates = beam[frame] ?: continue
            beam[frame] = null
            if (System.nanoTime() >= deadlineNs) break
            val active = pruneFrame(rawStates)
            if (active.isEmpty()) continue

            val byKey = HashMap<Int, ArrayList<BeamState>>(6)
            for (st in active) {
                byKey.getOrPut(st.lastKey) { ArrayList(8) }.add(st)
            }
            for ((prevKey, group) in byKey) {
                if (System.nanoTime() >= deadlineNs) break
                val minBaseGeom = group.minOf { it.geomCost }
                val spans = expandWordsFrom(frame, prevKey, minBaseGeom)
                if (spans.isEmpty()) continue
                for (st in group) {
                    for (sp in spans) {
                        val nextCodeLen = st.code.length + sp.code.length
                        if (nextCodeLen > GlideDecoder.MAX_SENTENCE_KEYS) continue
                        val nextGeom = st.geomCost + sp.geomCost
                        val nextLm = st.lmCost + wordLmCost(st.h2, st.h1, sp.wordLmId, sp.wordRankPrior)
                        val nextH2 = if (sp.wordLmId >= 0) st.h1 else -1
                        val nextH1 = sp.wordLmId
                        pushState(
                            sp.endFrame,
                            BeamState(sp.lastKey, nextH2, nextH1, nextGeom, nextLm, st.code + sp.code),
                        )
                    }
                }
            }
        }

        if (completed.isEmpty()) return emptyList()
        completed.sortBy { it.totalCost }
        val results = ArrayList<ScoredCode>(limit)
        val seenCodes = HashSet<String>(limit * 2)
        for (st in completed) {
            if (seenCodes.add(st.code)) {
                results += ScoredCode(
                    code = st.code,
                    cost = st.totalCost,
                    geomCost = st.geomCost,
                    lmCost = st.lmCost,
                    fromJointBeam = true,
                )
                if (results.size >= limit) break
            }
        }
        return results
    }
}
