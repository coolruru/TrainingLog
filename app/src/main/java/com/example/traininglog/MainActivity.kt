package com.example.traininglog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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
                MainScreen(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it; store.set(it) }
                )
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

    var historyExportOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles.getOrElse(tab) { "训练" }) },
                navigationIcon = {
                    if (tab == 1) {
                        IconButton(onClick = { historyExportOpen = true }) {
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
                0 -> TrainingPlanScreen(vm = vm)
                1 -> {
                    HistoryScreen(vm = vm)
                    if (historyExportOpen) {
                        ExportDialog(vm = vm, onDismiss = { historyExportOpen = false })
                    }
                }
                2 -> EquipmentScreen(vm = vm)
                3 -> TemplateScreen(vm = vm)
                4 -> SettingsScreen(themeMode, onThemeModeChange)
            }
        }
    }
}

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
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
        Text("数据存储", style = MaterialTheme.typography.titleMedium)
        Text("训练记录存本机 App 私有数据库；器械照片存 App 私有目录。", style = MaterialTheme.typography.bodySmall)
        Text("覆盖安装不丢，卸载/清 App 数据才丢。建议定期导出 Markdown 备份。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("版本", style = MaterialTheme.typography.titleMedium)
        Text("训练记录 2.0 · 计时 · 模板 · 器械 · 暗黑模式", style = MaterialTheme.typography.bodySmall)
    }
}
