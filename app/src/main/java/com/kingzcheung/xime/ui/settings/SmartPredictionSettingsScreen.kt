package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Backspace
import androidx.compose.material.icons.automirrored.twotone.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Dialpad
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.Spellcheck
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SmartPredictionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPredictionSettingsContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SmartPredictionSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportLearningData) }
    val importFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImport = uri }
    pendingImport?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入学习文件或文字") },
            text = { Text("新增：保留已学的，加入文件里的内容。\n替换：用文件内容替换已学的。\n也可以选普通文字文件，当作打过一遍来学。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pendingImport = null; viewModel.importLearningData(uri, merge = false) }) { Text("替换") }
                    Button(onClick = { pendingImport = null; viewModel.importLearningData(uri, merge = true) }) { Text("新增") }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            viewModel.showToast(message)
            viewModel.clearToast()
        }
    }

    KeyboardSettingsScaffold("智能联想", onBack, preview = pendingImport == null,
        example = if (uiState.isEnabled) com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Associations
            else com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Example.Keyboard) { modifier ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = modifier.settingsGutterScroll(listState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding(top = 8.dp, bottom = 8.dp)
        ) {
            item {
                SettingsSection(title = "功能开关", content = {
                    Row(
                        modifier = Modifier.settingsSearchAnchor("启用智能联想")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用智能联想",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "上屏后接着给出下一个词",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PerformanceCostNote(com.kingzcheung.xime.settings.PerformanceCosts.Item.PREDICTION)
                        }
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                                colors = SwitchDefaults.colors()
                            )
                        }
                    }
                })
            }

            if (uiState.isEnabled) {
                item {
                    var spaceCommitEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSpaceCommitAssociationEnabled(context))
                    }
                    var singleMode by remember {
                        mutableStateOf(SettingsPreferences.isSingleAssociationMode(context))
                    }
                    var restoreAssociations by remember {
                        mutableStateOf(SettingsPreferences.isRestorePreviousAssociationsEnabled(context))
                    }
                    Column { SettingsSection(title = "联想行为", content = {
                        SettingsToggleItem(
                            icon = Icons.TwoTone.AutoAwesome,
                            title = "空格上屏联想候选",
                            subtitle = "有联想词时，按空格上屏第一个",
                            checked = spaceCommitEnabled,
                            onCheckedChange = {
                                spaceCommitEnabled = it
                                SettingsPreferences.setSpaceCommitAssociationEnabled(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsToggleItem(
                            icon = Icons.TwoTone.AutoAwesome,
                            title = "删空字符后恢复联想",
                            subtitle = "联想出现后继续输入，删空新输入的字符时恢复之前的联想。",
                            checked = restoreAssociations,
                            onCheckedChange = {
                                restoreAssociations = it
                                SettingsPreferences.setRestorePreviousAssociationsEnabled(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "连续联想",
                            subtitle = "选了联想词后接着出下一批，接不上时自动停",
                            isSelected = !singleMode,
                            onClick = {
                                singleMode = false
                                SettingsPreferences.setAssociationSingleMode(context, false)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "单次联想",
                            subtitle = "选一个联想词就停，下次打字再出",
                            isSelected = singleMode,
                            onClick = {
                                singleMode = true
                                SettingsPreferences.setAssociationSingleMode(context, true)
                            }
                        )
                    })
                    // 「退格直接删字」「删字后给同音字」11-01 搬到 按键与手势 › 删除键，这里只指个路，不放第二个开关。
                    SettingsNote("退格直接删字、删字后给同音字在「按键与手势 › 删除键」。", jumps = listOf("按键与手势 › 删除键"))
                    }
                }
            }

            item {
                // 与键盘菜单「无痕」是同一个开关，两边改动立即互相反映。
                val toggles = com.kingzcheung.xime.ui.keyboard.rememberQuickToggleStates()
                SettingsSection(title = "无痕", content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.VisibilityOff,
                        title = "无痕模式",
                        subtitle = "不学习打过的词，不记录剪贴板",
                        checked = toggles.incognito,
                        onCheckedChange = { SettingsPreferences.setIncognito(context, it) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 删掉刚上屏的词不学习（B站反馈 10-31，默认关）；无痕开着时本来就什么都不学，置灰。
                    var unlearnRejected by remember { mutableStateOf(SettingsPreferences.isUnlearnRejectedCommit(context)) }
                    SettingsToggleItem(
                        icon = Icons.AutoMirrored.TwoTone.Backspace,
                        title = "删掉刚打的词不学习",
                        subtitle = "刚上屏就删掉的词不记进词库",
                        checked = unlearnRejected,
                        onCheckedChange = {
                            unlearnRejected = it
                            SettingsPreferences.setUnlearnRejectedCommit(context, it)
                        },
                        lockMessage = if (toggles.incognito) "无痕模式开着时不需要" else null,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 锁定词序（专业打字手，11-18，默认关）：候选顺序固定，每个位置都能盲打。
                    var lockWordOrder by remember { mutableStateOf(SettingsPreferences.isLockWordOrder(context)) }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Lock,
                        title = "锁定词序",
                        subtitle = "候选顺序固定，不随使用改变，也不记新词",
                        checked = lockWordOrder,
                        onCheckedChange = {
                            lockWordOrder = it
                            SettingsPreferences.setLockWordOrder(context, it)
                        },
                        lockMessage = if (toggles.incognito) "无痕模式开着时不需要" else null,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 自造词角标（用户 11-19 要开关，默认显示，同以前）：关掉只是候选上不画小铅笔，照常学习。
                    var showLearnedMark by remember { mutableStateOf(SettingsPreferences.showLearnedMark(context)) }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Edit,
                        title = "显示自造词角标",
                        subtitle = "学到的词在候选右上角显示小铅笔；关掉只是不显示，照常学习",
                        checked = showLearnedMark,
                        onCheckedChange = {
                            showLearnedMark = it
                            SettingsPreferences.setShowLearnedMark(context, it)
                        },
                    )
                })
            }

            item {
                LearningDataControls(
                    unique = uiState.cacheSize, observations = uiState.observations,
                    profileSize = uiState.profileSize, busy = uiState.isSaving,
                    status = uiState.learningStatus,
                    onSave = viewModel::saveUserData, onRefresh = viewModel::refreshCacheSize,
                    onExport = { exportFile.launch("CJIME-personal-learning.json") },
                    // 不按类型筛：md、srt 等在很多手机上没有 text/plain 类型，选了再看内容是不是文字（用户 11-15：md 都选不了）
                    onImport = { importFile.launch(arrayOf("*/*")) }
                )
            }

            item {
                SettingsSection(title = "短语与数字联想", content = {
                    var digitPhrases by remember { mutableStateOf(com.kingzcheung.xime.service.DigitPhrases.isEnabled(context)) }
                    SettingsToggleItem(
                        Icons.TwoTone.Dialpad,
                        "数字出短语",
                        "打数字时，候选里给出编码相符的「我的短语」",
                        checked = digitPhrases,
                        onCheckedChange = {
                            digitPhrases = it
                            com.kingzcheung.xime.service.DigitPhrases.setEnabled(context, it)
                        },
                        costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.DIGIT_PHRASES,
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    var englishPhrases by remember { mutableStateOf(com.kingzcheung.xime.service.EnglishPhrases.isEnabled(context)) }
                    SettingsToggleItem(
                        Icons.TwoTone.Dialpad,
                        "英文出短语",
                        "英文打出短语编码时，候选里给出「我的短语」",
                        checked = englishPhrases,
                        onCheckedChange = {
                            englishPhrases = it
                            com.kingzcheung.xime.service.EnglishPhrases.setEnabled(context, it)
                        },
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    var numberHistory by remember { mutableStateOf(com.kingzcheung.xime.service.NumberHistory.isEnabled(context)) }
                    SettingsToggleItem(
                        Icons.TwoTone.Dialpad,
                        "联想常用数字",
                        "打过的常用长数字，打开头时直接给出",
                        checked = numberHistory,
                        onCheckedChange = {
                            numberHistory = it
                            com.kingzcheung.xime.service.NumberHistory.setEnabled(context, it)
                        },
                    )
                })
            }

            item {
                val savedGlidePrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                var glidePrefs by remember(savedGlidePrefs) { mutableStateOf(savedGlidePrefs) }
                var assocLearning by remember { mutableStateOf(SettingsPreferences.isPersonalAssociationLearningEnabled(context)) }
                var glideStats by remember { mutableStateOf(com.kingzcheung.xime.glide.PersonalGlideCalibration.stats(context.filesDir)) }
                var assocStats by remember { mutableStateOf(com.kingzcheung.xime.association.PersonalAssociationBooster.stats(context.filesDir)) }
                var tuningGlide by remember { mutableStateOf(false) }
                var showResetDialog by remember { mutableStateOf(false) }
                var showReportDialog by remember { mutableStateOf(false) }
                var reportText by remember { mutableStateOf("") }
                val tuneScope = rememberCoroutineScope()

                SettingsSection(title = "自递进校准与数据", content = {
                    SettingsToggleItem(
                        Icons.TwoTone.Spellcheck,
                        "全输入联想与局部改错自递进",
                        "打字/滑行/局部改错的选词、删词和句内纠错反馈会本机重排后续所有联想候选",
                        checked = assocLearning,
                        onCheckedChange = {
                            assocLearning = it
                            SettingsPreferences.setPersonalAssociationLearningEnabled(context, it)
                        },
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    SettingsToggleItem(
                        Icons.TwoTone.Gesture,
                        "个人整句滑行自递进校准",
                        "仅在本机记住按键偏移、常用连划整句与纠错习惯，不上传",
                        checked = glidePrefs.personalChineseGlideLearning,
                        onCheckedChange = {
                            glidePrefs = glidePrefs.copy(personalChineseGlideLearning = it)
                            glidePrefs.save(context)
                        },
                        lockMessage = "需先打开「中文滑行」"
                            .takeIf { !glidePrefs.personalChineseGlideLearning && !glidePrefs.chineseGlideTyping },
                    )
                    if (glidePrefs.personalChineseGlideLearning || assocLearning) {
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                        SettingsItem(
                            Icons.TwoTone.Spellcheck,
                            "查看 / 导出自学习校准数据",
                            "滑行 ${glideStats.calibratedKeys} 键 · 混淆 ${glideStats.confusionCount} 组 · 联想 ${assocStats.boostCount} 强化 / ${assocStats.penaltyCount} 降权",
                            onClick = {
                                tuneScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val report = com.kingzcheung.xime.glide.PersonalGlideCalibration.buildDiagnosticReport(context.filesDir)
                                    val g = com.kingzcheung.xime.glide.PersonalGlideCalibration.stats(context.filesDir)
                                    val a = com.kingzcheung.xime.association.PersonalAssociationBooster.stats(context.filesDir)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        glideStats = g
                                        assocStats = a
                                        reportText = report
                                        showReportDialog = true
                                    }
                                }
                            },
                        )
                    }
                    if (glidePrefs.personalChineseGlideLearning) {
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                        val subtitle = if (tuningGlide) "正在回放历史滑行样本并调优…"
                        else "已校准 ${glideStats.calibratedKeys} 键 · 个人短语 ${glideStats.phraseCount} 条 · 历史样本 ${glideStats.sampleCount} 条（点击调优）"
                        SettingsItem(
                            Icons.TwoTone.Spellcheck,
                            "整句滑行历史调优",
                            subtitle,
                            onClick = {
                                if (!tuningGlide) {
                                    tuningGlide = true
                                    tuneScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val schemaId = SettingsPreferences.getCurrentSchema(context)
                                        val external = com.kingzcheung.xime.glide.ExternalGlideEncodings.parse(
                                            SettingsPreferences.getPrefsPublic(context)
                                                .getString(com.kingzcheung.xime.glide.ExternalGlideEncodings.KEY, null)
                                        )
                                        val encoding = com.kingzcheung.xime.glide.chineseGlideEncoding(
                                            com.kingzcheung.xime.settings.InputProfiles.describe(schemaId),
                                            schemaId,
                                            external,
                                        )
                                        val dp = encoding?.doublePinyin
                                        val sKey = dp?.id ?: "pinyin"
                                        val result = com.kingzcheung.xime.glide.PersonalGlideCalibration.stats(context.filesDir)
                                        val msg = if (result.sampleCount < 12) {
                                            "当前本机样本 ${result.sampleCount} 条（满 12 条后可基于历史轨迹自动调优，多滑几句即可）"
                                        } else {
                                            val defaultKeys = buildMap {
                                                listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
                                                    .forEachIndexed { r, (row, inset) ->
                                                        row.forEachIndexed { idx, c ->
                                                            put(
                                                                c,
                                                                com.kingzcheung.xime.keyboard.LetterKeyRect(
                                                                    (inset + idx) * 60f,
                                                                    r * 80f,
                                                                    60f,
                                                                    80f,
                                                                ),
                                                            )
                                                        }
                                                    }
                                            }
                                            val kb = com.kingzcheung.xime.glide.PersonalGlideCalibration.applyOffsets(
                                                context.filesDir,
                                                "p",
                                                com.kingzcheung.xime.glide.GlideLexicon.keyboard(defaultKeys),
                                            )
                                            val syllables = com.kingzcheung.xime.glide.glideSyllables(
                                                com.kingzcheung.xime.glide.GlideLexicon.pinyin(context),
                                                dp,
                                            )
                                            val tune = com.kingzcheung.xime.glide.PersonalGlideCalibration.tuneFromHistory(
                                                context.filesDir,
                                                sKey,
                                                kb,
                                                syllables,
                                            )
                                            if (tune.updated) "调优完成（${tune.sampleCount} 句）：首选命中 ${tune.beforeTop1} → ${tune.afterTop1}"
                                            else "当前权重已是最优（${tune.sampleCount} 句回放首选命中 ${tune.beforeTop1}）"
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            glideStats = com.kingzcheung.xime.glide.PersonalGlideCalibration.stats(context.filesDir)
                                            tuningGlide = false
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    SettingsItem(
                        Icons.TwoTone.ContentCut,
                        "重置个人自学习校准数据",
                        "清除本机记住的滑行按键偏移、短语样本与联想自递进搭配",
                        onClick = { showResetDialog = true },
                    )
                })

                if (showReportDialog) {
                    AlertDialog(
                        onDismissRequest = { showReportDialog = false },
                        title = { Text("本机自递进校准数据") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(reportText, style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            tuneScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                                    .format(java.util.Date())
                                                val tmp = java.io.File(context.cacheDir, "cjime-self-learn-backup-$stamp.zip")
                                                val summary = com.kingzcheung.xime.glide.PersonalGlideCalibration
                                                    .exportFullBackupZip(context.filesDir, tmp)
                                                val res = com.kingzcheung.xime.settings.RimeExportManager
                                                    .exportPreparedFile(context, tmp, "application/zip")
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    val err = res.exceptionOrNull()
                                                    if (res.isSuccess) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "已导出完整备份（${summary.fileCount} 份数据 / ${summary.recordCount} 条）至「下载/${res.getOrNull()?.fileName}」",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    } else if (err is com.kingzcheung.xime.settings.ExportSaveException) {
                                                        com.kingzcheung.xime.settings.RimeExportManager.shareSingleFile(context, err.kept)
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "导出失败：${err?.message ?: "未知错误"}",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                }
                                            }
                                        },
                                    ) { Text("导出完整备份 (.zip)") }
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            tuneScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                                    .format(java.util.Date())
                                                val tmp = java.io.File(context.cacheDir, "cjime-glide-sanitized-$stamp.jsonl.gz")
                                                val summary = com.kingzcheung.xime.glide.PersonalGlideCalibration
                                                    .exportSanitizedSamplesJsonlGz(context.filesDir, tmp)
                                                if (summary.recordCount == 0) {
                                                    tmp.delete()
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "暂无可导出的滑行轨迹样本（先多滑几句再导出）",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                    return@launch
                                                }
                                                val res = com.kingzcheung.xime.settings.RimeExportManager
                                                    .exportPreparedFile(context, tmp, "application/gzip")
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    val err = res.exceptionOrNull()
                                                    if (res.isSuccess) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "已导出脱敏轨迹 ${summary.recordCount} 条（不含汉字原文）至「下载/${res.getOrNull()?.fileName}」",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    } else if (err is com.kingzcheung.xime.settings.ExportSaveException) {
                                                        com.kingzcheung.xime.settings.RimeExportManager.shareSingleFile(context, err.kept)
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "导出失败：${err?.message ?: "未知错误"}",
                                                            android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                                }
                                            }
                                        },
                                    ) { Text("导出脱敏样本") }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("CJIME 自学习报告", reportText))
                                android.widget.Toast.makeText(context, "已复制校准报告到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("复制报告") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReportDialog = false }) { Text("关闭") }
                        },
                    )
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("清空个人自学习校准数据？") },
                        text = { Text("将清除本机记住的按键偏移、个人滑行短语、历史样本以及联想/局部改错自递进记录，恢复默认权重。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showResetDialog = false
                                com.kingzcheung.xime.glide.PersonalGlideCalibration.clear(context.filesDir)
                                com.kingzcheung.xime.association.PersonalAssociationBooster.clear(context.filesDir)
                                glideStats = com.kingzcheung.xime.glide.PersonalGlideCalibration.stats(context.filesDir)
                                assocStats = com.kingzcheung.xime.association.PersonalAssociationBooster.stats(context.filesDir)
                            }) { Text("清空") }
                        },
                        dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssociationModeRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.settingsSearchAnchor(title)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}

@Composable
internal fun LearningDataControls(
    unique: Int, observations: Long, profileSize: Int, busy: Boolean, status: String,
    onSave: () -> Unit, onRefresh: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    SettingsSection(title = "用户学习数据") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本机学习：$unique 种组合 · 累计 $observations 次")
            Text("个人语料方案：$profileSize 条联想", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("打字时自动学习，可导入导出。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Stack actions so large fonts and narrow keyboards cannot clip labels.
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出文件") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入学习文件") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
