package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.data.SymbolCategory
import com.kingzcheung.xime.data.SymbolData
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.keyboard.commonSymbolsFor
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.SubcharHelper
import kotlinx.coroutines.launch

private const val KEY_SYMBOL_TAB_ORDER = "symbol_category_tab_order"
private const val KEY_SYMBOL_TAB_HIDDEN = "symbol_category_tab_hidden"

internal fun resolveSymbolCategories(
    base: List<SymbolCategory>,
    order: List<String>,
    hidden: Set<String>,
): List<SymbolCategory> {
    val byId = base.associateBy { it.id }
    val ordered = buildList {
        order.forEach { id -> byId[id]?.let(::add) }
        val seen = mapTo(mutableSetOf()) { it.id }
        base.forEach { if (it.id !in seen) add(it) }
    }
    return ordered.filterNot { it.id in hidden }.ifEmpty { base }
}

internal fun moveSymbolTab(visibleIds: List<String>, targetId: String, delta: Int): List<String> {
    val from = visibleIds.indexOf(targetId)
    val to = from + delta
    if (from < 0 || to !in visibleIds.indices) return visibleIds
    return visibleIds.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun hideSymbolTab(visibleIds: List<String>, hidden: Set<String>, targetId: String): Pair<List<String>, Set<String>> {
    if (visibleIds.size <= 1 || targetId !in visibleIds) return visibleIds to hidden
    return visibleIds.filterNot { it == targetId } to (hidden + targetId)
}

@Composable
fun SymbolKeyboardLayout(
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onKeyPressDown: ((String) -> Unit)? = null,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    /** 分类切换的振动，也供没有按键反馈回调的面板使用。 */
    onHapticFeedback: (() -> Unit)? = null,
    onNumber: () -> Unit,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    specialKeyBackgroundColor: Color = accentColor,
    specialKeyTextColor: Color = textColor,
    onSelectExact: (String) -> Unit = onSelect,
    isFloatingMode: Boolean = false,
    enterKeyText: String = "回车",
    bubbleBackgroundColor: Color = specialKeyBackgroundColor,
    keyCornerRadius: Dp = LocalKeyCornerRadius.current,
) {
    val context = LocalContext.current
    // 常用在首屏；最近使用（LRU）作为紧邻分类，点击符号时置顶记录
    var recentSymbols by remember {
        mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_SYMBOLS))
    }
    var returnAfterPick by remember {
        mutableStateOf(SettingsPreferences.isSymbolReturnAfterPickEnabled(context))
    }
    val textLabel = LocalTextModeLabel.current
    val japanese = LocalKeyboardPunctuation.current?.japanese == true
    // 「按键符号 › 常用符号栏」里自己填的符号、短语排在「常用」页最前面，按原样上屏（默认的「符号」键进的就是这个面板）。
    val ownCommon = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.COMMON).orEmpty()
    val tabPrefs = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    var tabOrder by remember(tabPrefs) {
        mutableStateOf(tabPrefs.getString(KEY_SYMBOL_TAB_ORDER, "").orEmpty().split(',').filter { it.isNotBlank() })
    }
    var hiddenTabs by remember(tabPrefs) {
        mutableStateOf(tabPrefs.getStringSet(KEY_SYMBOL_TAB_HIDDEN, emptySet()).orEmpty().toSet())
    }
    var editingTabId by remember { mutableStateOf<String?>(null) }

    val baseCategories = remember(recentSymbols, textLabel, japanese, ownCommon) {
        listOf(SymbolCategory(name = "常用", id = "common",
                symbols = ownCommon + commonSymbolsFor(textLabel).filterNot { it in ownCommon }),
            SymbolCategory(name = "最近", id = "recentSymbols", symbols = recentSymbols)) +
            SymbolData.categories.map { category ->
                if (japanese && category.id == "punctuationSymbols") category.copy(name = "全")
                else category
            }
    }
    val defaultCategoryIds = remember(baseCategories) { baseCategories.map { it.id } }

    fun saveTabPrefs(order: List<String>, hidden: Set<String>) {
        val normalizedOrder = if (hidden.isEmpty() && order == defaultCategoryIds) emptyList() else order
        tabOrder = normalizedOrder
        hiddenTabs = hidden
        tabPrefs.edit()
            .putString(KEY_SYMBOL_TAB_ORDER, normalizedOrder.joinToString(","))
            .putStringSet(KEY_SYMBOL_TAB_HIDDEN, hidden)
            .apply()
    }

    val displayCategories = remember(baseCategories, tabOrder, hiddenTabs) {
        resolveSymbolCategories(baseCategories, tabOrder, hiddenTabs)
    }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = 0, // Common symbols are always the first page, including on a fresh install.
        pageCount = { displayCategories.size }
    )
    // 点击标签时同步更新选中态，避免松手与 scrollToPage 协程之间出现 1 帧空窗闪白。
    var selectedTabIndex by remember { mutableStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.currentPage) {
        selectedTabIndex = pagerState.currentPage
    }

    // 在「最近」页上点时不立刻重排，离开这一页再刷新，免得符号在手指下面换位置。
    val onRecentPage = displayCategories.getOrNull(pagerState.settledPage)?.id == "recentSymbols"
    LaunchedEffect(onRecentPage) {
        if (!onRecentPage) recentSymbols = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_SYMBOLS)
    }

    val swipeBubble = rememberSwipeBubbleController()
    val keyboardBounds = remember { arrayOf(Rect.Zero) }
    var keyboardWidthPx by remember { mutableFloatStateOf(0f) }
    val liveKeyboardBounds = remember { LiveBounds() }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val origin = liveKeyboardBounds.current(keyboardBounds[0], "symbol-keyboard")
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

    val keySpacing = KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty")
    // The spacing scope hands back a fresh modifier; keep the host's glass inheritance marker,
    // otherwise the panel paints a second keyboard background over the glass.
    val inheritBackground = modifier.inheritsKeyboardBackground()
    // 键缝动效的光层（默认关，关着时是 null）：点符号键打开这一页时从符号键的位置扩开（用户 11-22），
    // 和数字页、常用符号页一样点按也有键缝光。
    val gapLight = rememberKeyGapLight()
    KeyboardKeySpacingScope(modifier.padding(bottom = bottomPaddingDp.dp),
        policy = KeyVisualPolicy.Qwerty, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacing) { bodyModifier ->
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = keySpacing.first?.dp ?: 2.dp,
            vertical = keySpacing.second?.dp ?: 2.dp,
        ),
        LocalKeyGapLight provides gapLight,
    ) {
    Box(
        modifier = bodyModifier
            .fillMaxWidth()
            .inheritKeyboardBackground(inheritBackground)
            .keyboardPanelBackground(backgroundColor)
            .onGloballyPositioned { coordinates ->
                keyboardBounds[0] = coordinates.boundsInRoot()
                val width = coordinates.size.width.toFloat()
                if (keyboardWidthPx != width) keyboardWidthPx = width
                liveKeyboardBounds.coordinates = coordinates
            }
            .padding(bottom = KeyboardGridBottomMargin)
    ) {
    // 最先画 = 在所有键帽底下，只从键缝里露出来。
    KeyGapLightLayer(gapLight, Modifier.matchParentSize())
    KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, textColor, specialKeyTextColor, keyboardWidthPx)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridHeightDp = (maxHeight.value * 0.75f).coerceAtLeast(1f)
        val grid = symbolGridGeometry(maxWidth.value, gridHeightDp)
        val columns = grid.columns
        val symbolColumns = (columns - 1).coerceAtLeast(1)
        val cellWidth = maxWidth / columns
        // 和字母键同一个字号：同一基准、同一键帽尺度，也跟「按键字号」走（用户 11-18：符号和按键字号不一样）。
        val symbolFontSize = keyLabelSizeSp("中", KeyboardKeyMetrics.LabelSize.value, maxWidth.value / columns,
            grid.rowHeightDp, LocalDensity.current.fontScale,
            LocalKeyboardInputPreferences.current.keyTextScale).sp
        // 直接复用外层 KeyboardKeySpacingScope 算出的按键间距度量，与普通键盘、数字键盘保持完全一致的键缝（含「加宽按键」与自定义间距）。
        Column(modifier = Modifier.fillMaxSize()) {
        // 上三排：左侧符号网格 + 右侧竖排动作列（与数字键盘右栏同列宽、同排布）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(3f)
        ) {
            Box(
                modifier = Modifier
                    .weight(symbolColumns.toFloat())
                    .fillMaxHeight()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val category = displayCategories.getOrNull(page) ?: return@HorizontalPager
                    val preserveWidth = category.id == "englishSymbols"

                    if (category.symbols.isEmpty()) {
                        // 最近使用为空时的占位提示
                        PanelEmptyHint("暂无最近使用", textColor, Modifier.fillMaxSize())
                    } else {
                        // 大分类有数百个符号：只组合可见行，打开或切换分类时不一次创建全部按键。
                        val rows = remember(category.symbols, symbolColumns) { category.symbols.chunked(symbolColumns) }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("symbol-grid:${category.id}"),
                            verticalArrangement = Arrangement.Top
                        ) {
                            items(rows) { rowSymbols ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    rowSymbols.forEach { symbol ->
                                        SymbolButton(
                                            symbol = symbol,
                                            preserveWidth = preserveWidth,
                                            onClick = {
                                                if (onKeyPressDown != null) onKeyPressDown(symbol) else onHapticFeedback?.invoke()
                                                val updated = RecentUsageStore.record(
                                                    context, RecentUsageStore.KEY_RECENT_SYMBOLS, symbol
                                                )
                                                if (!onRecentPage) recentSymbols = updated
                                                if (preserveWidth || (category.id == "common" && symbol in ownCommon)) onSelectExact(symbol)
                                                else onSelect(symbol)
                                                // 「选完符号自动返回」（默认关）：符号已排进按键队列，回到打字键盘不影响上屏。
                                                if (returnAfterPick || SettingsPreferences
                                                        .isSymbolReturnAfterPickEnabled(context)) onBack()
                                            },
                                            modifier = Modifier.weight(1f).height(grid.rowHeightDp.dp).testTag("symbol-key:$symbol"),
                                            fontSize = symbolFontSize,
                                            textColor = textColor,
                                            backgroundColor = keyBgColor,
                                        )
                                    }
                                    repeat(symbolColumns - rowSymbols.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右侧竖排与数字键盘同步：右上删除（带「清空」角标）、中间空格与锁定切换，最下回车在底栏。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("symbol-actions")
            ) {
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onSelect("delete") },
                    onLongClick = { onSelect("delete") },
                    onPress = { onKeyPressDown?.invoke("delete") },
                    swipeText = "清空",
                    onSwipe = { onSelect("clear_composition") },
                    distanceDelete = true,
                    followSymbolSwipeDirection = true,
                    upFeedback = KeyFlickFeedback.DELETE,
                    swipeUpLabel = "上滑清空",
                    downFeedback = KeyFlickFeedback.UNDO,
                    swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onSelect("clear_all") },
                    onSwipeDown = { onSelect("undo_clear") },
                    onSwipeStateChange = ::processSwipeState,
                    backgroundColor = specialKeyBackgroundColor,
                    iconColor = specialKeyTextColor,
                    modifier = Modifier.weight(1f).testTag("symbol-delete")
                        .semantics { contentDescription = "删除" },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                SpaceKeyButton(
                    schemaName = "空格",
                    onClick = { onSelect("space") },
                    backgroundColor = keyBgColor,
                    textColor = textColor,
                    modifier = Modifier.weight(1f).testTag("symbol-space"),
                    onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                val locked = !returnAfterPick
                IconKeyButton(
                    icon = rememberVectorPainter(if (locked) Icons.Default.Lock else Icons.Default.LockOpen),
                    onClick = {
                        val nextReturn = !returnAfterPick
                        returnAfterPick = nextReturn
                        SettingsPreferences.setSymbolReturnAfterPickEnabled(context, nextReturn)
                        if (onKeyPressDown == null) onHapticFeedback?.invoke()
                    },
                    onPress = { onKeyPressDown?.invoke("symbol_lock") },
                    backgroundColor = keyBgColor,
                    iconColor = if (locked) textColor else textColor.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f).testTag("symbol-lock"),
                    contentDescription = if (locked) "锁定符号面板" else "选完自动返回",
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        // 底栏与数字键盘按列对齐：「中文」「123」「回车」各占 1 列，中间标签栏占余下列且每项标签同为 1 列宽。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (slot in 1..2) {
                KeyboardModeKey(
                    slot = slot, page = KeyboardInputPage.SYMBOLS,
                    onKeyPress = { action -> if (action == "abc") onBack() else onNumber() },
                    onKeyPressDown = { action ->
                        if (onKeyPressDown != null) onKeyPressDown(action) else onHapticFeedback?.invoke()
                    },
                    backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
            }
            Box(
                modifier = Modifier
                    .weight((columns - 3).toFloat().coerceAtLeast(1f))
                    .fillMaxHeight()
                    .testTag("symbol-category-strip"),
                contentAlignment = Alignment.CenterStart,
            ) {
                val tabWidth = cellWidth
                val glass = LocalKeyboardInputPreferences.current.frostedGlass
                // Under glass the rail is a translucent keycap like its neighbours, not a solid bar.
                // A light tint over whatever is below (solid colour, gradient or glass); on a solid
                // background this equals the former lerp(background, text, 0.05).
                val categoryColor = frostedKeyColor(lerp(backgroundColor, textColor, 0.05f), textColor, glass,
                    legacyStateColor = textColor.copy(alpha = 0.05f))
                Row(
                    Modifier.fillMaxSize().padding(scaledKeyVisualPadding())
                        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                        .background(categoryColor)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    displayCategories.forEachIndexed { index, category ->
                        SymbolCategoryTab(
                            name = category.name,
                            isSelected = index == selectedTabIndex,
                            onClick = {
                                onHapticFeedback?.invoke()
                                selectedTabIndex = index
                                scope.launch { pagerState.scrollToPage(index) }
                            },
                            onLongClick = {
                                onHapticFeedback?.invoke()
                                editingTabId = category.id
                            },
                            // The rail already paints the fill; tabs only add the selection tint.
                            backgroundColor = Color.Transparent,
                            textColor = textColor,
                            selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                            modifier = Modifier.width(tabWidth).fillMaxHeight()
                                .testTag("symbol-category:${category.id}"),
                        )
                    }
                }
            }

            ActionKeyButton(
                text = enterKeyText,
                onClick = { onSelect("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f).testTag("symbol-enter"),
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }

        }
        val editingCategory = editingTabId?.let { id -> displayCategories.firstOrNull { it.id == id } }
        if (editingCategory != null) {
            val visibleIds = displayCategories.map { it.id }
            val currentIdx = visibleIds.indexOf(editingCategory.id)
            val canMovePrev = currentIdx > 0
            val canMoveNext = currentIdx in 0 until visibleIds.lastIndex
            val canHide = visibleIds.size > 1
            val isModified = hiddenTabs.isNotEmpty() || visibleIds != defaultCategoryIds
            val cardColors = keyboardCardColors()
            val surfaceColor = lerp(backgroundColor.copy(alpha = 1f), keyBgColor.copy(alpha = 1f), 0.65f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cardColors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { editingTabId = null },
                    )
                    .testTag("symbol-tab-editor-overlay"),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 300.dp)
                        .shadow(8.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(surfaceColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "编辑「${editingCategory.name}」标签",
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SymbolTabActionPill(
                            label = "前移一位",
                            enabled = canMovePrev,
                            textColor = textColor,
                            backgroundColor = keyBgColor,
                            modifier = Modifier.weight(1f).testTag("symbol-tab-move-prev"),
                        ) {
                            onHapticFeedback?.invoke()
                            val nextOrder = moveSymbolTab(visibleIds, editingCategory.id, -1)
                            saveTabPrefs(nextOrder, hiddenTabs)
                            val targetPage = nextOrder.indexOf(editingCategory.id).coerceAtLeast(0)
                            editingTabId = null
                            scope.launch { pagerState.scrollToPage(targetPage) }
                        }
                        SymbolTabActionPill(
                            label = "后移一位",
                            enabled = canMoveNext,
                            textColor = textColor,
                            backgroundColor = keyBgColor,
                            modifier = Modifier.weight(1f).testTag("symbol-tab-move-next"),
                        ) {
                            onHapticFeedback?.invoke()
                            val nextOrder = moveSymbolTab(visibleIds, editingCategory.id, +1)
                            saveTabPrefs(nextOrder, hiddenTabs)
                            val targetPage = nextOrder.indexOf(editingCategory.id).coerceAtLeast(0)
                            editingTabId = null
                            scope.launch { pagerState.scrollToPage(targetPage) }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SymbolTabActionPill(
                            label = "隐藏标签",
                            enabled = canHide,
                            textColor = textColor,
                            backgroundColor = keyBgColor,
                            modifier = Modifier.weight(1f).testTag("symbol-tab-hide"),
                        ) {
                            onHapticFeedback?.invoke()
                            val (nextOrder, nextHidden) = hideSymbolTab(visibleIds, hiddenTabs, editingCategory.id)
                            saveTabPrefs(nextOrder, nextHidden)
                            val targetPage = pagerState.currentPage.coerceIn(0, (nextOrder.size - 1).coerceAtLeast(0))
                            editingTabId = null
                            scope.launch { pagerState.scrollToPage(targetPage) }
                        }
                        SymbolTabActionPill(
                            label = "恢复默认",
                            enabled = isModified,
                            textColor = accentColor,
                            backgroundColor = keyBgColor,
                            modifier = Modifier.weight(1f).testTag("symbol-tab-reset"),
                        ) {
                            onHapticFeedback?.invoke()
                            saveTabPrefs(emptyList(), emptySet())
                            editingTabId = null
                            scope.launch { pagerState.scrollToPage(0) }
                        }
                    }
                }
            }
        }
    }
    }
    }
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    backgroundColor: Color,
    preserveWidth: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = KeyboardKeyMetrics.LabelSize,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .padding(scaledKeyVisualPadding())
            .keyGlow(Modifier.clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(frostedKeyColor(backgroundColor, textColor,
                    LocalKeyboardInputPreferences.current.frostedGlass, pressed = isPressed))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (preserveWidth) symbol else punctuationKeyLabel(symbol),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            color = textColor,
            fontFamily = AppFonts.keyFontFamily
        )
    }
}

@Composable
private fun SymbolTabActionPill(
    label: String,
    enabled: Boolean,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = if (enabled) lerp(backgroundColor.copy(alpha = 1f), textColor, 0.08f)
        else backgroundColor.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) textColor else textColor.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SymbolCategoryTab(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    backgroundColor: Color,
    textColor: Color,
    selectedBackgroundColor: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    description: String? = null,
    showIndicator: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fill = if (isSelected) selectedBackgroundColor else backgroundColor
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                selected = isSelected
                role = Role.Tab
                if (description != null) contentDescription = description
                onClick { onClick(); true }
            }
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                longPressTimeoutMillis = CANDIDATE_LONG_PRESS_TIMEOUT_MS,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, color = if (isSelected) textColor else textColor.copy(alpha = 0.65f),
            fontSize = fontSize, maxLines = 1, textAlign = TextAlign.Center,
            fontFamily = AppFonts.keyFontFamily)
        if (isSelected && showIndicator) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.55f)
            .height(2.dp).background(textColor))
    }
}
