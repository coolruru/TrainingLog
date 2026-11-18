package com.example.traininglog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = AppDatabase.get(this).dao()
        val store = ThemeStore(this)
        setContent {
            var themeMode by remember { mutableStateOf(store.get()) }
            AppTheme(themeMode) {
                val vm: TrainingViewModel = viewModel(factory = TrainingViewModelFactory(dao))
                MainScreen(vm, themeMode, onThemeModeChange = { themeMode = it; store.set(it) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: TrainingViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val activeWorkout by vm.activeWorkout.collectAsState()

    var sessionDetail by remember { mutableStateOf<SessionWithExercises?>(null) }
    var historyEditSession by remember { mutableStateOf<SessionWithExercises?>(null) }
    var addRecordPlan by remember { mutableStateOf<WorkoutPlan?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<WorkoutTemplateWithExercises?>(null) }
    var homeSnackbar by remember { mutableStateOf("") }

    if (sessionDetail != null) {
        SessionDetailScreen(
            s = sessionDetail!!,
            onBack = { sessionDetail = null },
            onEditHistory = { historyEditSession = sessionDetail },
            onSavedAsTemplate = { homeSnackbar = it }
        )
        return
    }

    if (historyEditSession != null) {
        HistoryEditScreen(
            vm = vm,
            session = historyEditSession!!,
            onBack = { historyEditSession = null; sessionDetail = null }
        )
        return
    }

    if (addRecordPlan != null) {
        AddRecordScreen(
            plan = addRecordPlan!!,
            onCancel = { addRecordPlan = null },
            onConfirm = { date, durationSecs ->
                val plan = addRecordPlan!!.copy(date = date, durationOverrideSeconds = durationSecs)
                vm.addRecordDirectly(plan)
                addRecordPlan = null
            }
        )
        return
    }

    if (helpOpen) {
        HelpScreen(onBack = { helpOpen = false })
        return
    }

    if (editingTemplate != null) {
        TemplateEditScreen(vm, editingTemplate!!, onDone = { editingTemplate = null })
        return
    }

    var tab by remember { mutableStateOf(0) }
    val titles = listOf("训练", "历史", "器械", "模板", "设置")
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(homeSnackbar) {
        if (homeSnackbar.isNotEmpty()) {
            snackbar.showSnackbar(homeSnackbar)
            homeSnackbar = ""
        }
    }

    if (tab != 0) {
        BackHandler { tab = 0 }
    }

    var topMenuOpen by remember { mutableStateOf(false) }
    var equipmentShowAdd by remember { mutableStateOf(false) }
    var templateShowAdd by remember { mutableStateOf(false) }

    val showWorkoutTopBar = tab == 0 && activeWorkout != null

    Scaffold(
        topBar = {
            if (!showWorkoutTopBar) {
                TopAppBar(
                    title = { Text(titles.getOrElse(tab) { "训练" }) },
                    actions = {
                        if (tab in 1..3) {
                            IconButton(onClick = { topMenuOpen = true }) {
                                Text("⋮", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                                val label = when (tab) {
                                    1 -> "添加记录（去训练页）"
                                    2 -> "添加器械"
                                    3 -> "新建模板"
                                    else -> ""
                                }
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        topMenuOpen = false
                                        when (tab) {
                                            1 -> tab = 0
                                            2 -> equipmentShowAdd = true
                                            3 -> templateShowAdd = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t) },
                        label = { Text(t) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> {
                    if (activeWorkout != null) {
                        WorkoutSessionScreen(vm = vm, active = activeWorkout!!, embedded = true)
                    } else {
                        TrainingPlanScreen(
                            vm = vm,
                            onOpenAddRecord = { addRecordPlan = it }
                        )
                    }
                }
                1 -> HistoryScreen(vm = vm, onOpenDetail = { sessionDetail = it })
                2 -> EquipmentScreen(
                    vm = vm,
                    showAddDialog = equipmentShowAdd,
                    onShowAddConsumed = { equipmentShowAdd = false }
                )
                3 -> TemplateScreen(
                    vm = vm,
                    onEditTemplate = { editingTemplate = it },
                    showAddDialog = templateShowAdd,
                    onShowAddConsumed = { templateShowAdd = false }
                )
                4 -> SettingsScreen(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onOpenHelp = { helpOpen = true }
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    vm: TrainingViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenHelp: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }
    val createJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = vm.exportToJson(ctx, uri, null, null, emptyList())
            snackbar.showSnackbar(if (ok) "导出成功" else "导出失败")
        }
    }
    val createZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val ok = vm.exportToZip(ctx, uri, null, null, emptyList())
            snackbar.showSnackbar(if (ok) "导出成功" else "导出失败")
        }
    }

    LaunchedEffect(importUri) {
        val u = importUri ?: return@LaunchedEffect
        val res = vm.importFrom(ctx, u)
        snackbar.showSnackbar(
            if (res.success) "导入完成：${res.sessions}训练 / ${res.templates}模板 / ${res.profiles}器械"
            else "导入失败：${res.message}"
        )
        importUri = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = themeMode == m, onClick = { onThemeModeChange(m) })
                    Text(m.label)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("数据备份与恢复", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { createJsonLauncher.launch("training_backup_${System.currentTimeMillis()}.json") }) { Text("导出 JSON") }
                OutlinedButton(onClick = { createZipLauncher.launch("training_backup_${System.currentTimeMillis()}.zip") }) { Text("导出 ZIP") }
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/zip", "*/*")) }) { Text("导入 JSON / ZIP") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("帮助", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenHelp) { Text("查看使用说明") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("版本", style = MaterialTheme.typography.titleMedium)
            Text("训练记录 2.5 · 计时 · 模板 · 导入导出 · 动作库 · 蓝线计时", style = MaterialTheme.typography.bodySmall)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("使用说明") }) }) { p ->
        Column(
            Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("重量输入", style = MaterialTheme.typography.titleMedium)
            Text("空或 0 = 自重；正数 = 负重（kg）；负数 = 助力（kg）。")
            Text("显示时负数动作名前自动加“助力”，如“助力引体”。")
            Text("训练流程", style = MaterialTheme.typography.titleMedium)
            Text("训练页 → 选部位 + 目标 → 选动作 → 填参数 → 开始训练 / 添加记录 / 保存模板。")
            Text("开始训练后计时界面显示在训练 tab 内，可继续切底部其他 tab。")
            Text("方块灰 → 橙 → 绿；长按橙色或绿色方块可标记未完成（只填实际次数）。")
            Text("组间休息", style = MaterialTheme.typography.titleMedium)
            Text("蓝线从左向右填充，休息结束抖动；点下一个方块立即停止并记录到上一组。")
            Text("历史编辑", style = MaterialTheme.typography.titleMedium)
            Text("历史详情页顶部“编辑”按钮可进入历史编辑：编辑每组参数、删组、删动作、加组、加动作。")
            Text("导出", style = MaterialTheme.typography.titleMedium)
            Text("导出统一在设置页：JSON / ZIP 可导入；Markdown 只用于查看。")
            Spacer(Modifier.height(40.dp))
        }
    }
}
