package com.kingzcheung.xime.ui.menubar

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.kingzcheung.xime.ui.common.CompactActionMenu
import com.kingzcheung.xime.ui.common.CompactActionMenuEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.ui.settings.idleBorderAlpha
import com.kingzcheung.xime.ui.settings.isNearBlack
import com.kingzcheung.xime.ui.theme.liquidGlassEdge
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.*
import com.kingzcheung.xime.ui.keyboard.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class ClipboardMenuAnchor(val bounds: Rect = Rect.Zero, val occupied: List<Rect> = emptyList(),
    val preferSide: Boolean = true, val corner: Dp = 8.dp)

/** Corner of a record card; the long-press highlight uses the same one so its outline matches the card. */
internal fun clipboardCardCorner(oneLine: Boolean): Dp = if (oneLine) 10.dp else 12.dp
private data class ClipboardItemMenuAnchor(val card: ClipboardCard, val position: ClipboardMenuAnchor)

@Composable
internal fun ConnectedClipboardBoard(
    textItems: List<ClipboardItem>, initialImages: Boolean, expanded: Boolean,
    onBack: () -> Unit, onQuickSend: () -> Unit, onSelectText: (String) -> Unit,
    onRemoveText: (List<Long>) -> Unit, onAddQuick: (Long) -> Unit, onSplit: (String, Long) -> Unit,
    onImageSelect: (ClipboardImage) -> Unit, onImageShare: (ClipboardImage, String?) -> Unit,
    onSystemPaste: () -> Unit, onPullRemote: (() -> Unit)?, modifier: Modifier = Modifier,
    onAddPinned: (() -> Unit)? = null, onEditText: ((Long, String, String) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    /** 长按菜单的「翻译」：交给 AI 改写的翻译；null（没装或没开 AI 改写）不显示这一项。 */
    onTranslateText: ((String) -> Unit)? = null,
    /** The search view above the keyboard hands its query on to the board when Enter is pressed. */
    keepQueryOnDispose: Boolean = false,
    /** The keyboard search field, typed live (composition included); null when the board is not searching from it. */
    liveQuery: ClipboardLiveQuery? = null,
    /** False in a short search results area (phone landscape): no category row, records only. */
    showControls: Boolean = true,
    initialFavorites: Boolean = false,
) {
    val context = LocalContext.current
    val store = remember(context) { ClipboardImages.getInstance(context) }
    val prefs = remember(context) { context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE) }
    // Image items and their source mode must reach Compose atomically.
    val imageBoard by store.boardSnapshot.collectAsState()
    val images = imageBoard.images
    val galleryPhotosEnabled = imageBoard.galleryPhotosEnabled
    val photoAccess = imageBoard.screenshotsEnabled
    val failure by store.pasteFailure.collectAsState()
    val screenshotsNeedAccess by store.screenshotsNeedAccess.collectAsState()
    var storedPins by remember { mutableStateOf(prefs.getStringSet("pins", emptySet()).orEmpty().toSet()) }
    var pendingTextPins by remember { mutableStateOf(emptyMap<Long, Boolean>()) }
    var pendingPinJobs by remember { mutableStateOf(emptyMap<Long, Job>()) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refresh() }
    LaunchedEffect(Unit) { store.refresh() }
    val manager = remember(context) { ClipboardManager.getInstance(context) }
    // 短信里点链接、电话「复制」时键盘可能一直没收起，监听又没读到：打开面板时补读那一条（用户 11-03）。
    LaunchedEffect(manager) { runCatching { manager.readMissedCopy(boardOpened = true) } }
    val favoriteStore = remember(context) { ClipboardFavoriteStore.getInstance(context) }
    val favoriteCards by favoriteStore.items.collectAsState()
    val favoritePins by favoriteStore.pinnedKeys.collectAsState()
    var favoritesMode by remember(initialImages, initialFavorites, showControls, liveQuery) {
        mutableStateOf(
            when {
                liveQuery != null -> favoriteStore.isSearchInFavorites()
                showControls -> initialFavorites || favoriteStore.consumeReturnToFavorites()
                else -> false
            }
        )
    }
    val unpinnedTotal by manager.unpinnedCount.collectAsState()
    val room by manager.room.collectAsState()
    // 手动开的无痕（不含密码框、剪贴板搜索自动生效的那种）：面板顶上说明新复制的为什么没进来。
    val incognito = com.kingzcheung.xime.ui.keyboard.rememberQuickToggleStates().incognito
    // Older history is read page by page while open; closing returns to the first page.
    DisposableEffect(manager) { onDispose { manager.resetHistoryWindow() } }
    val cardMaxLines = remember(prefs) { clipboardCardMaxLines(prefs.getInt(CLIPBOARD_CARD_MAX_LINES_KEY, 4)) }
    val cardTextPercent = remember(prefs) { clipboardCardTextPercent(prefs.getInt(CLIPBOARD_CARD_TEXT_SIZE_KEY, 100)) }
    val imageHeight = remember(prefs) { clipboardImageHeight(prefs.getString(CLIPBOARD_IMAGE_HEIGHT_KEY, null)) }
    val recentImageCount = remember(prefs) { clipboardRecentImageCount(prefs.getInt(CLIPBOARD_RECENT_IMAGE_COUNT_KEY, 4)) }
    // 平铺 / 列表，顶栏按钮切换，记住上次选的（用户 11-14）。
    var listLayout by remember(prefs) { mutableStateOf(prefs.getBoolean(CLIPBOARD_LIST_LAYOUT_KEY, false)) }
    var closeAfterPaste by remember(prefs) { mutableStateOf(clipboardClosesAfterPaste(prefs)) }
    // A search submitted from the keyboard search bar shows only its matches (fixed records too).
    val boardQuery by manager.boardQuery.collectAsState()
    var searchResults by remember { mutableStateOf<List<ClipboardItem>?>(null) }
    LaunchedEffect(boardQuery, liveQuery, textItems) {
        if (liveQuery != null) {
            if (!liveQuery.isBlank) kotlinx.coroutines.delay(120) // Typing a word does not query per key.
            searchResults = if (liveQuery.isBlank) null else manager.search(liveQuery)
            return@LaunchedEffect
        }
        if (boardQuery.isNotBlank()) kotlinx.coroutines.delay(120) // Typing a word does not query per key.
        searchResults = if (boardQuery.isBlank()) null else manager.search(boardQuery)
    }
    DisposableEffect(manager) { onDispose { if (!keepQueryOnDispose) manager.boardQuery.value = "" } }
    val shownText = searchResults ?: textItems
    val shownFavorites = remember(favoriteCards, liveQuery, boardQuery) {
        when {
            liveQuery != null -> if (liveQuery.isBlank) favoriteCards else favoriteStore.search(liveQuery)
            boardQuery.isNotBlank() -> favoriteStore.search(ClipboardLiveQuery(boardQuery))
            else -> favoriteCards
        }
    }
    val pins = remember(shownText, storedPins, pendingTextPins) {
        clipboardPinnedKeys(shownText, storedPins, pendingTextPins)
    }
    LaunchedEffect(textItems, pendingPinJobs) {
        val inFlight = pendingTextPins.filterKeys { it in pendingPinJobs }
        val completed = pendingTextPins.filterKeys { it !in pendingPinJobs }
        pendingTextPins = inFlight + pendingClipboardTextPins(textItems, completed)
    }
    ClipboardBoardView(shownText, if (searchResults != null) emptyList() else images, initialImages, expanded, pins, photoAccess, failure,
        onBack, onQuickSend, onSelectText, onImageSelect, onSplit, onAddQuick,
        onPinsChange = { updated ->
            val changedTextPins = ((pins - updated) + (updated - pins)).mapNotNull { key ->
                key.takeIf { it.startsWith("text:") }?.removePrefix("text:")?.toLongOrNull()
                    ?.let { id -> id to (key in updated) }
            }.toMap()
            pendingTextPins = pendingTextPins + changedTextPins
            storedPins = updated.filterTo(mutableSetOf()) { it.startsWith("image:") }
            synchronized(prefs) {
                val unmigratedTextPins = prefs.getStringSet("pins", emptySet()).orEmpty()
                    .filterTo(mutableSetOf()) { it.startsWith("text:") }
                prefs.edit().putStringSet("pins", storedPins + unmigratedTextPins).apply()
            }
            val manager = ClipboardManager.getInstance(context)
            changedTextPins.forEach { (id, pinned) ->
                val job = manager.setClipboardPinned(id, pinned)
                pendingPinJobs = pendingPinJobs + (id to job)
                scope.launch {
                    job.join()
                    if (pendingPinJobs[id] === job) {
                        if (job.isCancelled) {
                            pendingTextPins = pendingTextPins - id
                            Toast.makeText(context, "无法保存固定状态，请重试", Toast.LENGTH_SHORT).show()
                        }
                        pendingPinJobs = pendingPinJobs - id
                    }
                }
            }
        },
        onRemove = { cards ->
            onRemoveText(cards.filterIsInstance<ClipboardCard.Text>().map { it.item.id })
            store.removeImages(cards.filterIsInstance<ClipboardCard.Image>().map { it.item.uri }.toSet())
        },
        onPhotoAccess = {
            if (store.recentScreenshotsEnabled()) store.enableRecentScreenshots(false)
            else context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onPick = { context.startActivity(Intent(context, ImageAccessActivity::class.java)
            .putExtra("pick_image", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onSystemPaste = onSystemPaste, onShare = onImageShare, onPullRemote = onPullRemote, modifier = modifier,
        onAddPinned = onAddPinned, onEditText = onEditText, onDismissFailure = store::clearPasteFailure,
        onTranslateText = onTranslateText,
        unpinnedTextTotal = unpinnedTotal, onLoadMore = { if (searchResults == null) manager.loadMoreHistory() },
        onClearUnpinned = { images ->
            // Clearing means all unfixed history in the database, not only the pages read so far.
            manager.clearAll()
            store.removeImages(images.filterIsInstance<ClipboardCard.Image>().map { it.item.uri }.toSet())
        }, cardMaxLines = cardMaxLines, cardTextPercent = cardTextPercent, imageHeight = imageHeight, recentImageCount = recentImageCount,
        listLayout = listLayout,
        onListLayoutChange = { value ->
            listLayout = value
            prefs.edit().putBoolean(CLIPBOARD_LIST_LAYOUT_KEY, value).apply()
        },
        searchQuery = (liveQuery?.display ?: boardQuery).takeIf { it.isNotBlank() },
        onClearSearch = {
            manager.boardQuery.value = ""
            favoriteStore.setSearchInFavorites(false)
        }, searchChip = liveQuery == null,
        onOpenSearch = onOpenSearch?.let { open ->
            {
                favoriteStore.setSearchInFavorites(favoritesMode)
                favoriteStore.setReturnToFavorites(favoritesMode)
                open()
            }
        }, full = room.full, showControls = showControls,
        incognito = incognito && showControls,
        onIncognitoOff = { com.kingzcheung.xime.settings.SettingsPreferences.setIncognito(context, false) },
        screenshotAccess = screenshotsNeedAccess && showControls,
        onAllowScreenshots = { context.startActivity(Intent(context, ImageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onHideScreenshots = { store.enableRecentScreenshots(false) },
        closeAfterPaste = closeAfterPaste, onCloseAfterPasteChange = { value ->
            closeAfterPaste = value
            prefs.edit().putBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, value).apply()
        },
        // Source changes must not reuse grid animation state from the text-only board.
        galleryPhotosEnabled = galleryPhotosEnabled,
        onOpenSettings = {
            context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context,
                com.kingzcheung.xime.ui.settings.SettingsRoutes.ClipboardPanel))
        },
        onFormOpen = { _, _ ->
            favoriteStore.setReturnToFavorites(favoritesMode)
        },
        favoriteItems = shownFavorites,
        favoritePins = favoritePins,
        favoritesMode = favoritesMode,
        onToggleFavoritesMode = {
            val next = !favoritesMode
            favoritesMode = next
            if (boardQuery.isNotBlank()) favoriteStore.setSearchInFavorites(next)
        },
        onFavoritePinsChange = favoriteStore::setPinnedKeys,
        isFavorited = favoriteStore::isFavorited,
        onToggleFavorite = { card ->
            if (favoriteStore.isFavorited(card)) favoriteStore.removeMatching(card)
            else favoriteStore.addCard(card)
        },
        onAddFavorite = onAddPinned?.let { openForm ->
            {
                favoriteStore.beginAdd(returnToFavorites = favoritesMode)
                openForm()
            }
        },
        onEditFavoriteText = onEditText?.let { openEdit ->
            { id, text, code ->
                favoriteStore.beginEditText(id, returnToFavorites = true)
                openEdit(id, text, code)
            }
        },
        onRemoveFavorites = favoriteStore::removeCards)
}

/** Closes the keyboard this board is in (it runs inside the input method service). */
internal fun hideKeyboardFrom(context: Context) {
    generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.inputmethodservice.InputMethodService>().firstOrNull()?.requestHideSelf(0)
}

/** Preference (board prefs) for how many lines a history card shows: 2, 4 or 8. */
internal const val CLIPBOARD_CARD_MAX_LINES_KEY = "card_max_lines"

/**
 * 多选时每一栏自己的全选（用户 11-11：圈圈放到分栏那里）：固定栏的全选只选固定的，下面那栏只选没固定的，
 * 互不影响。[fixed] 选哪一栏。
 */
internal fun clipboardSectionKeys(cards: List<ClipboardCard>, pins: Set<String>, fixed: Boolean): Set<String> =
    cards.filter { (it.key in pins) == fixed }.mapTo(mutableSetOf()) { it.key }

/** 点一栏的全选圈：这栏没全选上就补齐，已全选就只取消这一栏，另一栏选了的留着。 */
internal fun toggleClipboardSection(selected: Set<String>, section: Set<String>): Set<String> =
    if (section.isNotEmpty() && selected.containsAll(section)) selected - section else selected + section

internal fun clipboardCardMaxLines(stored: Int): Int = if (stored in CLIPBOARD_CARD_LINE_CHOICES) stored else 4

/** 剪贴板卡片字号（#497，默认 100 = 和现在一样）：给老人看的放大几档，只放大卡片里的字。 */
internal const val CLIPBOARD_CARD_TEXT_SIZE_KEY = "card_text_percent"
internal val CLIPBOARD_CARD_TEXT_SIZE_CHOICES = listOf(100, 115, 130, 150)

internal fun clipboardCardTextPercent(stored: Int): Int = if (stored in CLIPBOARD_CARD_TEXT_SIZE_CHOICES) stored else 100

/** 卡片显示行数能选的几档；1 行是 #300 要的像搜狗那样一条一行（默认仍是 4 行）。 */
internal val CLIPBOARD_CARD_LINE_CHOICES = listOf(1, 2, 4, 8)

/**
 * Preference (board prefs): close the clipboard after a text record is put into the text. On by
 * default, as before; off keeps the clipboard (or the search's matches) open to paste several.
 */
internal const val CLIPBOARD_CLOSE_AFTER_PASTE_KEY = "close_after_paste"

/**
 * 卡片上只画得下几行，交给 Text 的只取开头一段。整段长文（几万字的复制）每次排版都要量完整段，
 * 主线程一量几秒，按键排队超时（#536 ColorOS 17 ANR，栈在剪贴板瀑布流测量文字）。点了照样用完整内容。
 */
internal fun clipboardPreviewText(text: String, limit: Int = CLIPBOARD_PREVIEW_CHARS): String {
    if (text.length <= limit) return text
    val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    return text.substring(0, end) + "…"
}

internal const val CLIPBOARD_PREVIEW_CHARS = 400

internal fun clipboardClosesAfterPaste(prefs: android.content.SharedPreferences): Boolean =
    prefs.getBoolean(CLIPBOARD_CLOSE_AFTER_PASTE_KEY, true)

internal fun clipboardClosesAfterPaste(context: Context): Boolean =
    clipboardClosesAfterPaste(context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE))

/** 「粘贴后移到最前」（#299）：点一条历史记录上屏后，这条排回最前面。默认关，顺序照复制时间。 */
internal const val CLIPBOARD_PASTE_TO_TOP_KEY = "paste_to_top"

internal fun clipboardMovesPastedToTop(context: Context): Boolean =
    context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE).getBoolean(CLIPBOARD_PASTE_TO_TOP_KEY, false)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClipboardBoardView(
    textItems: List<ClipboardItem>, images: List<ClipboardImage>, initialImages: Boolean, expanded: Boolean,
    pins: Set<String>, photoAccess: Boolean, failure: ImagePasteFailure?,
    onBack: () -> Unit, onQuickSend: () -> Unit, onSelectText: (String) -> Unit,
    onSelectImage: (ClipboardImage) -> Unit, onSplit: (String, Long) -> Unit, onAddQuick: (Long) -> Unit,
    onPinsChange: (Set<String>) -> Unit, onRemove: (List<ClipboardCard>) -> Unit,
    onPhotoAccess: () -> Unit, onPick: () -> Unit, onSystemPaste: () -> Unit,
    onShare: (ClipboardImage, String?) -> Unit, onPullRemote: (() -> Unit)?, modifier: Modifier = Modifier,
    onAddPinned: (() -> Unit)? = null, onEditText: ((Long, String, String) -> Unit)? = null,
    onDismissFailure: () -> Unit = {},
    /** 长按菜单的「翻译」；null 不显示。密码记录没有这一项。 */
    onTranslateText: ((String) -> Unit)? = null,
    /** Unfixed text records in the whole history; -1 when only [textItems] is known. */
    unpinnedTextTotal: Int = -1,
    onLoadMore: () -> Unit = {},
    /** Clears all unfixed history; receives the unfixed images to remove. Null deletes [textItems] only. */
    onClearUnpinned: ((List<ClipboardCard>) -> Unit)? = null,
    cardMaxLines: Int = 4,
    /** 记录卡片的字号，100 = 跟键盘一样（#497）。只放大卡片里的字，顶栏、分栏不变。 */
    cardTextPercent: Int = 100,
    imageHeight: ClipboardImageHeight = ClipboardImageHeight.STANDARD,
    /** 「全部」shows only this many of the newest unfixed pictures; 0 shows them all. */
    recentImageCount: Int = 0,
    /** 列表: one record per row, recent pictures in one sideways row; false is the 瀑布流平铺 of several columns. */
    listLayout: Boolean = false,
    /** Switches between 平铺 and 列表 from the button in the top row; null hides the button. */
    onListLayoutChange: ((Boolean) -> Unit)? = null,
    /** The search shown in the board, or null; its chip clears it. */
    searchQuery: String? = null,
    onClearSearch: () -> Unit = {},
    onOpenSearch: (() -> Unit)? = null,
    /** False while the keyboard search field above shows the query: no second copy of it here. */
    searchChip: Boolean = true,
    /** History has reached its capacity: new copies are not being saved. */
    full: Boolean = false,
    /** The manual incognito switch is on: new copies are not being saved; existing records still work. */
    incognito: Boolean = false,
    onIncognitoOff: () -> Unit = {},
    /** 最近截图开着但没有相册权限：「全部」「图片」顶上一行「允许」。 */
    screenshotAccess: Boolean = false,
    onAllowScreenshots: () -> Unit = {},
    onHideScreenshots: () -> Unit = {},
    /** False hides the category / actions row (a short search results area). */
    showControls: Boolean = true,
    /** The "close after paste" switch shown in the more menu; null hides it. */
    closeAfterPaste: Boolean? = null,
    onCloseAfterPasteChange: (Boolean) -> Unit = {},
    /** Tab key to open on instead of 全部 / 图片 (back from the add / edit form). */
    initialTabKey: String? = null,
    /** Opens Settings › 剪贴板面板 (the switches of this menu and the board's other settings); null hides it. */
    onOpenSettings: (() -> Unit)? = null,
    /** The add (true) or edit form is about to open from this tab. */
    onFormOpen: (ClipboardTab, Boolean) -> Unit = { _, _ -> },
    /** Whether recent gallery photos are included in the screenshot feed. */
    galleryPhotosEnabled: Boolean = false,
    favoriteItems: List<ClipboardCard> = emptyList(),
    favoritePins: Set<String> = emptySet(),
    favoritesMode: Boolean = false,
    onToggleFavoritesMode: (() -> Unit)? = null,
    onFavoritePinsChange: ((Set<String>) -> Unit)? = null,
    isFavorited: (ClipboardCard) -> Boolean = { false },
    onToggleFavorite: ((ClipboardCard) -> Unit)? = null,
    onAddFavorite: (() -> Unit)? = null,
    onEditFavoriteText: ((Long, String, String) -> Unit)? = null,
    onRemoveFavorites: ((List<ClipboardCard>) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val appContext = LocalContext.current.applicationContext
    val tabs = remember { clipboardTabs() }
    var chosenTab by remember(initialImages) { mutableStateOf<ClipboardTab>(
        tabs.firstOrNull { it.key == initialTabKey }
            ?: ClipboardTab.Category(if (initialImages) ClipboardFilter.IMAGE else ClipboardFilter.ALL)) }
    val tab = tabs.resolve(chosenTab)
    var menu by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(ClipboardMenuAnchor()) }
    var deleteAnchor by remember { mutableStateOf(ClipboardMenuAnchor()) }
    val boardCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val recordsCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val moreBounds = remember { arrayOf(Rect.Zero) }
    val deleteBounds = remember { arrayOf(Rect.Zero) }
    // Each record's drawn bounds, so the long-press highlight is exactly the card (grid cell offsets
    // leave out the grid's content padding).
    val cardBounds = remember { mutableMapOf<String, Rect>() }
    // Deleted records leave at once. Unpinning them first would move a pin into recent history before
    // the delete lands, and the grid would scroll after it.
    var removing by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    // Kept passwords the user chose to see (#95); hidden again when the board closes.
    var revealed by remember { mutableStateOf(emptySet<String>()) }
    var pinsExpanded by remember(initialImages) { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<ClipboardItemMenuAnchor?>(null) }
    // Physical-keyboard focus inside an open record menu or confirmation, and its entries.
    var menuFocus by remember { mutableStateOf(0) }
    val keyboardMenuEntries = remember { arrayOf<List<com.kingzcheung.xime.ui.common.CompactActionMenuEntry>>(emptyList()) }
    var deleting by remember { mutableStateOf<List<ClipboardCard>?>(null) }
    // True while the delete confirmation is the "clear all unfixed" one.
    var clearingAll by remember { mutableStateOf(false) }
    val activePins = if (favoritesMode) favoritePins else pins
    val activeOnPinsChange: (Set<String>) -> Unit = if (favoritesMode) {
        { updated -> onFavoritePinsChange?.invoke(updated) }
    } else onPinsChange
    val normalAll = remember(textItems, images, pins, removing) {
        clipboardCards(textItems, images, ClipboardFilter.ALL, pins).filterNot { it.key in removing }
    }
    val favoritesAll = remember(favoriteItems, favoritePins, removing) {
        favoriteItems.filterNot { it.key in removing }
            .sortedWith(compareByDescending<ClipboardCard> { it.key in favoritePins }.thenByDescending { it.timestamp })
    }
    val all = if (favoritesMode) favoritesAll else normalAll
    LaunchedEffect(textItems, images, favoriteItems, favoritesMode) {
        if (removing.isNotEmpty()) {
            val pool = if (favoritesMode) favoriteItems else clipboardCards(textItems, images, ClipboardFilter.ALL, pins)
            removing = removing.intersect(pool.mapTo(mutableSetOf()) { it.key })
        }
    }
    val cards = remember(all, tab, activePins, recentImageCount, favoritesMode) {
        val currentFilter = (tab as? ClipboardTab.Category)?.filter ?: ClipboardFilter.ALL
        all.filter { clipboardMatches(it, currentFilter) }.let { shown ->
            if (!favoritesMode && currentFilter == ClipboardFilter.ALL) withRecentImageCount(shown, activePins, recentImageCount)
            else shown
        }
    }
    val keyboard = LocalClipboardKeyboardNavigation.current
    var focusedKey by remember(keyboard?.active, tab.key, favoritesMode) { mutableStateOf<String?>(null) }
    // Switching categories or photo feeds must discard outgoing lazy item animations.
    // Keep the grid alive for ordinary history updates and in-category pin folding.
    val gridState = key(tab.key, searchQuery, listLayout, photoAccess, galleryPhotosEnabled, favoritesMode) {
        rememberLazyStaggeredGridState()
    }
    // Near the end of what is loaded, read the next page of older history (again after each page
    // arrives, so a sparse category keeps filling until the whole history is read).
    val nearEnd by remember(gridState) { derivedStateOf {
        val info = gridState.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 30
    } }
    LaunchedEffect(gridState, nearEnd, textItems.size) { if (nearEnd) onLoadMore() }
    val scope = rememberCoroutineScope()
    fun menuPosition(anchorInRoot: Rect, preferSide: Boolean): ClipboardMenuAnchor {
        val boardOrigin = boardCoordinates[0]?.boundsInRoot()?.topLeft ?: Offset.Zero
        val records = recordsCoordinates[0]?.boundsInRoot() ?: Rect.Zero
        val visible = gridState.layoutInfo.visibleItemsInfo.mapNotNull { cell ->
            if (all.none { it.key == cell.key }) return@mapNotNull null
            val rect = Rect(records.left + cell.offset.x, records.top + cell.offset.y,
                records.left + cell.offset.x + cell.size.width, records.top + cell.offset.y + cell.size.height)
                .intersect(records)
            rect.takeIf { it.width > 0 && it.height > 0 }?.translate(-boardOrigin)
        }
        return ClipboardMenuAnchor(anchorInRoot.translate(-boardOrigin), visible, preferSide)
    }
    LaunchedEffect(tab.key) { gridState.scrollToItem(0) }
    fun changePinsExpansion(value: Boolean) {
        scope.launch {
            // Reset the scroll anchor before changing the keyed rows. A scrollToItem
            // after removal would reset the lazy item animator and discard fade-out.
            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                gridState.scrollToItem(0)
            }
            pinsExpanded = value
        }
    }
    LaunchedEffect(cards) {
        selected = selected.intersect(cards.mapTo(mutableSetOf()) { it.key })
    }
    LaunchedEffect(all, tab.key, pinsExpanded, failure, favoritesMode) {
        // Anchors capture the visible grid at opening time. A background copy/update
        // can move a surviving record too; do not keep an obsolete source rectangle.
        menu = false; itemMenu = null; deleting = null
    }
    val addPinned = onAddPinned?.let { add -> { onFormOpen(tab, true); add() } }
    val editText = onEditText?.let { edit -> { id: Long, text: String, code: String -> onFormOpen(tab, false); edit(id, text, code) } }
    val editFavoriteText = onEditFavoriteText ?: editText
    DisposableEffect(keyboard) { onDispose { keyboard?.attach(null) } }
    BoxWithConstraints(modifier.fillMaxSize().keyboardPanelBackground(colors.surface).testTag("clipboard-board")
        .onGloballyPositioned { boardCoordinates[0] = it }) {
        val menuDensity = LocalDensity.current
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight, menuDensity.density, menuDensity.fontScale) {
            // The source rectangles are opening-time snapshots. Resizing must not reuse stale anchors.
            menu = false; itemMenu = null; deleting = null
        }
        val list = remember(cards, listLayout) { clipboardListLayout(listLayout, cards) }
        val columns = if (list) 1 else clipboardColumnCount(maxWidth.value)
        val compactControls = maxWidth.value / LocalDensity.current.fontScale < 360f
        val content = remember(cards, activePins, columns, pinsExpanded) {
            clipboardBoardContent(cards, activePins, columns, pinsExpanded)
        }
        val pinsActuallyExpanded = pinsExpanded && content.pinsCollapsible
        // 列表: recent pictures share one sideways row (user 11-14).
        val strip = remember(content, list) { if (list) clipboardImageStrip(content.recent) else null }
        val visibleCards = remember(content, strip) { if (strip == null) content.visible else content.pinned + strip.ordered }
        fun inStrip(key: String?) = strip?.images?.any { it.key == key } == true
        /** The grid cell showing [key]: its own, or the picture row for a picture in it. */
        fun visibleCell(key: String) = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key == key || (inStrip(key) && it.key == CLIPBOARD_IMAGE_STRIP_KEY)
        }
        val focused = focusedKey?.takeIf { key -> visibleCards.any { it.key == key } }
            ?: visibleCards.firstOrNull()?.key
        val showScreenshotAccess = !favoritesMode && screenshotAccess && searchQuery == null &&
            (tab as? ClipboardTab.Category)?.filter.let { it == ClipboardFilter.ALL || it == ClipboardFilter.IMAGE }
        val gridKeys = remember(content, strip, failure != null, full, incognito, showScreenshotAccess, selecting) {
            buildList {
                if (incognito) add("incognito")
                if (full) add("full")
                if (failure != null) add("failure")
                if (showScreenshotAccess) add("screenshot-access")
                if (selecting && content.pinned.isNotEmpty()) add("select-fixed")
                addAll(content.pinned.map { it.key })
                if (content.pinned.isNotEmpty() && content.recent.isNotEmpty()) add("pins-end")
                if (selecting && content.recent.isNotEmpty()) add("select-recent")
                if (strip == null) addAll(content.recent.map { it.key })
                else {
                    addAll(strip.before.map { it.key })
                    if (strip.images.isNotEmpty()) add(CLIPBOARD_IMAGE_STRIP_KEY)
                    addAll(strip.after.map { it.key })
                }
            }
        }
        LaunchedEffect(focused, keyboard?.active, gridKeys) {
            if (keyboard?.active == true) {
                val index = gridKeys.indexOf(if (inStrip(focused)) CLIPBOARD_IMAGE_STRIP_KEY else focused)
                val visible = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == gridKeys.getOrNull(index) }
                if (index >= 0 && (visible == null || visible.offset.y < gridState.layoutInfo.viewportStartOffset ||
                    visible.offset.y + visible.size.height > gridState.layoutInfo.viewportEndOffset)) gridState.scrollToItem(index)
            }
        }
        fun openItemMenu(card: ClipboardCard, oneLine: Boolean) {
            val cell = visibleCell(card.key) ?: return
            val region = recordsCoordinates[0]?.boundsInRoot() ?: return
            val bounds = (cardBounds[card.key] ?: Rect(region.left + cell.offset.x, region.top + cell.offset.y,
                region.left + cell.offset.x + cell.size.width, region.top + cell.offset.y + cell.size.height))
                .intersect(region)
            menuFocus = 0
            itemMenu = ClipboardItemMenuAnchor(card, menuPosition(bounds, true).copy(corner = clipboardCardCorner(oneLine)))
        }
        SideEffect {
            keyboard?.attach { action ->
                val focusedCard = visibleCards.firstOrNull { it.key == (focusedKey?.takeIf { key -> key in gridKeys } ?: focused) }
                // Physical keys: Space opens the record menu, arrows move inside it, Enter runs the
                // highlighted action, Delete asks to delete the record, Esc closes the menu first.
                val openMenu = itemMenu
                if (openMenu != null || deleting != null) {
                    val entries = keyboardMenuEntries[0]
                    when (action) {
                        ClipboardNavigation.UP, ClipboardNavigation.LEFT ->
                            menuFocus = (menuFocus - 1).coerceAtLeast(0)
                        ClipboardNavigation.DOWN, ClipboardNavigation.RIGHT ->
                            menuFocus = (menuFocus + 1).coerceAtMost((entries.size - 1).coerceAtLeast(0))
                        ClipboardNavigation.CONFIRM -> entries.getOrNull(menuFocus)?.takeIf { it.enabled }?.let { entry ->
                            itemMenu = null; deleting = null; menuFocus = 0; entry.onClick()
                        }
                        ClipboardNavigation.CANCEL -> { itemMenu = null; deleting = null }
                        else -> Unit
                    }
                    return@attach true
                }
                if (menu && action == ClipboardNavigation.CANCEL) { menu = false; return@attach true }
                if (!menu && !selecting && action == ClipboardNavigation.MENU) {
                    focusedCard?.let { card -> openItemMenu(card, content.pinned.any { it.key == card.key }) }
                    return@attach true
                }
                if (!menu && !selecting && action == ClipboardNavigation.DELETE) {
                    focusedCard?.let { card ->
                        val cell = visibleCell(card.key)
                        val region = recordsCoordinates[0]?.boundsInRoot()
                        if (cell != null && region != null) {
                            deleteAnchor = menuPosition(Rect(region.left + cell.offset.x, region.top + cell.offset.y,
                                region.left + cell.offset.x + cell.size.width, region.top + cell.offset.y + cell.size.height)
                                .intersect(region), true)
                            menuFocus = 0
                            deleting = listOf(card)
                        }
                    }
                    return@attach true
                }
                if (!menu && !selecting && itemMenu == null && deleting == null) {
                    if (action == ClipboardNavigation.CONFIRM) {
                        visibleCards.firstOrNull { it.key == (focusedKey?.takeIf { key -> key in gridKeys } ?: focused) }?.let { card ->
                            when (card) {
                                is ClipboardCard.Text -> onSelectText(card.item.text)
                                is ClipboardCard.Image -> onSelectImage(card.item)
                            }
                            keyboard.close()
                        }
                    } else {
                        focusedKey = nextClipboardKey(visibleCards.map { it.key }, focusedKey?.takeIf { it in gridKeys } ?: focused, action, columns,
                            gridState.layoutInfo.visibleItemsInfo.map { ClipboardCell(it.key.toString(),
                                it.offset.x, it.offset.y, it.size.width, it.size.height) })
                    }
                }
                false
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (showControls) Row(Modifier.fillMaxWidth().clipboardPanelExpandGesture()
                .testTag("clipboard-controls").padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    CompactClipboardAction(Icons.Default.Close, "退出多选", {
                        selecting = false
                        selected = emptySet()
                    }, "clipboard-exit-selection")
                    // 全选圈挪到每一栏的分栏处（下面的 select-fixed / select-recent），「已选」靠后放（用户 11-11）。
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${selected.size}", color = colors.onSurface,
                        fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)
                            .testTag("clipboard-selected-count"))
                    val unpin = selected.isNotEmpty() && selected.all { it in activePins }
                    if (!favoritesMode || onFavoritePinsChange != null) {
                        CompactClipboardAction(if (unpin) Icons.Default.LockOpen else Icons.Default.PushPin,
                            if (unpin) "取消固定所选" else "固定所选", {
                            activeOnPinsChange(if (unpin) activePins - selected else activePins + selected)
                            selecting = false
                            selected = emptySet()
                        }, "clipboard-pin", enabled = selected.isNotEmpty(),
                            label = if (compactControls) null else if (unpin) "取消固定" else "固定")
                    }
                    CompactClipboardAction(Icons.Default.DeleteOutline, "删除所选",
                        { deleteAnchor = menuPosition(deleteBounds[0], false); deleting = cards.filter { it.key in selected } }, "clipboard-delete",
                        enabled = selected.isNotEmpty(), label = if (compactControls) null else "删除",
                        modifier = Modifier.onGloballyPositioned { deleteBounds[0] = it.boundsInRoot() })
                } else {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        searchQuery?.takeIf { searchChip }?.let { query ->
                            CompactClipboardFilter("🔍 $query ✕", true, onClearSearch,
                                Modifier.testTag("clipboard-search-chip"))
                        }
                        tabs.forEach { item ->
                            CompactClipboardFilter(item.label, tab.key == item.key, { chosenTab = item },
                                Modifier.testTag("clipboard-filter:${item.key}"))
                        }
                    }
                    onOpenSearch?.let {
                        CompactClipboardAction(Icons.Default.Search, if (favoritesMode) "搜索收藏夹" else "搜索剪贴板", it, "clipboard-search")
                    }
                    val topAddAction = if (favoritesMode) onAddFavorite else addPinned
                    topAddAction?.let { addAction ->
                        CompactClipboardAction(
                            Icons.Default.Add,
                            if (favoritesMode) "新增收藏内容" else "新增固定内容",
                            addAction,
                            if (favoritesMode) "clipboard-top-add-favorite" else "clipboard-top-add",
                        )
                    }
                    onToggleFavoritesMode?.let { toggleFav ->
                        CompactClipboardAction(
                            if (favoritesMode) Icons.Default.Star else Icons.Default.StarBorder,
                            if (favoritesMode) "退出收藏夹" else "收藏夹",
                            toggleFav,
                            "clipboard-favorites",
                            active = favoritesMode,
                        )
                    }
                    CompactClipboardAction(Icons.Default.MoreHoriz, "更多操作", {
                        moreAnchor = menuPosition(moreBounds[0], false); menu = !menu
                    }, "clipboard-more", modifier = Modifier.onGloballyPositioned { moreBounds[0] = it.boundsInRoot() })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("clipboard-records-region")
                .onGloballyPositioned { recordsCoordinates[0] = it }
                .clipboardPanelCollapseGesture(!selecting && !menu && itemMenu == null && deleting == null) {
                    !gridState.canScrollBackward
                }
                .clipboardCategorySwipe(!selecting && !menu && itemMenu == null && deleting == null) { delta ->
                    chosenTab = tabs.afterSwipe(tab, delta)
                }) {
                val expansion = LocalClipboardPanelExpansion.current
                val density = LocalDensity.current
                // Expanding exposes more records, not taller cards. Use the normal keyboard's
                // records area even at the expanded endpoint, so releasing cannot reflow the grid.
                val chromeHeight = remember(expansion, maxWidth, density.fontScale) {
                    expansion?.let { kotlin.math.round(it.height) - maxHeight.value } ?: 0f
                }
                val stableRecordsHeight = expansion?.let { (it.collapsedHeight - chromeHeight).coerceAtLeast(0f) }
                    ?: maxHeight.value
                val pinnedHeight = clipboardPinnedPreviewHeight(stableRecordsHeight).dp
                // The list layout's picture row: every picture the same small square, the row scrolls sideways.
                val stripHeight = pinnedHeight.coerceAtLeast(ClipboardImageMinHeight.dp)
                @Composable
                fun RecordCard(card: ClipboardCard, fixedRecords: Boolean, previewHeight: Dp?, modifier: Modifier) {
                    val chosen = card.key in selected
                    val cardDensity = if (cardTextPercent == 100) density
                        else androidx.compose.ui.unit.Density(density.density, density.fontScale * cardTextPercent / 100f)
                    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides cardDensity) {
                    ClipboardRecordCard(card = card, pinned = card.key in activePins, chosen = chosen, maxLines = cardMaxLines,
                        masked = !favoritesMode && card is ClipboardCard.Text && card.item.sensitive && card.key !in revealed,
                        selecting = selecting, keyboardFocused = keyboard?.active == true && card.key == focused,
                        previewHeight = previewHeight,
                        imageHeight = imageHeight, recordsHeightDp = stableRecordsHeight,
                        modifier = modifier.onGloballyPositioned { cardBounds[card.key] = it.boundsInRoot() },
                        onClick = {
                            focusedKey = card.key
                            if (selecting) selected = if (chosen) selected - card.key else selected + card.key
                            else when (card) {
                                is ClipboardCard.Text -> onSelectText(card.item.text)
                                is ClipboardCard.Image -> onSelectImage(card.item)
                            }
                        }, onLongClick = {
                            if (selecting) {
                                selected = if (chosen) selected - card.key else selected + card.key
                            } else openItemMenu(card, fixedRecords)
                        })
                    }
                }
                // A different category or photo feed must own a different lazy composition.
                // Merely clearing records does not immediately clear fade-out item layers.
                key(tab.key, searchQuery, listLayout, photoAccess, galleryPhotosEnabled, favoritesMode) {
                    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().testTag("clipboard-records"),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalItemSpacing = 6.dp) {
                    if (!favoritesMode && incognito) item(key = "incognito", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardIncognitoNotice(onIncognitoOff)
                    }
                    if (!favoritesMode && full) item(key = "full", span = StaggeredGridItemSpan.FullLine) {
                        Text("固定记录占满了剪贴板，新复制的内容存不下。删掉一些后恢复。",
                            Modifier.padding(horizontal = 4.dp, vertical = 6.dp).testTag("clipboard-full"),
                            color = colors.error, fontSize = 13.sp)
                    }
                    if (!favoritesMode && failure != null) item(key = "failure", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardImageFailureNotice(failure, onSystemPaste, onShare, onDismissFailure)
                    }
                    if (showScreenshotAccess) item(key = "screenshot-access", span = StaggeredGridItemSpan.FullLine) {
                        ClipboardScreenshotAccessNotice(onAllowScreenshots, onHideScreenshots)
                    }
                    if (cards.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                        val filterLabel = (tab as? ClipboardTab.Category)?.filter ?: ClipboardFilter.ALL
                        Text(when {
                            searchQuery != null -> "没有找到「$searchQuery」"
                            favoritesMode -> "暂无${if (filterLabel == ClipboardFilter.ALL) "收藏内容" else "收藏的${filterLabel.label}"}。长按剪贴板记录选「收藏」，或点右上角菜单「新增收藏内容」。"
                            else -> "暂无${if (filterLabel == ClipboardFilter.ALL) "剪贴板记录" else filterLabel.label}，复制后会显示在这里。"
                        },
                            Modifier.padding(vertical = 24.dp), color = colors.onSurfaceVariant, fontSize = 14.sp)
                    }
                    fun LazyStaggeredGridScope.records(records: List<ClipboardCard>, fixedRecords: Boolean) {
                        items(records, key = { it.key }) { card ->
                            RecordCard(card, fixedRecords,
                                // Pins are one line; a recent picture follows 「图片高度」 (user 11-09), never
                                // taller than part of the board, so a tall screenshot does not fill it.
                                previewHeight = if (fixedRecords) ClipboardPinnedRowHeight
                                    else if (card is ClipboardCard.Image && imageHeight == ClipboardImageHeight.LOW) pinnedHeight
                                    else null,
                                // Lazy layout retains removed keyed items through fade-out. Keep one
                                // grid so collapsing pins does not flash or replace recent history.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = keyboardPanelMotion(true),
                                    placementSpec = keyboardPanelMotion(pinsActuallyExpanded),
                                    fadeOutSpec = keyboardPanelMotion(false)))
                        }
                    }
                    // 多选时每一栏前面一行自己的全选圈（用户 11-11）。
                    fun LazyStaggeredGridScope.sectionSelectAll(fixed: Boolean) {
                        item(key = if (fixed) "select-fixed" else "select-recent", span = StaggeredGridItemSpan.FullLine) {
                            val section = clipboardSectionKeys(cards, activePins, fixed)
                            val all = section.isNotEmpty() && selected.containsAll(section)
                            ClipboardSectionSelectAll(
                                label = when {
                                    fixed -> "全选固定"
                                    content.pinned.isEmpty() -> "全选"
                                    else -> "全选未固定"
                                } + " ${section.size}",
                                all = all, tag = if (fixed) "clipboard-select-fixed" else "clipboard-select-recent",
                                modifier = Modifier.animateItem(placementSpec = keyboardPanelMotion(pinsActuallyExpanded))) {
                                selected = toggleClipboardSection(selected, section)
                                // Folded pins: show what was selected before it can be deleted.
                                if (fixed && !all && content.pinsCollapsible) changePinsExpansion(true)
                            }
                        }
                    }
                    if (selecting && content.pinned.isNotEmpty()) sectionSelectAll(fixed = true)
                    records(content.pinned, fixedRecords = true)
                    if (content.pinned.isNotEmpty() && content.recent.isNotEmpty()) {
                        item(key = "pins-end", span = StaggeredGridItemSpan.FullLine) {
                            val motion = Modifier.animateItem(placementSpec = keyboardPanelMotion(pinsActuallyExpanded))
                            if (!content.pinsCollapsible) HorizontalDivider(motion.testTag("clipboard-pins-divider"), thickness = .5.dp,
                                color = colors.onSurface.copy(alpha = .18f))
                            else PinsFoldDivider(pinsActuallyExpanded, content.pinnedCount - content.pinned.size,
                                colors.onSurface, motion) { changePinsExpansion(!pinsExpanded) }
                        }
                    }
                    if (selecting && content.recent.isNotEmpty()) sectionSelectAll(fixed = false)
                    if (strip == null) records(content.recent, fixedRecords = false)
                    else {
                        records(strip.before, fixedRecords = false)
                        if (strip.images.isNotEmpty()) item(key = CLIPBOARD_IMAGE_STRIP_KEY, span = StaggeredGridItemSpan.FullLine) {
                            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().height(stripHeight)
                                .animateItem(fadeInSpec = keyboardPanelMotion(true),
                                    placementSpec = keyboardPanelMotion(pinsActuallyExpanded),
                                    fadeOutSpec = keyboardPanelMotion(false))
                                .testTag("clipboard-image-strip"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(strip.images.size, key = { strip.images[it].key }) { index ->
                                    RecordCard(strip.images[index], fixedRecords = false, previewHeight = stripHeight,
                                        modifier = Modifier.width(stripHeight))
                                }
                            }
                        }
                        records(strip.after, fixedRecords = false)
                    }
                    }
                }
            }
        }
        itemMenu?.let { anchor ->
            val card = anchor.card
            val favorited = favoritesMode || isFavorited(card)
            val actions = buildList {
                if (!favoritesMode || onFavoritePinsChange != null) {
                    add(LongPressMenuEntry(Icons.Default.PushPin, if (card.key in activePins) "取消固定" else "固定") {
                        activeOnPinsChange(if (card.key in activePins) activePins - card.key else activePins + card.key)
                    })
                }
                onToggleFavorite?.let { toggleFav ->
                    add(LongPressMenuEntry(
                        if (favorited) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorited) "取消收藏" else "收藏",
                        tag = "clipboard-item-favorite",
                    ) { toggleFav(card) })
                }
                if (!favoritesMode && card is ClipboardCard.Text && card.item.sensitive) {
                    // A kept password: no 拆字 (its pieces would be ordinary records), shown only on request.
                    val shown = card.key in revealed
                    add(LongPressMenuEntry(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (shown) "隐藏" else "显示", tag = "clipboard-item-reveal") {
                        revealed = if (shown) revealed - card.key else revealed + card.key
                    })
                    // Ordinary text hidden by mistake (user 11-09): back to an ordinary record, kept past the hour.
                    add(LongPressMenuEntry(Icons.Default.NoEncryption, "不是密码", tag = "clipboard-item-not-password") {
                        revealed = revealed - card.key
                        ClipboardManager.getInstance(appContext).markNotSensitive(card.item.id, card.item.text)
                    })
                } else if (card is ClipboardCard.Text) {
                    add(LongPressMenuEntry(Icons.Default.ContentCut, "拆字") { onSplit(card.item.text, card.item.id) })
                    val targetEdit = if (favoritesMode) editFavoriteText else editText
                    targetEdit?.let { edit -> add(LongPressMenuEntry(Icons.Default.Edit, "编辑") {
                        edit(card.item.id, card.item.text, card.item.code)
                    }) }
                    onTranslateText?.let { translate -> add(LongPressMenuEntry(Icons.Default.Translate, "翻译",
                        tag = "clipboard-item-translate") { translate(card.item.text) }) }
                }
                add(LongPressMenuEntry(Icons.Default.DoneAll, "多选") {
                    selecting = true; selected = setOf(card.key)
                })
                add(LongPressMenuEntry(Icons.Default.DeleteOutline, "删除", colors.error) {
                    deleteAnchor = anchor.position; deleting = listOf(card)
                })
            }
            keyboardMenuEntries[0] = actions
            LongPressMenuOverlay(anchor.position.bounds, anchor.position.occupied,
                onDismiss = { itemMenu = null }, menuItems = actions, highlightCorner = anchor.position.corner,
                focusedIndex = menuFocus.takeIf { keyboard?.active == true })
        }
        if (menu) {
            val actions = buildList {
                add(CompactActionMenuEntry(Icons.Default.DoneAll, "多选", enabled = cards.isNotEmpty(), tag = "clipboard-select") {
                    selecting = true; selected = emptySet()
                })
                onListLayoutChange?.let { change ->
                    add(CompactActionMenuEntry(
                        if (listLayout) Icons.Default.GridView else Icons.Default.ViewAgenda,
                        if (listLayout) "切换为平铺" else "切换为列表",
                        tag = "clipboard-layout",
                    ) { change(!listLayout) })
                }
                if (!favoritesMode) {
                    addPinned?.let { action -> add(CompactActionMenuEntry(Icons.Default.Edit,
                        "新增固定内容", tag = "clipboard-add-text", onClick = action)) }
                }
                onAddFavorite?.let { action ->
                    add(CompactActionMenuEntry(Icons.Default.StarBorder,
                        "新增收藏内容", tag = "clipboard-add-favorite", onClick = action))
                }
                if (!favoritesMode) {
                    add(CompactActionMenuEntry(Icons.Default.Image, "添加图片", tag = "clipboard-pick", onClick = onPick))
                    add(CompactActionMenuEntry(Icons.Default.PhotoLibrary, if (photoAccess) "关闭最近截图" else "启用最近截图",
                        tag = "clipboard-photos", onClick = onPhotoAccess))
                    onPullRemote?.let { action -> add(CompactActionMenuEntry(Icons.Default.Sync, "同步", tag = "clipboard-sync", onClick = action)) }
                }
                onOpenSettings?.let { action -> add(CompactActionMenuEntry(Icons.Default.Settings, "剪贴板设置",
                    tag = "clipboard-settings", onClick = action)) }
                closeAfterPaste?.let { on -> add(CompactActionMenuEntry(
                    if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "上屏后自动关闭剪贴板",
                    tag = "clipboard-close-after-paste") { onCloseAfterPasteChange(!on) }) }
                if (!favoritesMode) {
                    add(CompactActionMenuEntry(Icons.Default.DeleteOutline, "清空未固定", colors.error,
                        enabled = all.any { it.key !in pins }, tag = "clipboard-clear") {
                        deleteAnchor = moreAnchor; clearingAll = onClearUnpinned != null
                        deleting = all.filterNot { it.key in pins }
                    })
                }
            }
            CompactActionMenu(moreAnchor.bounds, actions, onDismiss = { menu = false },
                occupiedBounds = emptyList(), preferSide = false, tag = "clipboard-more-menu",
                surfaceTag = "clipboard-board-action-card", animate = false, shadowElevation = 0.dp)
        }
        deleting?.let { records ->
            val imageCount = records.count { it is ClipboardCard.Image }
            // Clearing all counts the whole unfixed history, including pages not read yet.
            val textCount = if (clearingAll && unpinnedTextTotal >= 0) unpinnedTextTotal else records.size - imageCount
            val title = when {
                imageCount == 0 -> "删除 $textCount 条文本记录？"
                textCount == 0 -> "删除 $imageCount 条图片记录？"
                else -> "删除 $textCount 条文本和 $imageCount 条图片记录？"
            }
            val actions = listOf(
                CompactActionMenuEntry(Icons.Default.DeleteOutline, "删除", colors.error, tag = "clipboard-confirm-delete") {
                    val clearAll = clearingAll && onClearUnpinned != null
                    clearingAll = false
                    val keys = records.mapTo(mutableSetOf()) { it.key }
                    removing = removing + keys
                    if (favoritesMode) {
                        onRemoveFavorites?.invoke(records)
                    } else {
                        if (clearAll) onClearUnpinned.invoke(records) else onRemove(records)
                        onPinsChange(pins - keys)
                    }
                    selected = emptySet(); selecting = false
                },
                CompactActionMenuEntry(Icons.Default.Close, "取消", tag = "clipboard-cancel-delete") {},
            )
            // Same anchored card as the record actions, in the same place, at full touch size.
            if (itemMenu == null) keyboardMenuEntries[0] = actions
            LongPressMenuOverlay(deleteAnchor.bounds, deleteAnchor.occupied, onDismiss = { deleting = null; clearingAll = false },
                menuItems = actions, title = title, focusedIndex = menuFocus.takeIf { keyboard?.active == true },
                highlightCorner = deleteAnchor.corner,
                message = if (imageCount > 0) "只移除剪贴板记录，保留原始图片。" else null,
                tag = "clipboard-delete-menu", surfaceTag = "clipboard-board-action-card")
        }
    }
}

@Composable
private fun CompactClipboardFilter(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected) { if (isSelected) bringIntoView.bringIntoView() }
    Box(modifier.bringIntoViewRequester(bringIntoView).heightIn(min = PanelCategoryHeight).clip(RoundedCornerShape(6.dp))
        .background(if (isSelected) frostedKeyColor(colors.secondaryContainer, colors.onSurface, glass) else Color.Transparent)
        .semantics { selected = isSelected }
        .clickable(role = Role.Tab, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = PanelCategoryVerticalPadding), contentAlignment = Alignment.Center) {
        Text(label, color = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardRecordCard(
    card: ClipboardCard, pinned: Boolean, chosen: Boolean, selecting: Boolean,
    keyboardFocused: Boolean, previewHeight: Dp?, maxLines: Int = 4,
    /** A recent picture without [previewHeight]: how tall, from its width, the records area and the picture. */
    imageHeight: ClipboardImageHeight = ClipboardImageHeight.STANDARD, recordsHeightDp: Float = Float.MAX_VALUE,
    /** A kept password not asked to be shown: dots instead of its text (#95). */
    masked: Boolean = false,
    onClick: () -> Unit, onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val highlighted = chosen || keyboardFocused
    val shape = RoundedCornerShape(clipboardCardCorner(previewHeight == ClipboardPinnedRowHeight))
    val oneLine = previewHeight == ClipboardPinnedRowHeight
    val panelSurface = clipboardPanelSurface()
    val liquid = com.kingzcheung.xime.ui.theme.LiquidGlass.surfacesOn()
    BoxWithConstraints(modifier.fillMaxWidth()
        .then(if (previewHeight != null) Modifier.height(previewHeight) else Modifier)
        .clip(shape)
        .background(frostedKeyColor(if (highlighted) colors.secondaryContainer else clipboardCardColor(),
            colors.onSurface, glass))
        // 黑白主题深色是纯黑底：卡片不铺灰，靠描边看出边界（与设置页卡片的 idleBorderAlpha 一致，#82）。
        // 液态玻璃：没选中的卡片用按键同款细亮边代替淡描边；选中、键盘焦点的 2dp 强调色描边不变。
        .then(if (!highlighted && liquid) Modifier.liquidGlassEdge(clipboardCardCorner(oneLine))
            else Modifier.border(if (highlighted) 2.dp else .5.dp,
                if (highlighted) colors.primary
                else colors.onSurface.copy(alpha = if (panelSurface.isNearBlack()) idleBorderAlpha(panelSurface) else .08f), shape))
        .semantics { focused = keyboardFocused; if (selecting) selected = chosen }
        .testTag("clipboard-card:${card.key}")
        .combinedClickable(role = if (selecting) Role.Checkbox else Role.Button,
            onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = if (selecting) "切换选择" else "更多操作")) {
        when (card) {
            is ClipboardCard.Text -> Text(if (masked) com.kingzcheung.xime.clipboard.ClipboardLinkChoices.SENSITIVE_MASK
                else clipboardPreviewText(card.item.text),
                // A one-line record is as tall as a pinned row and its line sits in the middle; the old
                // 48dp floor left a strip under the text (user 10-30). Longer text grows the card.
                Modifier.align(Alignment.CenterStart).fillMaxWidth()
                    .heightIn(min = if (previewHeight == null) ClipboardPinnedRowHeight else 0.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = if (selecting) 28.dp else 8.dp,
                        end = if (pinned) 26.dp else 8.dp, top = if (oneLine) 0.dp else 8.dp, bottom = if (oneLine) 0.dp else 8.dp),
                color = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                fontSize = 16.sp, lineHeight = 23.sp,
                maxLines = if (oneLine) 1 else if (previewHeight != null) 3 else maxLines,
                overflow = TextOverflow.Ellipsis)
            is ClipboardCard.Image -> {
                val context = LocalContext.current
                val density = LocalDensity.current
                val widthPx = constraints.maxWidth.coerceAtLeast(1)
                val uri = card.item.uri
                // Only 「完整」「按图片比例」 read the picture's size; a known one is used at once, so reopening never jumps.
                val aspect by produceState(ClipboardImageAspect.cached(uri), uri, imageHeight) {
                    if (imageHeight.followsPicture && value == null)
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ClipboardImageAspect.read(context, uri)
                        }
                }
                val cardHeight = previewHeight ?: clipboardImageCardHeight(imageHeight,
                    with(density) { widthPx.toDp().value }, recordsHeightDp, aspect).dp
                val heightPx = with(density) { cardHeight.roundToPx() }
                val thumbnail = remember(context, card.item.uri, widthPx, heightPx) {
                    ImageRequest.Builder(context).data(card.item.uri)
                        .size(widthPx, heightPx.coerceAtLeast(1)).build()
                }
                // 「完整」fills the card's width like the others (user 11-18: a tall screenshot shown whole left wide
                // empty strips on both sides). The card already follows the picture's shape, so only a picture taller
                // than the board is cut, and it keeps its top (a screenshot's top is what tells it apart).
                val keepTop = previewHeight == null && imageHeight == ClipboardImageHeight.FULL
                AsyncImage(thumbnail, card.item.label, contentScale = ContentScale.Crop,
                    alignment = if (keepTop) Alignment.TopCenter else Alignment.Center,
                    modifier = if (previewHeight == null) Modifier.fillMaxWidth().height(cardHeight)
                    else Modifier.fillMaxSize())
            }
        }
        if (pinned) ClipboardCardBadge(Icons.Default.Lock, "已固定",
            Modifier.align(if (oneLine) Alignment.CenterEnd else Alignment.TopEnd).padding(4.dp), "clipboard-pin:${card.key}")
        if (selecting) {
            ClipboardCardBadge(if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                if (chosen) "已选" else "未选", Modifier.align(Alignment.TopStart).padding(4.dp)
                    .semantics { selected = chosen; role = Role.Checkbox }, "clipboard-selection:${card.key}")
        }
    }
}

@Composable
private fun CompactClipboardAction(icon: ImageVector, description: String, onClick: () -> Unit, tag: String,
    enabled: Boolean = true, active: Boolean = false, label: String? = null, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val color = (if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
        .copy(alpha = if (enabled) 1f else .38f)
    Row(modifier.heightIn(min = PanelCategoryHeight).widthIn(min = 28.dp).clip(RoundedCornerShape(6.dp))
        .background(if (active) frostedKeyColor(colors.secondaryContainer, colors.onSurface, glass) else Color.Transparent)
        .testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, description, Modifier.size(16.dp), tint = color)
        if (label != null) Text(label, color = color, fontSize = 12.sp, lineHeight = 18.sp,
            maxLines = 1, modifier = Modifier.padding(start = 2.dp))
    }
}

/** 多选时一栏的全选：圈 + 「全选固定 3」，整行可点。 */
@Composable
private fun ClipboardSectionSelectAll(label: String, all: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp))
        .semantics { selected = all }
        .clickable(role = Role.Checkbox, onClickLabel = if (all) "取消这一栏的全选" else "全选这一栏", onClick = onClick)
        .testTag(tag).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (all) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = color)
        Text(label, color = color, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ClipboardCardBadge(icon: ImageVector, description: String, modifier: Modifier, tag: String) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.testTag(tag).clip(RoundedCornerShape(4.dp))
        .background(frostedKeyColor(clipboardCardColor(), colors.onSurface,
            LocalKeyboardInputPreferences.current.frostedGlass)).padding(2.dp)) {
        Icon(icon, description, Modifier.size(16.dp), tint = colors.onSurface)
    }
}

/**
 * 固定内容折叠时，分隔线中间放一个带箭头的标签（“展开 N 条”/“收起”），整条线都可点：
 * 一眼能看出下面还有内容、这里可以展开；展开后箭头朝上，点一下收起。
 */
@androidx.compose.runtime.Composable
private fun PinsFoldDivider(expanded: Boolean, hidden: Int, content: androidx.compose.ui.graphics.Color,
    modifier: Modifier, onToggle: () -> Unit) {
    val line = content.copy(alpha = .22f)
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("clipboard-pins-divider")
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = if (expanded) "收起" else "展开 $hidden 条", onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = .5.dp, color = line)
        PinsFoldPill(if (expanded) "收起" else "展开 $hidden 条", expanded, content,
            Modifier.padding(horizontal = 8.dp).testTag("clipboard-pins-fold"))
        HorizontalDivider(Modifier.weight(1f), thickness = .5.dp, color = line)
    }
}

/** 固定内容的展开/收起胶囊：分隔线中间和工具栏右上角共用同一个样子。 */
@Composable
private fun PinsFoldPill(label: String, expanded: Boolean, content: Color, modifier: Modifier = Modifier) {
    val line = content.copy(alpha = .22f)
    val tint = content.copy(alpha = .78f)
    Row(modifier.clip(RoundedCornerShape(50)).border(.5.dp, line, RoundedCornerShape(50))
        .padding(start = 10.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
            Modifier.size(18.dp), tint = tint)
    }
}

/** One fixed height for a pinned record: a single line of text, or a thin strip of a picture. */
internal val ClipboardPinnedRowHeight = 40.dp

/**
 * 解析剪贴板面板的实际底色。
 * iOS 渐变等梯度/图片皮肤在 MaterialTheme 里沿用了纯黑 fallback surface，
 * 若不回退到调色板的 visible surface，文字框会呈纯黑方块（黑白纯黑主题两者皆近黑，仍保持纯黑）。
 */
@Composable
private fun clipboardPanelSurface(): Color {
    val colors = MaterialTheme.colorScheme
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current
    return if (colors.surface.isNearBlack() && palette != null && !palette.surface.isNearBlack()) {
        palette.surface
    } else {
        colors.surface
    }
}

/**
 * Cards sit just above the panel in the theme's own colors (dark panel → slightly lighter dark
 * card, light panel → slightly darker light card), instead of a fixed mid gray.
 */
@Composable
private fun clipboardCardColor(): Color {
    val colors = MaterialTheme.colorScheme
    val surface = clipboardPanelSurface()
    val dark = surface.luminance() < .5f
    return androidx.compose.ui.graphics.lerp(surface, colors.onSurface, if (dark) .10f else .06f)
        .copy(alpha = 1f)
}
