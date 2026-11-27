package com.kingzcheung.xime.glide

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 可微调的音节连续滑行解码参数（默认值与原始基线一致，可由本机历史回放调优更新）。 */
data class SyllableGlideParams(
    val hitWeight: Float = SyllableGlide.HIT_WEIGHT,
    val segmentWeight: Float = SyllableGlide.SEGMENT_WEIGHT,
    val keyCost: Float = SyllableGlide.KEY_COST,
    val repeatCost: Float = SyllableGlide.REPEAT_COST,
    val detourWeight: Float = SyllableGlide.DETOUR_WEIGHT,
    val syllableCost: Float = SyllableGlide.SYLLABLE_COST,
    val syllablePrior: Float = SyllableGlide.SYLLABLE_PRIOR,
    val pauseBonus: Float = SyllableGlide.PAUSE_BONUS,
    val hitRadius: Float = SyllableGlide.HIT_RADIUS,
    val straightTolerance: Float = SyllableGlide.STRAIGHT_TOLERANCE,
)

data class ScoredCode(val code: String, val cost: Float)

/**
 * 中文滑行按音节逐键识别：
 * 在音节前缀树 × 轨迹位置 DAG 上先做反向最短剩余代价表 [rem]，再做前向精确 K-Best A* 搜索，
 * 保留中间分叉路径，避免长句（5～12 字）在单一维特比槽位被截断；同时利用速度停顿微特征辅助音节边界。
 */
class SyllableGlide(
    private val keyboard: GlideKeyboard,
    syllables: List<String>,
    private val params: SyllableGlideParams = SyllableGlideParams(),
) {
    private val char = ArrayList<Char>()
    private val children = ArrayList<HashMap<Char, Int>>()
    private val syllableCost = ArrayList<Float>()
    private val syllableIdOfNode = ArrayList<Int>()
    private val nodeKey: IntArray
    internal val syllableList: List<String>
    internal val syllableToId: Map<String, Int>

    private val keys: CharArray = keyboard.centers.keys.toCharArray()
    private val keyIndexMap: Map<Char, Int> = keys.withIndex().associate { (idx, ch) -> ch to idx }
    private val keyX = FloatArray(keys.size) { keyboard.centers.getValue(keys[it]).first / keyboard.keyUnit }
    private val keyY = FloatArray(keys.size) { keyboard.centers.getValue(keys[it]).second / keyboard.keyUnit }

    init {
        char += ' '; children += HashMap(); syllableCost += Float.NaN; syllableIdOfNode += -1
        val distinct = syllables.filter { syllable -> syllable.isNotEmpty() && syllable.all { keyboard.centers.containsKey(it) } }.distinct()
        syllableList = distinct
        val idMap = HashMap<String, Int>(distinct.size * 2)
        val logMax = ln(distinct.size + 1.0).toFloat()
        distinct.forEachIndexed { rank, syllable ->
            idMap[syllable] = rank
            var node = 0
            for (c in syllable) {
                node = children[node].getOrPut(c) {
                    char += c; children += HashMap(); syllableCost += Float.NaN; syllableIdOfNode += -1
                    char.size - 1
                }
            }
            if (syllableCost[node].isNaN()) {
                syllableCost[node] = params.syllableCost + params.syllablePrior * ln(rank + 2.0).toFloat() / logMax
                syllableIdOfNode[node] = rank
            }
        }
        syllableToId = idMap
        nodeKey = IntArray(char.size) { idx -> if (idx == 0) -1 else (keyIndexMap[char[idx]] ?: -1) }
    }

    /** 兼容旧接口：最多返回 [limit] 串编码。 */
    fun decode(xs: FloatArray, ys: FloatArray, limit: Int = 3): List<String> =
        decodeScored(xs, ys, ts = null, limit = limit).map { it.code }

    /**
     * 返回最多 [limit] 串带代价的多路径音节编码；若提供时间戳 [ts]，在明显减速停顿处为完整音节边界加分。
     */
    fun decodeScored(xs: FloatArray, ys: FloatArray, ts: LongArray? = null, limit: Int = 12): List<ScoredCode> {
        if (xs.size < 2 || xs.size != ys.size || limit <= 0) return emptyList()
        val unit = keyboard.keyUnit
        val (px, py, pause) = resampleWithPause(
            FloatArray(xs.size) { xs[it] / unit },
            FloatArray(ys.size) { ys[it] / unit },
            ts,
        )
        val n = px.size
        if (n < 2) return emptyList()
        val step = pathLength(px, py) / (n - 1)
        val nearKeys = Array(n) { i ->
            keys.indices.filter { k -> dist(px[i], py[i], keyX[k], keyY[k]) <= params.hitRadius }.toIntArray()
        }
        val straightUntil = IntArray(n) { i ->
            var j = i + 1
            while (j < n && maxDeviation(px, py, i, j) <= params.straightTolerance &&
                (j - i) * step - dist(px[i], py[i], px[j], py[j]) <= params.straightTolerance) j++
            j - 1
        }

        val nodes = char.size
        val last = n - 1
        val remMove = Array(n) { FloatArray(nodes) { Float.POSITIVE_INFINITY } }
        val rem = Array(n) { FloatArray(nodes) { Float.POSITIVE_INFINITY } }

        for (node in 1 until nodes) {
            val endWeight = syllableCost[node]
            if (!endWeight.isNaN()) {
                remMove[last][node] = endWeight
                rem[last][node] = endWeight
            }
        }

        for (i in last downTo 0) {
            for (node in 1 until nodes) {
                val k = nodeKey[node]
                if (k < 0) continue
                val endWeight = syllableCost[node]
                val canEnd = !endWeight.isNaN()
                val boundaryWeight = if (canEnd) endWeight - params.pauseBonus * pause[i] else 0f
                var best = remMove[i][node]
                val maxJ = straightUntil[i]
                for (j in i + 1..maxJ) {
                    val candidatesAtJ = nearKeys[j]
                    for (idx in candidatesAtJ.indices) {
                        val next = candidatesAtJ[idx]
                        if (next == k) continue
                        val stepEdge = segmentCost(px, py, i, j, k, next, step) + params.keyCost +
                            params.hitWeight * dist(px[j], py[j], keyX[next], keyY[next])
                        val c = keys[next]
                        children[node][c]?.let { target ->
                            val r = rem[j][target]
                            if (r.isFinite()) {
                                val cand = stepEdge + r
                                if (cand < best) best = cand
                            }
                        }
                        if (canEnd) {
                            children[0][c]?.let { target0 ->
                                val r = rem[j][target0]
                                if (r.isFinite()) {
                                    val cand = stepEdge + boundaryWeight + r
                                    if (cand < best) best = cand
                                }
                            }
                        }
                    }
                }
                remMove[i][node] = best
                if (best < rem[i][node]) rem[i][node] = best
            }

            // 同位置至多原地连按一次（如双拼 ii = chi），基于 remMove[i] 松弛，保证 DAG 无环。
            val repeatEdge = params.keyCost + params.repeatCost
            for (node in 1 until nodes) {
                val c = char[node]
                var best = rem[i][node]
                children[node][c]?.let { target ->
                    val r = remMove[i][target]
                    if (r.isFinite()) {
                        val cand = repeatEdge + r
                        if (cand < best) best = cand
                    }
                }
                val endWeight = syllableCost[node]
                if (!endWeight.isNaN()) {
                    val boundaryWeight = endWeight - params.pauseBonus * pause[i]
                    children[0][c]?.let { target0 ->
                        val r = remMove[i][target0]
                        if (r.isFinite()) {
                            val cand = repeatEdge + boundaryWeight + r
                            if (cand < best) best = cand
                        }
                    }
                }
                rem[i][node] = best
            }
        }

        val pq = PriorityQueue<AStarState>(64) { a, b -> a.f.compareTo(b.f) }
        for (k in nearKeys[0]) {
            val target = children[0][keys[k]] ?: continue
            val h = rem[0][target]
            if (!h.isFinite()) continue
            val g = params.hitWeight * dist(px[0], py[0], keyX[k], keyY[k])
            pq.add(AStarState(i = 0, node = target, repeatUsed = false, g = g, f = g + h, code = keys[k].toString()))
        }

        val results = ArrayList<ScoredCode>(limit)
        val seenCodes = HashSet<String>(limit * 2)
        // 长句轨迹更长（n 可达 150~260），按点数动态放宽队列与扩展预算，避免长句中途截断。
        val maxQueueSize = maxOf(limit * 140, n * 24, 960).coerceAtMost(6000)
        var expansions = 0
        val maxExpansions = maxOf(limit * 180, n * 32, 1200).coerceAtMost(8000)

        while (pq.isNotEmpty() && results.size < limit && expansions < maxExpansions) {
            val cur = pq.poll() ?: break
            expansions++
            val i = cur.i
            val node = cur.node
            val endWeight = syllableCost[node]
            val canEnd = !endWeight.isNaN()

            if (i == last) {
                if (canEnd && seenCodes.add(cur.code)) {
                    results += ScoredCode(cur.code, cur.g + endWeight)
                    if (results.size >= limit) break
                }
            }

            if (!cur.repeatUsed) {
                val c = char[node]
                val repeatEdge = params.keyCost + params.repeatCost
                children[node][c]?.let { target ->
                    val h = remMove[i][target]
                    if (h.isFinite()) {
                        val g = cur.g + repeatEdge
                        pq.add(AStarState(i, target, true, g, g + h, cur.code + c))
                    }
                }
                if (canEnd) {
                    val boundaryWeight = endWeight - params.pauseBonus * pause[i]
                    children[0][c]?.let { target0 ->
                        val h = remMove[i][target0]
                        if (h.isFinite()) {
                            val g = cur.g + repeatEdge + boundaryWeight
                            pq.add(AStarState(i, target0, true, g, g + h, cur.code + c))
                        }
                    }
                }
            }

            val k = nodeKey[node]
            if (k >= 0) {
                val boundaryWeight = if (canEnd) endWeight - params.pauseBonus * pause[i] else 0f
                val maxJ = straightUntil[i]
                for (j in i + 1..maxJ) {
                    val candidatesAtJ = nearKeys[j]
                    for (idx in candidatesAtJ.indices) {
                        val next = candidatesAtJ[idx]
                        if (next == k) continue
                        val c = keys[next]
                        val stepEdge = segmentCost(px, py, i, j, k, next, step) + params.keyCost +
                            params.hitWeight * dist(px[j], py[j], keyX[next], keyY[next])
                        children[node][c]?.let { target ->
                            val h = rem[j][target]
                            if (h.isFinite()) {
                                val g = cur.g + stepEdge
                                pq.add(AStarState(j, target, false, g, g + h, cur.code + c))
                            }
                        }
                        if (canEnd) {
                            children[0][c]?.let { target0 ->
                                val h = rem[j][target0]
                                if (h.isFinite()) {
                                    val g = cur.g + stepEdge + boundaryWeight
                                    pq.add(AStarState(j, target0, false, g, g + h, cur.code + c))
                                }
                            }
                        }
                    }
                }
            }
            if (pq.size > maxQueueSize) {
                val kept = ArrayList<AStarState>(maxQueueSize / 2)
                repeat(maxQueueSize / 2) { pq.poll()?.let(kept::add) }
                pq.clear()
                pq.addAll(kept)
            }
        }
        return results
    }

    /** 单音节在轨迹上的一条晶格边：从给定起点帧出发，结束于 [endFrame]，对应音节 [sylId]。 */
    internal data class SyllableSpan(
        val endFrame: Int,
        val sylId: Int,
        val lastKey: Int,
        val cost: Float,
    )

    /** 供 P1 联合束搜索复用的轨迹网格与反向精确剩余代价表。 */
    internal inner class LatticeContext(
        val px: FloatArray,
        val py: FloatArray,
        val pause: FloatArray,
        val step: Float,
        val nearKeys: Array<IntArray>,
        val straightUntil: IntArray,
        val remMove: Array<FloatArray>,
        val rem: Array<FloatArray>,
        val bestGlobalCost: Float,
    ) {
        val n: Int get() = px.size
        val last: Int get() = px.size - 1
        val keyCount: Int get() = keys.size
        val repeatEdgeCost: Float get() = params.keyCost + params.repeatCost

        private val spanCache = arrayOfNulls<List<SyllableSpan>>(px.size * keys.size)
        private val minRemBoundaryCache = FloatArray(px.size * keys.size) { Float.NaN }

        fun startHitCost(frame: Int, keyIdx: Int): Float =
            params.hitWeight * dist(px[frame], py[frame], keyX[keyIdx], keyY[keyIdx])

        fun moveTransitionCost(fromFrame: Int, toFrame: Int, fromKey: Int, toKey: Int): Float =
            segmentCost(px, py, fromFrame, toFrame, fromKey, toKey, step) + params.keyCost +
                params.hitWeight * dist(px[toFrame], py[toFrame], keyX[toKey], keyY[toKey])

        fun minRemainingAfterBoundary(frame: Int, prevKey: Int): Float {
            if (frame >= last) return 0f
            val slot = frame * keys.size + prevKey
            val cached = minRemBoundaryCache[slot]
            if (!cached.isNaN()) return cached
            var best = Float.POSITIVE_INFINITY
            val maxJ = straightUntil[frame]
            for (j in frame + 1..maxJ) {
                val cands = nearKeys[j]
                for (idx in cands.indices) {
                    val next = cands[idx]
                    if (next == prevKey) continue
                    val target = children[0][keys[next]] ?: continue
                    val r = rem[j][target]
                    if (r.isFinite()) {
                        val est = params.keyCost + r
                        if (est < best) best = est
                    }
                }
            }
            val repTarget = children[0][keys[prevKey]]
            if (repTarget != null) {
                val r = remMove[frame][repTarget]
                if (r.isFinite()) {
                    val est = repeatEdgeCost + r
                    if (est < best) best = est
                }
            }
            minRemBoundaryCache[slot] = best
            return best
        }

        /** 按需提取从 ([startFrame], [firstKey]) 出发的全部可行单音节边，并按起点缓存。 */
        fun syllablesFrom(startFrame: Int, firstKey: Int, slack: Float = 3.2f): List<SyllableSpan> {
            val slot = startFrame * keys.size + firstKey
            spanCache[slot]?.let { return it }
            val startNode = children[0][keys[firstKey]] ?: run {
                spanCache[slot] = emptyList()
                return emptyList()
            }
            val baseRem = rem[startFrame][startNode]
            if (!baseRem.isFinite()) {
                spanCache[slot] = emptyList()
                return emptyList()
            }
            val cutoff = baseRem + slack
            val bestByEndSyl = HashMap<Long, SyllableSpan>(24)

            fun dfs(frame: Int, node: Int, accCost: Float, repeatUsed: Boolean) {
                val endWeight = syllableCost[node]
                if (!endWeight.isNaN()) {
                    val sylId = syllableIdOfNode[node]
                    if (sylId >= 0) {
                        val boundaryWeight = if (frame < last) endWeight - params.pauseBonus * pause[frame] else endWeight
                        val total = accCost + boundaryWeight
                        val key = (frame.toLong() shl 16) or (sylId.toLong() and 0xFFFFL)
                        val old = bestByEndSyl[key]
                        if (old == null || total < old.cost) {
                            bestByEndSyl[key] = SyllableSpan(frame, sylId, nodeKey[node], total)
                        }
                    }
                }
                val chMap = children[node]
                if (chMap.isEmpty()) return

                // 同位置重复字母（音节内，如双拼）
                if (!repeatUsed) {
                    val sameChar = char[node]
                    val repTarget = chMap[sameChar]
                    if (repTarget != null) {
                        val r = remMove[frame][repTarget]
                        val nextCost = accCost + repeatEdgeCost
                        if (r.isFinite() && nextCost + r <= cutoff) {
                            dfs(frame, repTarget, nextCost, true)
                        }
                    }
                }

                val k = nodeKey[node]
                if (k < 0 || frame >= last) return
                val maxJ = straightUntil[frame]
                for (j in frame + 1..maxJ) {
                    val cands = nearKeys[j]
                    for (idx in cands.indices) {
                        val next = cands[idx]
                        if (next == k) continue
                        val target = chMap[keys[next]] ?: continue
                        val r = rem[j][target]
                        if (!r.isFinite()) continue
                        val nextCost = accCost + segmentCost(px, py, frame, j, k, next, step) + params.keyCost +
                            params.hitWeight * dist(px[j], py[j], keyX[next], keyY[next])
                        if (nextCost + r <= cutoff) {
                            dfs(j, target, nextCost, false)
                        }
                    }
                }
            }

            dfs(startFrame, startNode, 0f, false)
            val result = if (bestByEndSyl.isEmpty()) {
                emptyList()
            } else {
                bestByEndSyl.values.sortedBy { it.cost }.take(28)
            }
            spanCache[slot] = result
            return result
        }
    }

    /** 构建轨迹网格与反向精确剩余代价表，供 P1 音节晶格联合束搜索使用。 */
    internal fun buildLatticeContext(xs: FloatArray, ys: FloatArray, ts: LongArray? = null): LatticeContext? {
        if (xs.size < 2 || xs.size != ys.size) return null
        val unit = keyboard.keyUnit
        val (px, py, pause) = resampleWithPause(
            FloatArray(xs.size) { xs[it] / unit },
            FloatArray(ys.size) { ys[it] / unit },
            ts,
        )
        val n = px.size
        if (n < 2) return null
        val step = pathLength(px, py) / (n - 1)
        val nearKeys = Array(n) { i ->
            keys.indices.filter { k -> dist(px[i], py[i], keyX[k], keyY[k]) <= params.hitRadius }.toIntArray()
        }
        val straightUntil = IntArray(n) { i ->
            var j = i + 1
            while (j < n && maxDeviation(px, py, i, j) <= params.straightTolerance &&
                (j - i) * step - dist(px[i], py[i], px[j], py[j]) <= params.straightTolerance) j++
            j - 1
        }

        val nodes = char.size
        val last = n - 1
        val remMove = Array(n) { FloatArray(nodes) { Float.POSITIVE_INFINITY } }
        val rem = Array(n) { FloatArray(nodes) { Float.POSITIVE_INFINITY } }

        for (node in 1 until nodes) {
            val endWeight = syllableCost[node]
            if (!endWeight.isNaN()) {
                remMove[last][node] = endWeight
                rem[last][node] = endWeight
            }
        }

        for (i in last downTo 0) {
            for (node in 1 until nodes) {
                val k = nodeKey[node]
                if (k < 0) continue
                val endWeight = syllableCost[node]
                val canEnd = !endWeight.isNaN()
                val boundaryWeight = if (canEnd) endWeight - params.pauseBonus * pause[i] else 0f
                var best = remMove[i][node]
                val maxJ = straightUntil[i]
                for (j in i + 1..maxJ) {
                    val candidatesAtJ = nearKeys[j]
                    for (idx in candidatesAtJ.indices) {
                        val next = candidatesAtJ[idx]
                        if (next == k) continue
                        val stepEdge = segmentCost(px, py, i, j, k, next, step) + params.keyCost +
                            params.hitWeight * dist(px[j], py[j], keyX[next], keyY[next])
                        val c = keys[next]
                        children[node][c]?.let { target ->
                            val r = rem[j][target]
                            if (r.isFinite()) {
                                val cand = stepEdge + r
                                if (cand < best) best = cand
                            }
                        }
                        if (canEnd) {
                            children[0][c]?.let { target0 ->
                                val r = rem[j][target0]
                                if (r.isFinite()) {
                                    val cand = stepEdge + boundaryWeight + r
                                    if (cand < best) best = cand
                                }
                            }
                        }
                    }
                }
                remMove[i][node] = best
                if (best < rem[i][node]) rem[i][node] = best
            }

            val repeatEdge = params.keyCost + params.repeatCost
            for (node in 1 until nodes) {
                val c = char[node]
                var best = rem[i][node]
                children[node][c]?.let { target ->
                    val r = remMove[i][target]
                    if (r.isFinite()) {
                        val cand = repeatEdge + r
                        if (cand < best) best = cand
                    }
                }
                val endWeight = syllableCost[node]
                if (!endWeight.isNaN()) {
                    val boundaryWeight = endWeight - params.pauseBonus * pause[i]
                    children[0][c]?.let { target0 ->
                        val r = remMove[i][target0]
                        if (r.isFinite()) {
                            val cand = repeatEdge + boundaryWeight + r
                            if (cand < best) best = cand
                        }
                    }
                }
                rem[i][node] = best
            }
        }

        var bestGlobal = Float.POSITIVE_INFINITY
        for (k in nearKeys[0]) {
            val target = children[0][keys[k]] ?: continue
            val h = rem[0][target]
            if (h.isFinite()) {
                val total = params.hitWeight * dist(px[0], py[0], keyX[k], keyY[k]) + h
                if (total < bestGlobal) bestGlobal = total
            }
        }
        if (!bestGlobal.isFinite()) return null
        return LatticeContext(px, py, pause, step, nearKeys, straightUntil, remMove, rem, bestGlobal)
    }

    private class AStarState(
        val i: Int,
        val node: Int,
        val repeatUsed: Boolean,
        val g: Float,
        val f: Float,
        val code: String,
    )

    private fun segmentCost(px: FloatArray, py: FloatArray, i: Int, j: Int, a: Int, b: Int, step: Float): Float {
        var sum = 0f
        for (m in i + 1 until j) sum += pointToSegment(px[m], py[m], keyX[a], keyY[a], keyX[b], keyY[b])
        val detour = ((j - i) * step - dist(px[i], py[i], px[j], py[j])).coerceAtLeast(0f)
        return params.segmentWeight * sum * step + params.detourWeight * detour
    }

    companion object {
        const val STEP = 0.25f
        const val MAX_POINTS = 260
        const val HIT_RADIUS = 1.0f
        const val HIT_WEIGHT = 1.5f
        const val SEGMENT_WEIGHT = 1.5f
        const val KEY_COST = 0.45f
        const val REPEAT_COST = 0.6f
        const val DETOUR_WEIGHT = 3.0f
        const val SYLLABLE_COST = 0.2f
        const val SYLLABLE_PRIOR = 0.6f
        const val PAUSE_BONUS = 0.22f
        const val STRAIGHT_TOLERANCE = 0.5f

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        private fun pointToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax; val vy = by - ay
            val length = vx * vx + vy * vy
            val t = if (length <= 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / length).coerceIn(0f, 1f)
            return dist(x, y, ax + t * vx, ay + t * vy)
        }

        private fun pathLength(xs: FloatArray, ys: FloatArray): Float {
            var total = 0f
            for (i in 1 until xs.size) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return total
        }

        private fun maxDeviation(px: FloatArray, py: FloatArray, i: Int, j: Int): Float {
            var worst = 0f
            for (m in i + 1 until j) worst = max(worst, pointToSegment(px[m], py[m], px[i], py[i], px[j], py[j]))
            return worst
        }

        internal fun resampleByStep(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
            val total = pathLength(xs, ys)
            val step = max(STEP, total / (MAX_POINTS - 1))
            val count = max(2, (total / step).toInt() + 1)
            return GlideDecoder.resampleTo(xs, ys, count)
        }

        internal fun resampleWithPause(xs: FloatArray, ys: FloatArray, ts: LongArray?): Triple<FloatArray, FloatArray, FloatArray> {
            val (px, py) = resampleByStep(xs, ys)
            val n = px.size
            val pause = FloatArray(n)
            if (ts == null || ts.size != xs.size || xs.size < 3) return Triple(px, py, pause)

            val segSpeed = FloatArray(xs.size - 1)
            val cumDist = FloatArray(xs.size)
            for (k in 0 until xs.size - 1) {
                val d = dist(xs[k], ys[k], xs[k + 1], ys[k + 1])
                cumDist[k + 1] = cumDist[k] + d
                val dtMs = (ts[k + 1] - ts[k]).coerceAtLeast(1L).toFloat()
                segSpeed[k] = d / dtMs
            }
            val sortedSpeeds = segSpeed.filter { it > 0f }.sorted()
            val medianSpeed = sortedSpeeds.getOrNull(sortedSpeeds.size / 2) ?: return Triple(px, py, pause)
            if (medianSpeed <= 0f) return Triple(px, py, pause)

            val total = cumDist.last()
            if (total <= 0f) return Triple(px, py, pause)
            var origIdx = 0
            for (i in 0 until n) {
                val targetD = total * i / max(1, n - 1)
                while (origIdx < cumDist.size - 2 && cumDist[origIdx + 1] < targetD) origIdx++
                val v = segSpeed[origIdx]
                pause[i] = ((medianSpeed - v) / medianSpeed).coerceIn(0f, 1f)
            }
            return Triple(px, py, pause)
        }
    }
}
