package com.kingzcheung.xime.settings

object ClipboardWordSegmenter {
    /** 将文本逐字拆分为独立字符单元（保留 `\r\n` 与 Unicode 代理对完整不拆裂）。 */
    fun split(text: String): List<String> = splitCharacters(text)

    fun splitCharacters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                out.add("\r\n")
                i += 2
            } else {
                val count = Character.charCount(text.codePointAt(i))
                out.add(text.substring(i, i + count))
                i += count
            }
        }
        return out
    }
}
