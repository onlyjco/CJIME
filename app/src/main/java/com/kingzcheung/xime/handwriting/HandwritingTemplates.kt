package com.kingzcheung.xime.handwriting

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.InputLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 边用边学的手写模板库（英文字母）。不训练模型：把一个字的笔迹变成特征，和模板比相似度，
 * 最像的几个当候选；你选了哪个，这次的笔迹就存成那个字的新模板。
 */
object HandwritingTemplates {
    private const val TAG = "HandwritingTemplates"
    private const val MAX_PER_LABEL = 3

    const val OCH_TOP_N = 320

    enum class Script(val fileName: String, val sampleTypes: Set<String>) {
        LATIN("latin.json", setOf("latin"));

        fun accepts(label: String): Boolean {
            if (label.codePointCount(0, label.length) != 1) return false
            val cp = label.codePointAt(0)
            return (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || (cp in '0'.code..'9'.code)
        }

        fun available(context: Context): Boolean = FormulaRecognizer.isAvailable(context)

        companion object {
            fun forLanguage(language: InputLanguage): Script? = when (language) {
                InputLanguage.ENGLISH -> LATIN
                else -> null
            }
        }
    }

    internal class Feature(val dense: FloatArray? = null, val idx: IntArray? = null, val vals: FloatArray? = null) {
        fun dot(o: Feature): Float {
            if (dense != null && o.dense != null) {
                var sum = 0f
                val n = minOf(dense.size, o.dense.size)
                for (i in 0 until n) sum += dense[i] * o.dense[i]
                return sum
            }
            if (idx != null && vals != null && o.idx != null && o.vals != null) {
                var sum = 0f
                var i = 0; var j = 0
                while (i < idx.size && j < o.idx.size) {
                    val a = idx[i]; val b = o.idx[j]
                    if (a == b) { sum += vals[i] * o.vals[j]; i++; j++ }
                    else if (a < b) i++
                    else j++
                }
                return sum
            }
            return 0f
        }

        fun toJson(dest: JSONObject = JSONObject()): JSONObject = dest.apply {
            dense?.let { put("d", JSONArray((0 until it.size).map { i -> it[i].toDouble() })) }
            idx?.let { put("i", JSONArray((0 until it.size).map { i -> it[i] })) }
            vals?.let { put("v", JSONArray((0 until it.size).map { i -> (it[i] * 1000f).toInt() / 1000.0 })) }
        }

        companion object {
            fun fromProbabilities(sortedTop: List<Pair<Int, Float>>): Feature {
                if (sortedTop.isEmpty()) return Feature(idx = IntArray(0), vals = FloatArray(0))
                val sumSq = sortedTop.sumOf { (it.second * it.second).toDouble() }.toFloat().coerceAtLeast(1e-12f)
                val invNorm = (1f / kotlin.math.sqrt(sumSq))
                val pairs = sortedTop.sortedBy { it.first }
                val idx = IntArray(pairs.size) { pairs[it].first }
                val vals = FloatArray(pairs.size) { pairs[it].second * invNorm }
                return Feature(idx = idx, vals = vals)
            }
        }
    }

    private class Entry(val label: String, val feat: Feature, val source: String)

    private fun dir(context: Context): File = File(context.filesDir, "handwriting_templates").apply { mkdirs() }

    private val cache = HashMap<Script, MutableList<Entry>>()
    private val defaults = HashMap<Script, List<Entry>>()

    private const val LATIN_DEFAULT_STROKES = "handwriting/latin_default_strokes.json"
    private const val LATIN_DEFAULT_CACHE = "latin_default_v1.json"
    private const val PERSONAL_BONUS = 0.03f

    private var lastFeature: Feature? = null
    private var lastScript: Script? = null

    private fun featureFromJson(o: JSONObject): Feature {
        val d = o.optJSONArray("d")?.let { arr -> FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() } }
        val idx = o.optJSONArray("i")?.let { arr -> IntArray(arr.length()) { i -> arr.getInt(i) } }
        val v = o.optJSONArray("v")?.let { arr -> FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() } }
        return Feature(dense = d, idx = idx, vals = v)
    }

    @Synchronized
    private fun entries(context: Context, script: Script): MutableList<Entry> {
        cache[script]?.let { return it }
        val file = File(dir(context), script.fileName)
        val list = mutableListOf<Entry>()
        if (file.isFile) runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.getString("l")
                list += Entry(label, featureFromJson(o), o.optString("s", "user"))
            }
        }.onFailure { Log.w(TAG, "failed reading template file ${script.fileName}", it) }
        cache[script] = list
        return list
    }

    @Synchronized
    private fun save(context: Context, script: Script) {
        val list = cache[script] ?: return
        val arr = JSONArray()
        for (e in list) {
            val o = e.feat.toJson()
            o.put("l", e.label)
            o.put("s", e.source)
            arr.put(o)
        }
        val file = File(dir(context), script.fileName)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    private fun feature(context: Context, script: Script, strokes: List<List<Pair<Float, Float>>>): Feature? = when (script) {
        Script.LATIN -> FormulaRecognizer.embed(context, strokes)?.let { Feature(dense = it) }
    }

    fun count(context: Context, script: Script): Int = entries(context, script).size + (synchronized(this) { defaults[script]?.size } ?: 0)

    fun ensureDefaults(context: Context, script: Script): Unit = synchronized(defaultLock) { prepareDefaults(context, script) }

    private val defaultLock = Any()

    private fun prepareDefaults(context: Context, script: Script) {
        if (synchronized(this) { defaults.containsKey(script) }) return
        if (script == Script.LATIN) return prepareLatinDefaults(context)
    }

    private fun prepareLatinDefaults(context: Context) {
        if (!Script.LATIN.available(context)) return
        val encoder = File(FormulaRecognizer.dir(context), "formula_encoder.onnx")
        val stamp = "${encoder.path}:${encoder.length()}:${encoder.lastModified()}"
        val cacheFile = File(dir(context), LATIN_DEFAULT_CACHE)
        val cached = runCatching {
            val o = JSONObject(cacheFile.readText())
            if (o.getString("model") != stamp) null else {
                val arr = o.getJSONArray("t")
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { e -> Entry(e.getString("l"), featureFromJson(e), "builtin") } }
            }
        }.getOrNull()
        val list = cached ?: runCatching {
            val chars = JSONObject(context.assets.open(LATIN_DEFAULT_STROKES).bufferedReader(Charsets.UTF_8).use { it.readText() })
                .getJSONObject("chars")
            val out = mutableListOf<Entry>()
            for (label in chars.keys()) {
                val variants = chars.getJSONArray(label)
                for (i in 0 until variants.length()) {
                    val strokes = variants.getJSONArray(i).let { st ->
                        (0 until st.length()).map { j ->
                            val pts = st.getJSONArray(j)
                            (0 until pts.length()).map { k -> pts.getJSONArray(k).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } }
                        }
                    }
                    feature(context, Script.LATIN, strokes)?.let { out += Entry(label, it, "builtin") }
                }
            }
            if (out.isEmpty()) return
            val arr = JSONArray()
            for (e in out) arr.put(e.feat.toJson(JSONObject().put("l", e.label)))
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(JSONObject().put("model", stamp).put("t", arr).toString())
            if (!tmp.renameTo(cacheFile)) { tmp.copyTo(cacheFile, overwrite = true); tmp.delete() }
            out
        }.onFailure { Log.w(TAG, "builtin latin templates failed", it) }.getOrNull() ?: return
        synchronized(this) { defaults[Script.LATIN] = list }
    }

    fun importTrainingSamples(context: Context, script: Script): Int {
        val files = listOf(File(File(context.filesDir, "handwriting_training"), "samples.jsonl"),
            File(com.kingzcheung.xime.handwriting.training.PromptBankFile.dir(context), "samples.jsonl")).filter { it.isFile }
        if (files.isEmpty() || !script.available(context)) return 0
        val known = synchronized(this) { entries(context, script).map { it.source }.toHashSet() }
        var added = 0
        for (file in files) file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            if (o.optString("type") !in script.sampleTypes) return@forEachLine
            val id = "train:" + o.optString("id")
            val label = o.optString("label")
            if (id in known || !script.accepts(label)) return@forEachLine
            val strokes = parseStrokes(o.optJSONArray("strokes")) ?: return@forEachLine
            val feat = feature(context, script, strokes) ?: return@forEachLine
            add(context, script, label, feat, id, persist = false)
            known += id
            added++
        }
        if (added > 0) save(context, script)
        return added
    }

    private fun parseStrokes(arr: JSONArray?): List<List<Pair<Float, Float>>>? {
        arr ?: return null
        val out = mutableListOf<List<Pair<Float, Float>>>()
        for (i in 0 until arr.length()) {
            val pts = arr.getJSONObject(i).optJSONArray("points") ?: continue
            val stroke = (0 until pts.length()).map { j ->
                val p = pts.getJSONArray(j)
                p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
            }
            if (stroke.isNotEmpty()) out += stroke
        }
        return out.ifEmpty { null }
    }

    @Synchronized
    private fun add(context: Context, script: Script, label: String, feat: Feature, source: String, persist: Boolean = true) {
        val list = entries(context, script)
        list += Entry(label, feat, source)
        val same = list.filter { it.label == label }
        if (same.size > MAX_PER_LABEL) list.remove(same.first())
        if (persist) save(context, script)
    }

    fun addTrainingSample(context: Context, type: String, label: String, sampleId: String, strokes: List<List<Pair<Float, Float>>>) {
        val script = Script.entries.firstOrNull { type in it.sampleTypes } ?: return
        if (!script.accepts(label) || !script.available(context)) return
        val feat = feature(context, script, strokes) ?: return
        add(context, script, label, feat, "train:$sampleId")
    }

    fun recognize(context: Context, script: Script, strokes: List<List<Pair<Float, Float>>>, topK: Int = 8,
                  remember: Boolean = true): List<Pair<String, Float>> {
        val list = synchronized(this) { entries(context, script).toList() }
        val builtin = synchronized(this) { defaults[script].orEmpty() }
        if (list.isEmpty() && builtin.isEmpty()) return emptyList()
        val query = feature(context, script, strokes) ?: return emptyList()
        if (remember) {
            lastFeature = query
            lastScript = script
        }
        return rank(query, list.map { it.label to it.feat }, builtin.map { it.label to it.feat }, topK)
    }

    internal fun rank(query: Feature, personal: List<Pair<String, Feature>>, builtin: List<Pair<String, Feature>>, topK: Int): List<Pair<String, Float>> {
        val best = HashMap<String, Float>()
        for ((label, feat) in personal) {
            val s = query.dot(feat) + PERSONAL_BONUS
            if (s > (best[label] ?: -2f)) best[label] = s
        }
        for ((label, feat) in builtin) {
            val s = query.dot(feat)
            if (s > (best[label] ?: -2f)) best[label] = s
        }
        return best.entries.sortedByDescending { it.value }.take(topK)
            .map { it.key to ((it.value + 1f) / 2f).coerceIn(0.0001f, 1f) }
    }

    fun rememberForLearning(context: Context, script: Script, strokes: List<List<Pair<Float, Float>>>?) {
        lastFeature = strokes?.let { feature(context, script, it) }
        lastScript = script.takeIf { lastFeature != null }
    }

    fun learnSelection(context: Context, committed: String) {
        val script = lastScript ?: return
        val feat = lastFeature ?: return
        lastFeature = null
        if (!learningAllowed() || !script.accepts(committed)) return
        val app = context.applicationContext
        Thread { add(app, script, committed, feat, "pick:${System.currentTimeMillis()}") }.start()
    }

    fun forget(context: Context, label: String): Int = synchronized(this) {
        var removed = 0
        for (script in Script.entries) {
            val list = entries(context, script)
            val before = list.size
            list.removeAll { it.label == label && it.source.startsWith("pick:") }
            if (list.size != before) {
                removed += before - list.size
                save(context, script)
            }
        }
        removed
    }

    fun learn(context: Context, label: String): Boolean {
        if (!learningAllowed()) return false
        val feat = lastFeature ?: return false
        val script = lastScript ?: return false
        if (!script.accepts(label)) return false
        add(context, script, label, feat, "user")
        lastFeature = null
        lastScript = null
        return true
    }

    fun forgetAll(context: Context) = synchronized(this) {
        for (s in Script.entries) {
            cache[s]?.clear()
            File(dir(context), s.fileName).delete()
        }
        lastFeature = null
        lastScript = null
    }

    fun onBaseModelUpdated(): Unit = synchronized(this) { defaults.remove(Script.LATIN) }

    fun onRecognizerChanged(context: Context): Unit = synchronized(this) {
        defaults.remove(Script.LATIN)
        cache.remove(Script.LATIN)
        File(dir(context), Script.LATIN.fileName).delete()
    }

    internal fun learningAllowed(): Boolean = !com.kingzcheung.xime.settings.IncognitoState.active

    fun isUsable(context: Context, script: Script): Boolean = script.available(context) && count(context, script) > 0

    internal fun labelsWithCount(context: Context, script: Script): Map<String, Int> =
        synchronized(this) { entries(context, script).groupingBy { it.label }.eachCount() }
}
