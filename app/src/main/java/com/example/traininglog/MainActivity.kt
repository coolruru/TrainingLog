package com.example.traininglog

import android.content.Intent
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
import java.io.File

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

    if (activeWorkout != null) {
        WorkoutSessionScreen(vm = vm, active = activeWorkout!!)
        return
    }

    var tab by remember { mutableStateOf(0) }
    val titles = listOf("训练", "历史", "器械", "模板", "设置")
    var historyMenuOpen by remember { mutableStateOf(false) }

    if (tab != 0) {
        BackHandler { tab = 0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles.getOrElse(tab) { "训练" }) },
                navigationIcon = {
                    if (tab == 1) {
                        IconButton(onClick = { historyMenuOpen = true }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            )
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
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> TrainingPlanScreen(vm)
                1 -> HistoryScreen(vm)
                2 -> EquipmentScreen(vm)
                3 -> TemplateScreen(vm)
                4 -> SettingsScreen(vm, themeMode, onThemeModeChange)
            }
        }
    }

    if (historyMenuOpen) {
        AlertDialog(
            onDismissRequest = { historyMenuOpen = false },
            title = { Text("数据") },
            text = { Text("请到历史页导出/导入。") },
            confirmButton = { TextButton(onClick = { historyMenuOpen = false }) { Text("关闭") } }
        )
    }
}

@Composable
fun SettingsScreen(
    vm: TrainingViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showHelp by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    BackHandler(enabled = showHelp) { showHelp = false }

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

    if (showHelp) {
        HelpScreen(onBack = { showHelp = false })
        return
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
                OutlinedButton(onClick = {
                    createJsonLauncher.launch("training_backup_${System.currentTimeMillis()}.json")
                }) { Text("导出 JSON") }
                OutlinedButton(onClick = {
                    createZipLauncher.launch("training_backup_${System.currentTimeMillis()}.zip")
                }) { Text("导出 ZIP") }
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "application/zip", "*/*"))
            }) { Text("导入 JSON / ZIP") }
            Text(
                "Markdown 导出在历史页左上角菜单里。JSON/ZIP 用于数据备份与恢复。",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("帮助", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showHelp = true }) { Text("查看使用说明") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("动作库", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { vm.restoreDefaultLibrary() }) { Text("恢复默认动作库") }
            Text("只补回默认动作，不删除你已有的自定义动作。", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("版本", style = MaterialTheme.typography.titleMedium)
            Text("训练记录 2.2 · 计时 · 模板 · 导入导出 · 动作库", style = MaterialTheme.typography.bodySmall)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("使用说明") }
        )
    }) { p ->
        Column(
            Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("重量输入", style = MaterialTheme.typography.titleMedium)
            Text("空或 0 = 自重；正数 = 负重（kg）；负数 = 助力（kg）。")
            Text("例：卧推写 80 → 80kg；引体写 -40 → 助力40kg；俯卧撑写 0 → 自重。")
            Text("显示时负数动作名前自动加“助力”，如“助力引体”。")

            Text("训练流程", style = MaterialTheme.typography.titleMedium)
            Text("训练页 → 选部位 + 目标 → 选动作 → 填重量/组数/次数/组间休息 → 开始训练 / 添加记录 / 保存模板。")
            Text("点“开始训练”进入计时界面：方块灰 → 橙（本组计时）→ 绿（完成）；长按橙色方块 → 红（未完成，填实际次数+力竭次数）。")

            Text("组间休息", style = MaterialTheme.typography.titleMedium)
            Text("每组结束后，方块之间出现杯子：满 → 空 → 摇。点下一个方块立刻停止杯子，并记录本段实际休息时长到上一组。")
            Text("同一时刻只允许一个方块在计时；同一动作必须按组顺序，不能跳过未完成组开始下一组。")

            Text("核心附加", style = MaterialTheme.typography.titleMedium)
            Text("部位选“核心”时，动作会合并到当天已有训练里，显示在记录末尾“训练后核心附加”区域。")

            Text("模板与记录", style = MaterialTheme.typography.titleMedium)
            Text("训练页可直接“从模板开始”。模板在“模板”Tab 管理，支持删除、复制（自动原名+编号）、编辑另存为新。")
            Text("历史记录长按可“删除”或“保存为模板”。")

            Text("数据备份", style = MaterialTheme.typography.titleMedium)
            Text("历史页左上角菜单、设置页都可以导入/导出。JSON 与 ZIP 可双向导入；Markdown 只用于查看与分享，不作为导入源。")

            Spacer(Modifier.height(40.dp))
        }
    }
}
