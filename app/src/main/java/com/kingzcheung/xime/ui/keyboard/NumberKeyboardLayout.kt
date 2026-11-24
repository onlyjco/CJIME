package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.LayoutKind

/** A keypad's number page inherits the text page's geometry settings. */
internal fun numberKeyboardSpacingSection(profile: InputProfile, ascii: Boolean): String = when {
    !ascii && profile.layout.kind == LayoutKind.T9 -> "t9"
    else -> "number"
}

/** Numeric keypad; slot one opens symbols, slot two returns to the original text mode. */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    /** 回车键动作名（换行、发送、搜索等），与主键盘同一来源，决定回车键图标。 */
    enterKeyText: String = "确定",
) {
    // 与符号键盘、普通键盘使用同一套 Qwerty 间距与边距策略，保证两侧按键与键缝完全一致。
    KeyboardKeySpacingScope(modifier,
        policy = KeyVisualPolicy.Qwerty, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->

    val swipeBubble = rememberSwipeBubbleController()
    val keyboardBounds = remember { arrayOf(Rect.Zero) }
    var keyboardWidthPx by remember { mutableFloatStateOf(0f) }
    val liveKeyboardBounds = remember { LiveBounds() }
    val longPressMenuKeyboard: () -> Rect = remember(liveKeyboardBounds) { { liveKeyboardBounds.current(keyboardBounds[0], "keyboard") } }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)


    fun processSwipeState(state: SwipeState, bounds: Rect) {
        // 按键和键盘都按这一刻的位置算，见 [LiveBounds]。
        val origin = liveKeyboardBounds.current(keyboardBounds[0], "keyboard")
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else state
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - origin.left,
                top = bounds.top - origin.top,
                right = bounds.right - origin.left,
                bottom = bounds.bottom - origin.top
            )
        )
    }

    // 键缝动效的光层（默认关，关着时是 null）。
    val gapLight = rememberKeyGapLight()

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius, LocalLongPressMenuKeyboard provides longPressMenuKeyboard,
        LocalKeyGapLight provides gapLight,
        LocalKeyboardPunctuation provides LocalKeyboardPunctuation.current?.copy(numberPanel = true)) {
    Box(
        modifier = bodyModifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds[0] = coordinates.boundsInRoot()
                val width = coordinates.size.width.toFloat()
                if (keyboardWidthPx != width) keyboardWidthPx = width
                liveKeyboardBounds.coordinates = coordinates
            }
    ) {
        // 键缝动效的光层：最先画 = 在所有键帽底下，只从键缝里露出来。
        KeyGapLightLayer(gapLight, Modifier.matchParentSize())
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, keyTextColor, specialKeyTextColor, keyboardWidthPx)
        run {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = KeyboardGridBottomMargin),
            ) {
                NumberRows(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    specialKeyTextColor = specialKeyTextColor,
                    enterKeyText = enterKeyText,
                    onSwipeStateChange = ::processSwipeState
                )
            }
            }
        }

    }
    }
    }
}

@Composable
private fun NumberRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    compactMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    enterKeyText: String = "确定",
) {
    val modeWeight = NineGridFrame.BOTTOM_MODE
    // 左栏符号和数字键同一个字号（用户 11-18：符号和按键字号不一样）。
    val symFontSize = if (compactMode) 16.sp else KeyboardKeyMetrics.LabelSize
    val keyFontSize = if (compactMode) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val ctrlFontSize = if (compactMode) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    // 左栏符号可在「按键符号 → 数字侧栏」改（用户 11-09）；没改过仍是 + - * /。四格一屏，多出的上下滑，末尾一格「编辑」。
    val symbols = SideSymbolTarget.NUMBER.resolve(LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.NUMBER))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 两侧按键（左栏、右栏及底栏「中文」「!@#」「确定」）与符号键盘按同一列宽（1 / symbolCols）对齐。
        val symbolCols = symbolGridGeometry(maxWidth.value, (maxHeight.value * 0.75f).coerceAtLeast(1f)).columns.toFloat()
        val mainWeight = (symbolCols - 2f).coerceAtLeast(2f)
        val bottomCenterWeight = (symbolCols - 3f).coerceAtLeast(1.5f)
        val dotWeight = minOf(1f, bottomCenterWeight * 0.4f)
        val zeroWeight = bottomCenterWeight - dotWeight
    Column(Modifier.fillMaxSize()) {
        // Numeric navigation uses the operator-column width, independent of the text row.
        Row(Modifier.fillMaxWidth().weight(3f)) {
            NumberSymbolRail(symbols = symbols, onKeyPress = onKeyPress, onKeyPressDown = onKeyPressDown,
                backgroundColor = keyBackgroundColor, textColor = keyTextColor, fontSize = symFontSize,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("number-operators")
                    .padding(scaledKeyVisualPadding())
                    // 键缝动效：左栏不是键，按它不画；登记位置是为了线能绕着它走。
                    .then(keyGapLightSource(GapLightKind.NONE)))
            Column(Modifier.weight(mainWeight).fillMaxHeight()) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { key ->
                            KeyButton(text = key, onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) }, modifier = Modifier.weight(1f),
                                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().testTag("number-actions")) {
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") }, backgroundColor = specialKeyBackgroundColor,
                    iconColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("number-delete-key"),
                    swipeText = "清空", onSwipe = { onKeyPress("clear_composition") },
                    onLongClick = { onKeyPress("delete") }, onPress = { onKeyPressDown?.invoke("delete") },
                    followSymbolSwipeDirection = true,
                    upFeedback = KeyFlickFeedback.DELETE,
                    swipeUpLabel = "上滑清空", downFeedback = KeyFlickFeedback.UNDO, swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") }, onSwipeDown = { onKeyPress("undo_clear") },
                    distanceDelete = true,
                    onSwipeStateChange = onSwipeStateChange,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius)
                SpaceKeyButton(schemaName = "空格", onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
                // 表情在工具栏里已有入口；这里放邮箱、@人常用的 @，和小数点一样是直接上屏的字符键。
                KeyButton(text = "@", onClick = { onKeyPress("@") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f).testTag("number-at-key"), onPress = { onKeyPressDown?.invoke("@") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // 数字键盘左下角依次放「中文/返回」和「!@#」符号键（调换 slot 2 与 slot 1 位置），宽度与符号键盘对齐。
            for (slot in listOf(2, 1)) KeyboardModeKey(slot = slot, page = KeyboardInputPage.NUMBERS,
                onKeyPress = onKeyPress, backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor, modifier = Modifier.weight(1f),
                onKeyPressDown = onKeyPressDown, shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            KeyButton(text = "0", onClick = { onKeyPress("0") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(zeroWeight).testTag("number-zero"), onPress = { onKeyPressDown?.invoke("0") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            // 点键上角标「:」（用户 11-09：IP 后面接端口 192.168.1.1:8080）。和空格两侧标点键同一个「底排标点固定上滑」：
            // 开着时上滑或长按输入，不随「反转上下滑方向」变成下滑（键在屏幕底边，往下滑不出去）。
            val bottomSwipeUp = LocalKeyboardInputPreferences.current.bottomPunctuationSwipeUp
            SwipeableKeyButton(text = ".", onClick = { onKeyPress(".") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(dotWeight).testTag("number-dot-key"), onPress = { onKeyPressDown?.invoke(".") },
                swipeText = NUMBER_DOT_ALTERNATE, symbolInputText = NUMBER_DOT_ALTERNATE,
                onSwipe = { onKeyPress(NUMBER_DOT_ALTERNATE) }, onSwipeStateChange = onSwipeStateChange,
                followSymbolSwipeDirection = !bottomSwipeUp, keepSymbolSwipeOnLongPress = bottomSwipeUp,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            ActionKeyButton(text = enterKeyText, onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
        }
    }
    }
}

/** 数字键盘点键的上角标：IP 地址后接端口用。 */
internal const val NUMBER_DOT_ALTERNATE = ":"

/**
 * 数字键盘左栏：整列一个键帽（圆角、阴影画在外框上，和九键左栏一样），一屏四格，
 * 多出的上下滑（右缘滚动条滑动时出现），末尾一格「编辑」打开「按键符号 → 数字侧栏」。
 */
@Composable
private fun NumberSymbolRail(
    symbols: List<String>,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shapes = KeyShapes.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, shapes, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shapes.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(color = color, topLeft = Offset(0f, offsetPx), size = size, cornerRadius = CornerRadius(cornerPx))
            }
        } else Modifier
    }
    BoxWithConstraints(modifier
        .then(shadowModifier)
        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
        .background(frostedKeyColor(backgroundColor, textColor, frostedGlass))) {
        val rowHeight = maxHeight / NineGridFrame.SIDE_VISIBLE_SLOTS
        val railState = rememberLazyListState()
        LazyColumn(state = railState, modifier = Modifier.fillMaxSize().testTag("number-symbol-rail")
            .railScrollIndicator(railState, textColor.copy(alpha = 0.35f))) {
            itemsIndexed(symbols) { index, symbol ->
                NumberSymbolKey(text = symbol, onClick = { onKeyPress(symbol) },
                    onPress = { onKeyPressDown?.invoke(symbol) }, backgroundColor = backgroundColor,
                    textColor = textColor, fontSize = fontSize,
                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("number-symbol:$index"))
            }
            item(key = "edit") {
                SideRailEditItem(SideSymbolTarget.NUMBER, textColor, backgroundColor,
                    iconSize = (fontSize.value * 1.1f).dp, onPress = { onKeyPressDown?.invoke("edit") },
                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("number-symbols-edit"))
            }
        }
    }
}

/** 左栏里的一格：底色由整列画，这里只画按下态和字。 */
@Composable
private fun NumberSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = KeyboardKeyMetrics.LabelSize,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    // 和字母键一样松手也给「按键抬起震动」（用户 11-23：九键抬起震动无效）。
    val currentOnRelease by rememberUpdatedState(keyReleaseOrShared(null, onPress != null))
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    BoxWithConstraints(
        modifier = modifier
            .background(if (isPressed) frostedKeyColor(backgroundColor, textColor, frostedGlass,
                legacyStateColor = backgroundColor.copy(alpha = 0.7f), pressed = true) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                    currentOnRelease?.invoke()
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        val fittedSize = keyLabelSizeSp(text, fontSize.value, maxWidth.value, maxHeight.value - 4f, density.fontScale,
            LocalKeyboardInputPreferences.current.keyTextScale)
        Text(
            text = punctuationKeyLabel(text),
            color = textColor,
            fontSize = fittedSize.sp,
            lineHeight = (fittedSize * 1.2f).sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 2.dp),
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
