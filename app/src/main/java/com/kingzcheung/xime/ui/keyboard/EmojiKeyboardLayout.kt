package com.kingzcheung.xime.ui.keyboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.data.EmojiSkinTone
import com.kingzcheung.xime.data.KaomojiData
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.common.CompactMenuStyle
import com.kingzcheung.xime.util.SubcharHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val KEY_EXPRESSION_LAST_SECTION = "expression_last_section"
private const val KEY_EXPRESSION_LAST_EMOJI_PAGE = "expression_last_emoji_page"
private const val KEY_EXPRESSION_LAST_KAOMOJI_PAGE = "expression_last_kaomoji_page"

private enum class ExpressionSection(val label: String, val tag: String) {
    EMOJI("Emoji", "expression-emoji"),
    KAOMOJI("颜文字", "expression-kaomoji"),
}

@Composable
fun EmojiKeyboardLayout(
    onEmojiSelect: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    onHapticFeedback: (() -> Unit)? = null,
    onDeleteFace: ((String?) -> String?)? = null,
    pickedKaomoji: ArrayDeque<String> = remember { ArrayDeque() },
    keyBgColor: Color = backgroundColor,
    specialKeyBackgroundColor: Color = accentColor,
    specialKeyTextColor: Color = textColor,
    bubbleBackgroundColor: Color = specialKeyBackgroundColor,
    enterKeyText: String = "回车",
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = LocalKeyCornerRadius.current,
    isFloatingMode: Boolean = false,
    onKeyPressDown: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val tabPrefs = remember(context) { SettingsPreferences.getPrefsPublic(context) }
    val clipboardManager = remember { ClipboardManager.getInstance(context) }
    val allCategories by ExtensionManager.emojiCategoriesFlow.collectAsStateWithLifecycle()

    var section by remember {
        val savedName = tabPrefs.getString(KEY_EXPRESSION_LAST_SECTION, null)
        mutableStateOf(ExpressionSection.entries.firstOrNull { it.name == savedName } ?: ExpressionSection.EMOJI)
    }
    var recentEmojis by remember {
        mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_EMOJIS))
    }
    var recentKaomoji by remember {
        mutableStateOf(RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_KAOMOJI))
    }

    // Emoji 二级标签首位为「最近」，后面紧跟内置与插件分类。
    val emojiCategories = remember(allCategories, recentEmojis) {
        listOf(EmojiCategory(name = "最近", icon = "最近", emojis = recentEmojis)) +
            allCategories.filter { !it.isPlugin } + allCategories.filter { it.isPlugin }
    }
    val kaomojiCategories = KaomojiData.categories

    val savedEmojiPage = remember(tabPrefs) { tabPrefs.getInt(KEY_EXPRESSION_LAST_EMOJI_PAGE, -1) }
    val initialEmojiPage = remember(emojiCategories.size, savedEmojiPage) {
        when {
            savedEmojiPage >= 0 -> savedEmojiPage.coerceIn(0, (emojiCategories.size - 1).coerceAtLeast(0))
            emojiCategories.size > 1 -> 1
            else -> 0
        }
    }
    val emojiPager = rememberPagerState(
        initialPage = initialEmojiPage,
        pageCount = { emojiCategories.size },
    )
    var initialEmojiChosen by remember { mutableStateOf(emojiCategories.size > 1) }
    LaunchedEffect(emojiCategories.size) {
        if (!initialEmojiChosen && emojiCategories.size > 1) {
            val target = if (savedEmojiPage >= 0) savedEmojiPage.coerceIn(0, emojiCategories.lastIndex) else 1
            emojiPager.scrollToPage(target)
            initialEmojiChosen = true
        }
    }
    var emojiSelectedTab by remember { mutableStateOf(emojiPager.currentPage) }
    LaunchedEffect(emojiPager.currentPage) {
        emojiSelectedTab = emojiPager.currentPage
        if (initialEmojiChosen) {
            tabPrefs.edit().putInt(KEY_EXPRESSION_LAST_EMOJI_PAGE, emojiPager.currentPage).apply()
        }
    }
    val emojiTabList = rememberLazyListState(initialFirstVisibleItemIndex = (initialEmojiPage - 1).coerceAtLeast(0))
    LaunchedEffect(emojiPager.currentPage, emojiCategories.size) {
        if (emojiCategories.isNotEmpty() && emojiTabList.layoutInfo.visibleItemsInfo.isNotEmpty() &&
            emojiTabList.layoutInfo.visibleItemsInfo.none { it.index == emojiPager.currentPage }) {
            emojiTabList.animateScrollToItem(emojiPager.currentPage.coerceAtMost(emojiCategories.lastIndex))
        }
    }
    // 在 Emoji「最近」页上点时不立刻重排，离开这一页再刷新。
    LaunchedEffect(section, emojiPager.settledPage) {
        if (section != ExpressionSection.EMOJI || emojiPager.settledPage != 0) {
            recentEmojis = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_EMOJIS)
        }
    }

    // 颜文字二级标签首位为「最近」（page 0），page i + 1 为对应颜文字分类。
    val initialKaomojiPage = remember(tabPrefs, kaomojiCategories.size) {
        tabPrefs.getInt(KEY_EXPRESSION_LAST_KAOMOJI_PAGE, 1).coerceIn(0, kaomojiCategories.size)
    }
    val kaomojiPager = rememberPagerState(
        initialPage = initialKaomojiPage,
        pageCount = { kaomojiCategories.size + 1 },
    )
    var kaomojiSelectedTab by remember { mutableStateOf(kaomojiPager.currentPage) }
    LaunchedEffect(kaomojiPager.currentPage) {
        kaomojiSelectedTab = kaomojiPager.currentPage
        tabPrefs.edit().putInt(KEY_EXPRESSION_LAST_KAOMOJI_PAGE, kaomojiPager.currentPage).apply()
    }
    LaunchedEffect(section) {
        tabPrefs.edit().putString(KEY_EXPRESSION_LAST_SECTION, section.name).apply()
    }
    val kaomojiTabList = rememberLazyListState(initialFirstVisibleItemIndex = (initialKaomojiPage - 1).coerceAtLeast(0))
    LaunchedEffect(kaomojiPager.currentPage) {
        if (kaomojiTabList.layoutInfo.visibleItemsInfo.isNotEmpty() &&
            kaomojiTabList.layoutInfo.visibleItemsInfo.none { it.index == kaomojiPager.currentPage }) {
            kaomojiTabList.animateScrollToItem(kaomojiPager.currentPage.coerceAtMost(kaomojiCategories.size))
        }
    }
    // 在颜文字「最近」页上点时不立刻重排，离开这一页再刷新。
    LaunchedEffect(section, kaomojiPager.settledPage) {
        if (section != ExpressionSection.KAOMOJI || kaomojiPager.settledPage != 0) {
            recentKaomoji = RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_KAOMOJI)
        }
    }

    val scope = rememberCoroutineScope()
    var tonePicker by remember { mutableStateOf<SkinTonePicker?>(null) }
    val panelCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(section, emojiPager.currentPage) { tonePicker = null }

    val recordRecentEmoji: (String) -> Unit = { emoji ->
        val updated = RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_EMOJIS, emoji)
        if (emojiPager.settledPage != 0) recentEmojis = updated
    }
    val recordRecentKaomoji: (String) -> Unit = { face ->
        val updated = RecentUsageStore.record(context, RecentUsageStore.KEY_RECENT_KAOMOJI, face)
        if (kaomojiPager.settledPage != 0) recentKaomoji = updated
        if (pickedKaomoji.size >= 32) pickedKaomoji.removeFirst()
        pickedKaomoji.addLast(face)
    }

    val swipeBubble = rememberSwipeBubbleController()
    val keyboardBounds = remember { arrayOf(Rect.Zero) }
    var keyboardWidthPx by remember { mutableFloatStateOf(0f) }
    val liveKeyboardBounds = remember { LiveBounds() }

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val origin = liveKeyboardBounds.current(keyboardBounds[0], "emoji-keyboard")
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
    val inheritBackground = modifier.inheritsKeyboardBackground()
    val gapLight = rememberKeyGapLight()

    KeyboardKeySpacingScope(
        modifier.padding(bottom = bottomPaddingDp.dp),
        policy = KeyVisualPolicy.Qwerty,
        allowShrink = isFloatingMode,
        applyGutter = true,
        growthSpacing = keySpacing,
    ) { bodyModifier ->
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
            .fillMaxSize()
            .inheritKeyboardBackground(inheritBackground)
            .keyboardPanelBackground(backgroundColor)
            .onPlaced { panelCoords[0] = it }
            .onGloballyPositioned { coordinates ->
                keyboardBounds[0] = coordinates.boundsInRoot()
                val width = coordinates.size.width.toFloat()
                if (keyboardWidthPx != width) keyboardWidthPx = width
                liveKeyboardBounds.coordinates = coordinates
            }
            .padding(bottom = KeyboardGridBottomMargin)
    ) {
        KeyGapLightLayer(gapLight, Modifier.matchParentSize())
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, textColor, specialKeyTextColor, keyboardWidthPx)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridHeightDp = (maxHeight.value * 0.75f).coerceAtLeast(1f)
            val columns = symbolGridGeometry(maxWidth.value, gridHeightDp).columns
            val leftWeight = (columns - 1).toFloat().coerceAtLeast(1f)
            val cellWidth = maxWidth / columns
            val glass = LocalKeyboardInputPreferences.current.frostedGlass
            val categoryColor = frostedKeyColor(
                lerp(backgroundColor, textColor, 0.05f), textColor, glass,
                legacyStateColor = textColor.copy(alpha = 0.05f)
            )
            val inKaomoji = section == ExpressionSection.KAOMOJI

            Row(Modifier.fillMaxSize()) {
                // 左侧内容区（上 3 份）+ 底部二级分类滑动条（下 1 份，首位各自管理自己的「最近」）
                Column(Modifier.weight(leftWeight).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(3f)) {
                        when (section) {
                            ExpressionSection.EMOJI -> {
                                HorizontalPager(
                                    state = emojiPager,
                                    modifier = Modifier.fillMaxSize().testTag("emoji-pages"),
                                ) { page ->
                                    val category = emojiCategories.getOrNull(page) ?: return@HorizontalPager
                                    EmojiCategoryPage(
                                        category = category,
                                        backgroundColor = backgroundColor,
                                        textColor = textColor,
                                        clipboardManager = clipboardManager,
                                        panelCoords = panelCoords,
                                        onEmojiPick = { emoji ->
                                            recordRecentEmoji(emoji)
                                            onEmojiSelect(emoji)
                                        },
                                        onPluginTextPick = { text -> onEmojiSelect(text) },
                                        onImageEmojiSelect = onImageEmojiSelect,
                                        onOpenTonePicker = { picker ->
                                            onHapticFeedback?.invoke()
                                            tonePicker = picker
                                        },
                                    )
                                }
                            }
                            ExpressionSection.KAOMOJI -> {
                                HorizontalPager(
                                    state = kaomojiPager,
                                    modifier = Modifier.fillMaxSize().testTag("kaomoji-pages"),
                                ) { page ->
                                    val faces = if (page == 0) recentKaomoji else kaomojiCategories[page - 1].faces
                                    KaomojiGrid(
                                        faces = faces,
                                        textColor = textColor,
                                        onPick = { face ->
                                            recordRecentKaomoji(face)
                                            onEmojiSelect(face)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 底部二级分类滑动条：复用符号键盘的圆角底槽 + 选中底色 + 底部指示条款式
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(scaledKeyVisualPadding())
                            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                            .background(categoryColor),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        when (section) {
                            ExpressionSection.EMOJI -> {
                                LazyRow(
                                    state = emojiTabList,
                                    modifier = Modifier.fillMaxSize().testTag("emoji-categories"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    itemsIndexed(emojiCategories) { index, category ->
                                        val isRecent = index == 0
                                        val samePlugin = category.isPlugin &&
                                            emojiCategories.count { it.isPlugin && it.pluginId == category.pluginId } > 1
                                        val label = when {
                                            isRecent -> "最近"
                                            category.isPlugin -> if (samePlugin) category.name else category.pluginIcon?.text ?: category.icon
                                            else -> category.icon
                                        }
                                        val asset = category.pluginIcon?.assetName.takeIf { category.isPlugin && !samePlugin }
                                        val isSelected = index == emojiSelectedTab
                                        if (asset != null) {
                                            Box(
                                                modifier = Modifier
                                                    .width(cellWidth)
                                                    .fillMaxHeight()
                                                    .testTag("emoji-category:$index")
                                                    .background(if (isSelected) textColor.copy(alpha = 0.12f) else Color.Transparent)
                                                    .tolerantClick(showRipple = false) {
                                                        onHapticFeedback?.invoke()
                                                        emojiSelectedTab = index
                                                        scope.launch { emojiPager.scrollToPage(index) }
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                AsyncImage(
                                                    asset, category.name,
                                                    Modifier.size(22.dp),
                                                    contentScale = ContentScale.Fit,
                                                )
                                                if (isSelected) Box(
                                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(.55f)
                                                        .height(2.dp).background(textColor)
                                                )
                                            }
                                        } else {
                                            SymbolCategoryTab(
                                                name = label,
                                                isSelected = isSelected,
                                                onClick = {
                                                    onHapticFeedback?.invoke()
                                                    emojiSelectedTab = index
                                                    scope.launch { emojiPager.scrollToPage(index) }
                                                },
                                                backgroundColor = Color.Transparent,
                                                textColor = textColor,
                                                selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                                                fontSize = when {
                                                    isRecent -> 15.sp
                                                    category.isPlugin && samePlugin -> 14.sp
                                                    else -> 18.sp
                                                },
                                                description = category.name,
                                                modifier = Modifier
                                                    .widthIn(min = cellWidth)
                                                    .fillMaxHeight()
                                                    .padding(horizontal = if (category.isPlugin && samePlugin) 6.dp else 0.dp)
                                                    .testTag(if (isRecent) "emoji-recent" else "emoji-category:$index"),
                                            )
                                        }
                                    }
                                }
                            }
                            ExpressionSection.KAOMOJI -> {
                                LazyRow(
                                    state = kaomojiTabList,
                                    modifier = Modifier.fillMaxSize().testTag("kaomoji-categories"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    item(key = "kaomoji-recent") {
                                        SymbolCategoryTab(
                                            name = "最近",
                                            isSelected = kaomojiSelectedTab == 0,
                                            onClick = {
                                                onHapticFeedback?.invoke()
                                                kaomojiSelectedTab = 0
                                                scope.launch { kaomojiPager.scrollToPage(0) }
                                            },
                                            backgroundColor = Color.Transparent,
                                            textColor = textColor,
                                            selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                                            fontSize = 15.sp,
                                            modifier = Modifier
                                                .width(cellWidth)
                                                .fillMaxHeight()
                                                .testTag("kaomoji-recent"),
                                        )
                                    }
                                    itemsIndexed(kaomojiCategories) { index, item ->
                                        val pageIndex = index + 1
                                        SymbolCategoryTab(
                                            name = item.name,
                                            isSelected = pageIndex == kaomojiSelectedTab,
                                            onClick = {
                                                onHapticFeedback?.invoke()
                                                kaomojiSelectedTab = pageIndex
                                                scope.launch { kaomojiPager.scrollToPage(pageIndex) }
                                            },
                                            backgroundColor = Color.Transparent,
                                            textColor = textColor,
                                            selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                                            fontSize = 15.sp,
                                            modifier = Modifier
                                                .width(cellWidth)
                                                .fillMaxHeight()
                                                .testTag("kaomoji-category:$index"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 右侧竖排动作列（参考数字输入界面右侧排布）：
                // 顶部删除（带「清空」角标）、中间两格放「Emoji / 颜文字」选框、最下回车。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("emoji-actions")
                ) {
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = {
                            if (inKaomoji) pickedKaomoji.clear()
                            onEmojiSelect("delete")
                        },
                        onLongClick = {
                            if (inKaomoji && onDeleteFace != null) {
                                val last = pickedKaomoji.lastOrNull()
                                val removed = onDeleteFace(last)
                                if (last != null && removed == last) pickedKaomoji.removeLast() else pickedKaomoji.clear()
                            } else {
                                onEmojiSelect("delete")
                            }
                        },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        swipeText = "清空",
                        onSwipe = { onEmojiSelect("clear_composition") },
                        distanceDelete = true,
                        followSymbolSwipeDirection = true,
                        upFeedback = KeyFlickFeedback.DELETE,
                        swipeUpLabel = "上滑清空",
                        downFeedback = KeyFlickFeedback.UNDO,
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onEmojiSelect("clear_all") },
                        onSwipeDown = { onEmojiSelect("undo_clear") },
                        onSwipeStateChange = ::processSwipeState,
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("expression-delete")
                            .semantics { contentDescription = "删除" },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                    // 中间两格：Emoji / 颜文字 竖排选框，与符号键盘标签栏同款，各占 1 格高度。
                    Column(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxWidth()
                            .padding(scaledKeyVisualPadding())
                            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                            .background(categoryColor)
                            .testTag("expression-mode-rail"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ExpressionSection.entries.forEach { item ->
                            SymbolCategoryTab(
                                name = item.label,
                                isSelected = section == item,
                                onClick = {
                                    onHapticFeedback?.invoke()
                                    section = item
                                },
                                backgroundColor = Color.Transparent,
                                textColor = textColor,
                                selectedBackgroundColor = textColor.copy(alpha = 0.12f),
                                fontSize = 14.sp,
                                showIndicator = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag(item.tag),
                            )
                        }
                    }

                    ActionKeyButton(
                        text = enterKeyText,
                        onClick = { onEmojiSelect("enter") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).testTag("expression-enter"),
                        onPress = { onKeyPressDown?.invoke("enter") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
            }
        }

        tonePicker?.let { picker ->
            SkinTonePickerOverlay(
                picker = picker,
                onPick = { emoji ->
                    tonePicker = null
                    recordRecentEmoji(emoji)
                    onEmojiSelect(emoji)
                },
                onDismiss = { tonePicker = null },
            )
        }
    }
    }
    }
}

@Composable
private fun EmojiCategoryPage(
    category: EmojiCategory,
    backgroundColor: Color,
    textColor: Color,
    clipboardManager: ClipboardManager,
    panelCoords: Array<LayoutCoordinates?>,
    onEmojiPick: (String) -> Unit,
    onPluginTextPick: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)?,
    onOpenTonePicker: (SkinTonePicker) -> Unit,
) {
    val context = LocalContext.current
    val pluginItems = category.emojiItems
    if (category.isPlugin && pluginItems != null) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            val grid = emojiGridGeometry(maxWidth.value, maxHeight.value)
            val hasImages = pluginItems.any { it.imageUrl != null }
            val columns = if (category.layoutColumns > 0) category.layoutColumns
                else if (hasImages) (maxWidth.value / 64f).toInt().coerceAtLeast(1) else grid.columns
            val itemHeight = if (category.layoutItemHeightDp > 0) category.layoutItemHeightDp
                else if (hasImages) 60 else 40
            val rows = remember(pluginItems, columns) { pluginItems.chunked(columns) }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (hasImages) 6.dp else 2.dp)) {
                items(rows, key = { it.firstOrNull()?.id ?: it.hashCode() }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { item ->
                            PluginEmojiButton(item, onClick = {
                                val imageUrl = item.imageUrl
                                when {
                                    imageUrl != null && onImageEmojiSelect != null -> onImageEmojiSelect(imageUrl)
                                    imageUrl != null -> {
                                        val success = clipboardManager.copyImageToSystemClipboard(imageUrl, item.text)
                                        Toast.makeText(context, if (success) "已复制表情，可粘贴发送" else "复制失败", Toast.LENGTH_SHORT).show()
                                    }
                                    else -> onPluginTextPick(item.insertText ?: item.text)
                                }
                            }, defaultHeightDp = itemHeight, backgroundColor = backgroundColor,
                                textColor = textColor, modifier = Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    } else if (category.emojis.isEmpty()) {
        PanelEmptyHint("暂无表情记录", textColor, Modifier.fillMaxSize())
    } else {
        EmojiGridContent(category.emojis, panelCoords, onEmojiPick, onOpenTonePicker)
    }
}

@Composable
private fun EmojiGridContent(
    emojis: List<String>,
    panelCoords: Array<LayoutCoordinates?>,
    onPick: (String) -> Unit,
    onOpenTonePicker: (SkinTonePicker) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        val grid = emojiGridGeometry(maxWidth.value, maxHeight.value)
        val rows = remember(emojis, grid.columns) { emojis.chunked(grid.columns) }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { emoji ->
                        val tones = remember(emoji) { EmojiSkinTone.variants(emoji) }
                        EmojiButton(
                            emoji = emoji,
                            onClick = { onPick(emoji) },
                            modifier = Modifier.weight(1f).height(grid.rowHeightDp.dp),
                            cellHeightDp = grid.rowHeightDp,
                            onLongClick = tones.takeIf { it.isNotEmpty() }?.let {
                                { cell: LayoutCoordinates ->
                                    val panel = panelCoords[0]
                                    if (panel != null && panel.isAttached && cell.isAttached) {
                                        onOpenTonePicker(SkinTonePicker(tones, panel.localBoundingBoxOf(cell, false)))
                                    }
                                }
                            },
                        )
                    }
                    repeat(grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class SkinTonePicker(val variants: List<String>, val anchor: Rect)

/** 肤色条：盖在表情面板上，点条外任何地方只关闭，不会顺手打出下面的表情。 */
@Composable
private fun SkinTonePickerOverlay(picker: SkinTonePicker, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = keyboardCardColors()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardPadding = 4.dp
        val fitWidth = (maxWidth - cardPadding * 2) / picker.variants.size
        val itemSize = with(density) { picker.anchor.height.toDp() }.coerceIn(40.dp, 56.dp).coerceAtMost(fitWidth)
        val anchor = picker.anchor
        Layout(content = {
            Box(Modifier.fillMaxSize().testTag("emoji-skin-tone-dismiss")
                .pointerInput(Unit) { detectTapGestures { onDismiss() } })
            Row(Modifier.shadow(CompactMenuStyle.Elevation, CompactMenuStyle.Shape)
                .clip(CompactMenuStyle.Shape).background(colors.card)
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag("emoji-skin-tones").padding(cardPadding)) {
                picker.variants.forEachIndexed { index, variant ->
                    Box(Modifier.size(itemSize).clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(variant) }.testTag("emoji-skin-tone:$index"),
                        contentAlignment = Alignment.Center) {
                        Text(variant, fontSize = (itemSize.value * 0.55f).coerceIn(20f, 32f).sp,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val scrim = measurables[0].measure(Constraints.fixed(width, height))
            val card = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = 4.dp.roundToPx()
            val x = (anchor.center.x - card.width / 2f).roundToInt()
                .coerceIn(0, (width - card.width).coerceAtLeast(0))
            val above = anchor.top - gap - card.height
            val y = (if (above >= 0f) above else anchor.bottom + gap).roundToInt()
                .coerceIn(0, (height - card.height).coerceAtLeast(0))
            layout(width, height) {
                scrim.place(0, 0)
                card.place(x, y)
            }
        }
    }
}

@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeightDp: Float? = null,
    /** 非空时长按回调，带上格子坐标（肤色条据此定位）。 */
    onLongClick: ((LayoutCoordinates) -> Unit)? = null,
) {
    val cell = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier = modifier
            .then(if (cellHeightDp == null) Modifier.aspectRatio(1f) else Modifier)
            .then(if (onLongClick != null) Modifier.onPlaced { cell[0] = it } else Modifier)
            .clip(RoundedCornerShape(4.dp))
            .tolerantClick(onLongClick = onLongClick?.let { callback -> { cell[0]?.let(callback) } },
                onClick = onClick)
            .keyGlow(Modifier.clip(RoundedCornerShape(4.dp)), liquid = false),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = (cellHeightDp?.let { (it * 0.45f).coerceIn(22f, 36f) } ?: 22f).sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PluginEmojiButton(
    emojiItem: PluginResultItem,
    onClick: () -> Unit,
    defaultHeightDp: Int = 40,
    backgroundColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLightTheme =
        (backgroundColor.red + backgroundColor.green + backgroundColor.blue) / 3f > 0.5f
    val buttonBackgroundColor = if (isLightTheme) Color.White.copy(alpha = 0.8f)
    else Color.LightGray.copy(alpha = 0.15f)
    val contentColor = if (isLightTheme) Color.Black else textColor

    Box(
        modifier = modifier
            .height(defaultHeightDp.dp)
            .then(
                if (emojiItem.imageUrl != null) Modifier.aspectRatio(1f)
                else Modifier.fillMaxWidth()
            )
            .clip(RoundedCornerShape(4.dp))
            .background(buttonBackgroundColor)
            .tolerantClick(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (emojiItem.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(emojiItem.imageUrl)
                    // 高频网格滚动场景：关闭渐显动画，降低加载突发期的重绘压力
                    .crossfade(false)
                    .build(),
                contentDescription = emojiItem.text,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = emojiItem.text,
                fontSize = 12.sp,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
