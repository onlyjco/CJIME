package com.kingzcheung.xime.clipboard

/** 剪贴板顶部栏的内置分类标签。 */
sealed interface ClipboardTab {
    val key: String
    val label: String

    data class Category(val filter: ClipboardFilter) : ClipboardTab {
        override val key: String get() = filter.name
        override val label: String get() = filter.label
    }
}

internal fun clipboardTabs(): List<ClipboardTab> =
    ClipboardFilter.entries.map { ClipboardTab.Category(it) }

internal fun List<ClipboardTab>.resolve(current: ClipboardTab): ClipboardTab =
    firstOrNull { it.key == current.key } ?: first()

internal fun List<ClipboardTab>.afterSwipe(current: ClipboardTab, delta: Int): ClipboardTab {
    val index = indexOfFirst { it.key == current.key }.coerceAtLeast(0)
    return this[(index + delta).coerceIn(0, lastIndex)]
}
