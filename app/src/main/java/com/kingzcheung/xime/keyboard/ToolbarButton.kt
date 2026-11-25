package com.kingzcheung.xime.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", "表情", Icons.Outlined.EmojiEmotions),
    CLIPBOARD("clipboard", "剪贴板", Icons.AutoMirrored.Outlined.Assignment),
    SCHEMA("schema", "输入方式", Icons.Outlined.KeyboardAlt),
    SYMBOL("symbol", "符号", Icons.Outlined.Paid),
    // 保留已保存的旧 ID，悬浮模式统一在键盘调节中设置。
    FLOAT("float", "键盘调节", Icons.Outlined.Tune),
    HANDWRITING_LOOKUP("handwriting_lookup", "手写", Icons.Outlined.Draw),
    EDIT("edit", "编辑", Icons.Outlined.OpenWith),
    VOICE("voice", "语音", Icons.Outlined.Mic),
    // 以下原为菜单专属；工具栏与菜单共用一组入口，可放在任一处或不使用。
    SETTINGS("settings", "设置", Icons.Outlined.Settings),
    // 整屏实时字幕（给听力不好的人看周围人说话），打开独立页面。装了「字幕」插件才出现（CaptionPlugin）。
    CAPTION("caption", "字幕", Icons.Outlined.Subtitles),
    SOUND("sound", "声音振动", Icons.AutoMirrored.Outlined.VolumeUp),
    FLOATING("floating", "悬浮", Icons.Outlined.PictureInPictureAlt),
    GLASS("glass", "玻璃", Icons.Outlined.BlurOn),
    INCOGNITO("incognito", "无痕", Icons.Outlined.VisibilityOff),
    // 图标随状态切换（半角月牙 / 全角满圆），见 WidthShapeIcons.kt。
    // 名称用于自定义列表；键盘上按当前状态显示“全角”或“半角”（见 [stateLabel]）。
    FULL_SHAPE("full_shape", "全角/半角", HalfWidthIcon),
    // 切换当前方案的输出字形；注音使用独立的简体设置。
    // 键盘上按当前状态显示“繁体”或“简体”（见 [stateLabel]）；图标随状态换「简」或「繁」（TraditionalGlyphIcons.kt、ToolbarButtonIcon）。
    TRADITIONAL("traditional", "简繁", SimplifiedGlyphIcon),
    // 布局手势开关：只在对应布局里出现（见 LayoutGestureMenuItems.kt），其他布局自动隐藏。
    GLIDE("glide", "滑行输入", Icons.Outlined.Gesture),
    T9_FLICK("t9_flick", "九键拂动", Icons.Outlined.SwipeRight),
    FOURTEEN_SWIPE("fourteen_swipe", "14键滑选", Icons.Outlined.SwipeRight),
    FLICK_DISC("flick_disc", "拂动圆盘", Icons.Outlined.Explore);

    companion object {
        // 仅在用户还没有保存工具栏偏好时使用，保留已有选择及顺序。
        val DEFAULT_VISIBLE = linkedSetOf(SCHEMA, EMOJI, EDIT, CLIPBOARD, HANDWRITING_LOOKUP, VOICE)

        /** 菜单默认入口：只放打字时会临时调整的入口，其余可在菜单内拖拽加入。 */
        // 字幕不在这份清单里：它是「字幕」插件的按钮，插件装上并启用时才放进菜单（CaptionPlugin）。
        val DEFAULT_MENU = listOf(SETTINGS, FLOAT, SOUND, GLASS, INCOGNITO, FULL_SHAPE)
        /** 布局手势开关：每个布局只显示适用的一两个。 */
        val LAYOUT_GESTURES = listOf(GLIDE, T9_FLICK, FOURTEEN_SWIPE, FLICK_DISC)
        /** 开关类入口：菜单中显示开/关状态，工具栏中开启时高亮。 */
        val TOGGLES = setOf(SOUND, FLOATING, GLASS, INCOGNITO, FULL_SHAPE, TRADITIONAL) + LAYOUT_GESTURES

        /** 通过图标形态本身区分开/关的开关：任务栏与卡片均不显示高亮选框背景。 */
        val ICON_STATE_TOGGLES = setOf(FULL_SHAPE, SOUND, INCOGNITO, TRADITIONAL, GLIDE, GLASS)

        val CUSTOMIZABLE = entries.toList()

        /** 已移除的旧版“固定内容”（quick_phrase）并入剪贴板。 */
        private const val LEGACY_QUICK_PHRASE = "quick_phrase"
        /** 已移除的旧版“排列”按钮，老用户偏好中的残留 ID 直接过滤。 */
        private const val LEGACY_ARRANGE = "arrange"

        /**
         * 已移除的全选、复制、粘贴、段首、段尾入口（用户 2026-10-31：编辑面板里都有）：
         * 放过它们的位置换成“编辑”，同一处已有“编辑”就只留一个。
         */
        private val LEGACY_EDITING = setOf("select_all", "copy", "paste", "home", "end")

        fun normalizeIds(ids: List<String>): List<String> =
            ids.mapNotNull {
                when (it) {
                    LEGACY_QUICK_PHRASE -> CLIPBOARD.id
                    in LEGACY_EDITING -> EDIT.id
                    LEGACY_ARRANGE -> null
                    else -> it
                }
            }.distinct()

        fun fromId(id: String): ToolbarButton? =
            entries.find { it.id == id }
    }
}

data class ToolbarAction(
    val item: ToolbarButtonItem,
    val active: Boolean = false,
    /** 菜单里长按：打开这一项在设置里的对应页；没有对应设置时为 null。 */
    val onLongClick: (() -> Unit)? = null,
    /** 工具栏上长按弹出、滑到一项松手就选它的列表（插件按钮，如「翻译」选目标语言）；null 时没有。 */
    val pressMenu: ToolbarPressMenu? = null,
    /** 工具栏不可横滑时长按触发的快捷操作（如剪贴板图标长按直达收藏栏）；null 时没有。 */
    val onToolbarLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

/** 长按列表：[load] 在长按时才取选项（插件给的），[onChoose] 收到选中的那一项。 */
class ToolbarPressMenu(
    val load: suspend () -> PressMenuOptions?,
    val onChoose: (key: String, option: String) -> Unit,
)

/** 一组选项：[key] 是插件里这一项设置的名字，[selected] 是现在选着的。 */
data class PressMenuOptions(
    val key: String,
    val title: String,
    val options: List<String>,
    val selected: String?,
    /** 选项显示成什么（插件的 optionLabels，如「英文」→「英」）；没有的照原值画。 */
    val labels: Map<String, String> = emptyMap(),
)
