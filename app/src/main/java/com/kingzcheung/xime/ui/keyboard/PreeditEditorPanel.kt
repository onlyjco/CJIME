package com.kingzcheung.xime.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.rime.PinyinEditDisplay

/**
 * 点拼音时手指落点对到哪个光标位置：离某个音节分界（隔音符两边、开头、末尾）不到 [slopPx] 就落在那个分界上，
 * 否则照字落点。看得到的间距不变，只是分界两边的可点范围放宽。
 * [isSeparator] 是这段字里的分隔符（编辑条里是隔音符，屏上拼音还有空格、零宽空格）。
 */
internal fun snapPreeditTap(layout: TextLayoutResult, position: Offset, slopPx: Float,
    isSeparator: (Char) -> Boolean): Int {
    val text = layout.layoutInput.text.text
    val exact = layout.getOffsetForPosition(position)
    if (text.isEmpty()) return exact
    val line = layout.getLineForVerticalPosition(position.y)
    val start = layout.getLineStart(line)
    val end = layout.getLineEnd(line)
    var best = exact
    var bestDistance = Float.MAX_VALUE
    for (offset in start..end) {
        val boundary = offset == 0 || offset == text.length ||
            isSeparator(text[offset - 1]) || isSeparator(text[offset])
        if (!boundary) continue
        val distance = kotlin.math.abs(layout.getHorizontalPosition(offset, true) - position.x)
        if (distance < bestDistance) { bestDistance = distance; best = offset }
    }
    return if (bestDistance <= slopPx) best else exact
}

/** 编辑条里音节分界两边各放宽多少（约半个指尖）。 */
internal val PreeditTapSlop = 10.dp

/** 屏上拼音（气泡、小签、整行）里的分隔符。 */
internal fun isShownPreeditSeparator(c: Char): Boolean = c == ' ' || c == '\'' || c == '\u200B'

/**
 * 记下点屏上拼音时手指落在哪个字母后面，打开编辑时光标放在那里。
 * 外层可点的块用 [record]，里面的字用 [textModifier]；点开时 [lettersBefore] 给出落点前面有几个字母，算不出来是 null（光标照旧）。
 */
internal class PreeditTapPoint {
    private var box: androidx.compose.ui.layout.LayoutCoordinates? = null
    private var textCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
    private var textLayout: TextLayoutResult? = null
    private var down: Offset? = null

    /** 外层可点的块：记下它的坐标和手指按下的位置（不吃掉点按，点开照旧由它的 clickable 管）。 */
    fun record(): Modifier = Modifier
        .onGloballyPositioned { box = it }
        .pointerInput(this) {
            awaitEachGesture { down = awaitFirstDown(requireUnconsumed = false).position }
        }

    /** 里面那段字：记下它的坐标；排版结果交给 [layout]。 */
    fun textModifier(): Modifier = Modifier.onGloballyPositioned { textCoordinates = it }

    fun layout(result: TextLayoutResult) { textLayout = result }

    fun lettersBefore(slopPx: Float): Int? {
        val point = down ?: return null
        val outer = box?.takeIf { it.isAttached } ?: return null
        val inner = textCoordinates?.takeIf { it.isAttached } ?: return null
        val result = textLayout ?: return null
        val offset = snapPreeditTap(result, inner.localPositionOf(outer, point), slopPx, ::isShownPreeditSeparator)
        return com.kingzcheung.xime.rime.preeditLettersBefore(result.layoutInput.text.text, offset)
    }
}

/**
 * 拼音编辑条内容本体：浮在应用上时直接放进拼音气泡同一个 Popup 里切换，不拆窗、不重建模糊、无展开动画、无涟漪闪白；
 * 放在键盘内（floatingColor == null）时保持原样两块样式。
 */
@Composable
internal fun PreeditEditorContent(
    session: PinyinEditSession,
    keyColor: Color,
    textColor: Color,
    accentColor: Color,
    onClose: () -> Unit,
    onCaret: (Int) -> Unit,
    onReplace: (String) -> Unit,
    preedit: String = session.text,
    floatingColor: Color? = null,
    textShadow: Shadow? = null,
    modifier: Modifier = Modifier,
) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onClose)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val currentCaret by rememberUpdatedState(onCaret)
    // 保持与预览气泡一致的自动音节分隔符，避免点开编辑条时尾部编码间距变化导致位移。
    val display = remember(session.text, session.isT9, session.protectedText, preedit) {
        PinyinEditDisplay(session.text, preedit.removePrefix(session.protectedText).trim(), session.isT9,
            includeAutomaticBoundaries = true)
    }
    val code = display.text.ifEmpty { " " }
    val displayCaret = display.displayOffset(session.caret)
    LaunchedEffect(session.caret, code, layout) {
        layout?.takeIf { it.layoutInput.text.text == code }?.getCursorRect(displayCaret.coerceIn(0, code.length))?.let {
            if (it.left < scroll.value) scroll.scrollTo(it.left.toInt().coerceAtLeast(0))
            else if (it.right > scroll.value + scroll.viewportSize)
                scroll.scrollTo((it.right - scroll.viewportSize + 16).toInt().coerceAtLeast(0))
        }
    }
    val codeColor = floatingColor?.let { readableThemeText(textColor, it.copy(alpha = 1f)) } ?: textColor.copy(alpha = 0.9f)
    val iconColor = floatingColor?.let { codeColor } ?: textColor
    val halo = remember(codeColor, floatingColor) { floatingPreeditHalo(codeColor, floatingColor) }
    val textStyle = LocalTextStyle.current.copy(shadow = textShadow)
    val lineHeight = PreeditStyle.FontSize * 1.35f
    val rowSurface = if (floatingColor == null) Modifier
        else Modifier.heightIn(min = PreeditStyle.SurfaceHeight).clip(RoundedCornerShape(4.dp))
            .background(floatingColor).glassTextHalo(halo)
    val outerModifier = if (floatingColor == null) {
        modifier.height(44.dp).testTag("preedit-editor")
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
    } else {
        // 与 PreeditLabel 保持完全一致的上下留白（44dp 最小高、上下各 6dp），文字起点与基线不位移。
        modifier.heightIn(min = 44.dp).padding(vertical = 6.dp).testTag("preedit-editor")
    }
    Row(outerModifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(rowSurface), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).then(if (floatingColor == null) Modifier.preeditSurface(keyColor)
                    else Modifier.heightIn(min = PreeditStyle.SurfaceHeight).padding(horizontal = 8.dp))
                .testTag("preedit-editor-surface"),
                contentAlignment = Alignment.CenterStart) {
                Text(code, color = codeColor, fontSize = PreeditStyle.FontSize, lineHeight = lineHeight,
                    maxLines = 1, softWrap = false, style = textStyle,
                    onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)
                        .padding(vertical = if (floatingColor != null) 4.dp else 0.dp)
                        .testTag("preedit-editor-code")
                        .semantics {
                            editableText = AnnotatedString(session.text)
                            textSelectionRange = TextRange(session.caret)
                            setSelection { start, end, _ ->
                                if (start != end) false else { currentCaret(start.coerceIn(0, session.text.length)); true }
                            }
                            setText { onReplace(it.text); true }
                        }
                        .pointerInput(code) {
                            // 点：靠近音节分界就落在分界上；拖：照字走，细调用。
                            val slop = PreeditTapSlop.toPx()
                            detectTapGestures { point -> layout?.let {
                                currentCaret(display.rawOffset(snapPreeditTap(it, point, slop) { c -> c == '\'' }))
                            } }
                        }
                        .pointerInput(code) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                layout?.let { currentCaret(display.rawOffset(it.getOffsetForPosition(change.position))) }
                            }
                        }
                        .drawBehind {
                            layout?.takeIf { it.layoutInput.text.text == code }?.getCursorRect(displayCaret.coerceIn(0, code.length))?.let {
                                drawLine(accentColor, Offset(it.left, it.top), Offset(it.left, it.bottom), 2.dp.toPx())
                            }
                        })
            }
            Box(Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(4.dp))
                .then(if (floatingColor == null) Modifier.background(keyColor.copy(alpha = 0.62f)) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ).testTag("preedit-editor-close"), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "关闭拼音编辑", tint = iconColor, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A non-focusable editing strip above the IME: no replacement keyboard or extra row of keys. */
@Composable
internal fun PreeditEditorBar(
    session: PinyinEditSession,
    keyColor: Color,
    textColor: Color,
    accentColor: Color,
    onClose: () -> Unit,
    onCaret: (Int) -> Unit,
    onReplace: (String) -> Unit,
    preedit: String = session.text,
    inline: Boolean = false,
) {
    val density = LocalDensity.current
    val gap = with(density) { 2.dp.roundToPx() }
    val floatingColor = if (inline) null else floatingPreeditColor(
        keyColor.takeUnless { it == Color.Unspecified || it.alpha == 0f }
            ?: com.kingzcheung.xime.ui.theme.LocalMaterialPalette.current?.surface ?: keyColor,
        keyColor)
    val editorBounds = remember { mutableStateOf<IntRect?>(null) }
    val position = remember(gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
            anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            .also { offset ->
                val bounds = IntRect(offset, popupContentSize)
                if (editorBounds.value != bounds) editorBounds.value = bounds
            }
    } }
    BoxWithConstraints(if (inline) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(0.dp)) {
        val width = maxWidth
        if (inline) {
            PreeditEditorContent(session, keyColor, textColor, accentColor, onClose, onCaret, onReplace,
                preedit = preedit, floatingColor = null, modifier = Modifier.width(width))
        } else {
            val glass = LocalKeyboardInputPreferences.current.frostedGlass.normalized()
            if (floatingPreeditBlurBehind(glass)) {
                val start = with(density) { 8.dp.roundToPx() }
                val end = with(density) { 4.dp.roundToPx() }
                val vertical = with(density) { 6.dp.roundToPx() }
                PreeditBubbleBlur(editorBounds.value?.let {
                    IntRect(it.left + start, it.top + vertical, it.right - end, it.bottom - vertical)
                }, glass.blurRadiusDp, with(density) { 4.dp.toPx() })
            }
            Popup(popupPositionProvider = position,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                PreeditEditorContent(session, keyColor, textColor, accentColor, onClose, onCaret, onReplace,
                    preedit = preedit, floatingColor = floatingColor,
                    modifier = Modifier.width(width).padding(start = 8.dp, end = 4.dp))
            }
        }
    }
}

internal object PreeditStyle {
    /** 输入的拼音跟「候选字大小」一起放大缩小（用户 11-15：有人说拼音太小）；默认字号下仍是 18sp。 */
    val FontSize: androidx.compose.ui.unit.TextUnit
        @androidx.compose.runtime.Composable get() = (18f * candidateSizeFactor(rememberCandidateTextSize())).sp
    val SurfaceHeight = 32.dp
}

internal fun Modifier.preeditSurface(color: Color): Modifier = heightIn(min = PreeditStyle.SurfaceHeight)
    .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = color.alpha * 0.62f)).padding(horizontal = 8.dp)
