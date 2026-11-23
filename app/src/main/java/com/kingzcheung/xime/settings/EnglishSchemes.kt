package com.kingzcheung.xime.settings

/**
 * 英文输入（直接输入 [InputModes.ENGLISH]）：母语方案的 ascii 模式，字母逐字上屏，补全另给。
 */
internal object EnglishSchemes {
    fun isEnglishSchema(id: String, entries: List<SchemaInfo> = emptyList()): Boolean =
        id.isNotBlank() && id != InputModes.ENGLISH && InputModes.languageOf(id, entries) == InputLanguage.ENGLISH

    data class Target(val schemaId: String, val asciiMode: Boolean)

    /** 切到英文：在母语方案上开 ascii；若当前或已选是独立英文方案，则先回到 [nativeSchema]。 */
    fun target(entries: List<SchemaInfo>, selected: String?, currentSchema: String, nativeSchema: String?): Target? {
        val base = if (isEnglishSchema(currentSchema, entries)) nativeSchema?.takeUnless { isEnglishSchema(it, entries) }
            else currentSchema
        return base?.takeIf { it.isNotBlank() }?.let { Target(it, asciiMode = true) }
    }

    fun nativeLanguage(schemaLanguage: InputLanguage, before: InputLanguage?, order: List<InputLanguage>,
        available: Set<InputLanguage>): InputLanguage {
        if (schemaLanguage != InputLanguage.ENGLISH) return schemaLanguage
        fun native(language: InputLanguage?) = language != null && language != InputLanguage.ENGLISH && language in available
        return before?.takeIf(::native) ?: (order + available).firstOrNull(::native) ?: InputLanguage.ENGLISH
    }
}
