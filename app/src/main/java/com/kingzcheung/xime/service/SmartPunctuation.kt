package com.kingzcheung.xime.service

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 智能标点开关：开启后，中文键盘在数字或英文字母后输入标点符号时自动转为半角。
 */
internal object SmartPunctuation {
    const val PREF_KEY = "smart_punctuation"

    fun isEnabled(context: Context): Boolean =
        SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        SettingsPreferences.getPrefsPublic(context).edit().putBoolean(PREF_KEY, enabled).apply()
    }

    /** 光标前一个字符是否为数字（半角/全角）或英文字母（半角/全角）。 */
    fun isAfterDigitOrEnglish(before: CharSequence?): Boolean {
        val c = before?.lastOrNull() ?: return false
        return c in '0'..'9' || c in '０'..'９' ||
            c in 'A'..'Z' || c in 'a'..'z' ||
            c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ'
    }

    /** 将所有常见中文/全角标点符号转为对应半角符号。 */
    fun toHalfWidth(text: String): String {
        if (text.isEmpty()) return text
        if (text == "……") return "..."
        if (text == "——") return "--"
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '。', '｡', '·', '・' -> out.append('.')
                '，' -> out.append(',')
                '、', '､' -> out.append('\\')
                '？' -> out.append('?')
                '！' -> out.append('!')
                '：' -> out.append(':')
                '；' -> out.append(';')
                '“', '”', '「', '」', '『', '』', '｢', '｣' -> out.append('"')
                '‘', '’' -> out.append('\'')
                '（' -> out.append('(')
                '）' -> out.append(')')
                '【', '〔' -> out.append('[')
                '】', '〕' -> out.append(']')
                '《', '〈' -> out.append('<')
                '》', '〉' -> out.append('>')
                '～' -> out.append('~')
                '￥' -> out.append('$')
                '—', '－' -> out.append('-')
                '…' -> out.append("...")
                in '！'..'～' -> out.append((c.code - 0xFEE0).toChar())
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
