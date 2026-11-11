package com.example.traininglog

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HistoryScreen(
    vm: TrainingViewModel,
    onOpenDetail: (SessionWithExercises) -> Unit
) {
    val all by vm.sessions.collectAsState()
    var viewMode by remember { mutableStateOf(0) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var pendingSessionMenu by remember { mutableStateOf<SessionWithExercises?>(null) }
    var showTemplateNameDialog by remember { mutableStateOf<SessionWithExercises?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionWithExercises?>(null) }
    var templateName by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val res = vm.importFrom(ctx, uri)
                snackbar.showSnackbar(
                    if (res.success) "导入完成：${res.sessions}训练 / ${res.templates}模板 / ${res.profiles}器械"
                    else "导入失败：${res.message}"
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = viewMode) {
                Tab(selected = viewMode == 0, onClick = { viewMode = 0 }, text = { Text("日历") })
                Tab(selected = viewMode == 1, onClick = { viewMode = 1 }, text = { Text("列表") })
            }
            if (viewMode == 0) {
                CalendarView(all, onPickSession = { onOpenDetail(it) }, onLongPressSession = { pendingSessionMenu = it })
            } else {
                ListView(all, onPickSession = { onOpenDetail(it) }, onLongPressSession = { pendingSessionMenu = it })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showExportMenu) {
        AlertDialog(
            onDismissRequest = { showExportMenu = false },
            title = { Text("数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showExportMenu = false; showExportDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("导出") }
                    TextButton(onClick = {
                        showExportMenu = false
                        importLauncher.launch(arrayOf("application/json", "application/zip", "*/*"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("导入 JSON / ZIP") }
                }
            },
            confirmButton = { TextButton(onClick = { showExportMenu = false }) { Text("关闭") } }
        )
    }

    if (showExportDialog) {
        ExportDialog(vm) { showExportDialog = false }
    }

    pendingSessionMenu?.let { s ->
        Dialog(onDismissRequest = { pendingSessionMenu = null }) {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("${s.session.date}｜${s.session.bodyPart}", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    HorizontalDivider()
                    TextButton(onClick = { showTemplateNameDialog = s; pendingSessionMenu = null }, modifier = Modifier.fillMaxWidth()) { Text("保存为模板") }
                    TextButton(onClick = { pendingDelete = s; pendingSessionMenu = null }, modifier = Modifier.fillMaxWidth()) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { pendingSessionMenu = null }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除训练记录") },
            text = { Text("确认删除 ${s.session.date}｜${s.session.bodyPart} 的训练记录？删除后不可恢复。") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(s.session.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    showTemplateNameDialog?.let { s ->
        AlertDialog(
            onDismissRequest = { showTemplateNameDialog = null },
            title = { Text("保存为模板") },
            text = { OutlinedTextField(value = templateName, onValueChange = { templateName = it }, label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (templateName.isNotBlank()) {
                        vm.saveSessionAsTemplate(s, templateName)
                        scope.launch { snackbar.showSnackbar("已保存为模板") }
                        templateName = ""; showTemplateNameDialog = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTemplateNameDialog = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CalendarView(
    all: List<SessionWithExercises>,
    onPickSession: (SessionWithExercises) -> Unit,
    onLongPressSession: (SessionWithExercises) -> Unit
) {
    val today = remember { Calendar.getInstance() }
    var year by remember { mutableStateOf(today.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(today.get(Calendar.MONTH) + 1) }
    var level by remember { mutableStateOf(0) }
    var decadeStart by remember { mutableStateOf(year - 4) }
    var selectedDay by remember { mutableStateOf<Int?>(if (year == today.get(Calendar.YEAR) && month == today.get(Calendar.MONTH) + 1) today.get(Calendar.DAY_OF_MONTH) else null) }

    val monthCountMap = remember(all) {
        all.groupBy {
            val p = it.session.date.split("-"); (p[0].toIntOrNull() ?: 0) to (p[1].toIntOrNull() ?: 0)
        }.mapValues { (_, list) -> list.map { it.session.date }.distinct().size }
    }
    val yearCountMap = remember(all) {
        all.groupBy { it.session.date.split("-").getOrNull(0)?.toIntOrNull() ?: 0 }
            .mapValues { (_, list) -> list.map { it.session.date }.distinct().size }
    }
    val dayPartsMap = remember(all, year, month) {
        all.filter {
            val p = it.session.date.split("-")
            p.getOrNull(0)?.toIntOrNull() == year && p.getOrNull(1)?.toIntOrNull() == month
        }.groupBy { it.session.date.split("-").getOrNull(2)?.toIntOrNull() ?: 0 }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                when (level) {
                    0 -> { month--; if (month < 1) { month = 12; year-- }; selectedDay = null }
                    1 -> year--
                    2 -> decadeStart -= 9
                }
            }) { Text("‹") }
            Text(
                when (level) { 0 -> "${year}年${month}月"; 1 -> "${year}年"; else -> "" },
                modifier = Modifier.clickable {
                    when (level) { 0 -> level = 1; 1 -> { decadeStart = year - 4; level = 2 } }
                },
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = {
                when (level) {
                    0 -> { month++; if (month > 12) { month = 1; year++ }; selectedDay = null }
                    1 -> year++
                    2 -> decadeStart += 9
                }
            }) { Text("›") }
        }

        when (level) {
            0 -> MonthGrid(year, month, dayPartsMap, selectedDay) { selectedDay = it }
            1 -> YearGrid(year, monthCountMap) { m -> month = m; selectedDay = null; level = 0 }
            else -> DecadeGrid(decadeStart, yearCountMap) { y -> year = y; level = 1 }
        }

        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TextButton(onClick = {
                year = today.get(Calendar.YEAR); month = today.get(Calendar.MONTH) + 1
                selectedDay = today.get(Calendar.DAY_OF_MONTH); level = 0
            }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("今") }
        }

        if (level == 0 && selectedDay != null) {
            val list = dayPartsMap[selectedDay].orEmpty()
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (list.isEmpty()) {
                Text("${month}月${selectedDay}日 · 休", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    items(list) { s ->
                        SessionSummaryRow(s, onClick = { onPickSession(s) }, onLongClick = { onLongPressSession(s) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int, month: Int,
    dayPartsMap: Map<Int, List<SessionWithExercises>>,
    selectedDay: Int?,
    onPickDay: (Int) -> Unit
) {
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, 1)
    }
    val firstDow = cal.get(Calendar.DAY_OF_WEEK)
    val offset = (firstDow + 5) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")
    Column {
        Row(Modifier.fillMaxWidth()) {
            weekNames.forEach { w ->
                Text(w, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
        val total = offset + days
        val rows = (total + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0..6) {
                    val idx = r * 7 + c
                    val dayNum = idx - offset + 1
                    if (dayNum in 1..days) {
                        val isTraining = (dayPartsMap[dayNum]?.isNotEmpty() == true)
                        val isSelected = selectedDay == dayNum
                        val bg = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isTraining -> Color(0xFFFFB74D)
                            else -> Color.Transparent
                        }
                        val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                                .clip(RoundedCornerShape(6.dp)).background(bg)
                                .clickable { onPickDay(dayNum) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$dayNum", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (!isTraining) Text("休", color = textColor, fontSize = 9.sp)
                            }
                        }
                    } else Spacer(Modifier.weight(1f).aspectRatio(1f).padding(3.dp))
                }
            }
        }
    }
}

@Composable
private fun YearGrid(year: Int, monthCountMap: Map<Pair<Int, Int>, Int>, onPickMonth: (Int) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items((1..12).toList()) { m ->
            val count = monthCountMap[year to m] ?: 0
            val bg = orangeByCount(count)
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(bg)
                .clickable { onPickMonth(m) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${m}月", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    if (count > 0) Text("${count}次", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun DecadeGrid(decadeStart: Int, yearCountMap: Map<Int, Int>, onPickYear: (Int) -> Unit) {
    val years = (decadeStart..(decadeStart + 8)).toList()
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(years) { y ->
            val count = yearCountMap[y] ?: 0
            val bg = orangeByCount(count)
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(bg)
                .clickable { onPickYear(y) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$y", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    if (count > 0) Text("${count}次", fontSize = 10.sp)
                }
            }
        }
    }
}

private fun orangeByCount(count: Int): Color = when {
    count == 0 -> Color.Transparent
    count <= 5 -> Color(0xFFFFE0B2)
    count <= 10 -> Color(0xFFFFB74D)
    count <= 15 -> Color(0xFFFB8C00)
    else -> Color(0xFFE65100)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionSummaryRow(s: SessionWithExercises, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.session.bodyPart, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(48.dp))
            Text(s.session.goal.ifBlank { "—" }, modifier = Modifier.width(56.dp))
            Text("${s.exercises.size}动作", modifier = Modifier.width(64.dp))
            Text(if (s.session.durationSeconds > 0) formatDuration(s.session.durationSeconds) else "—")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListView(
    all: List<SessionWithExercises>,
    onPickSession: (SessionWithExercises) -> Unit,
    onLongPressSession: (SessionWithExercises) -> Unit
) {
    val sorted = remember(all) {
        all.sortedWith(compareByDescending<SessionWithExercises> { it.session.date }.thenByDescending { it.session.createdAt })
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(sorted) { s ->
            Card(modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = { onPickSession(s) },
                onLongClick = { onLongPressSession(s) }
            )) {
                Column(Modifier.padding(10.dp)) {
                    Text(s.session.date, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text("${s.session.bodyPart}｜${s.session.goal.ifBlank { "—" }}｜${s.exercises.size}动作｜${if (s.session.durationSeconds > 0) formatDuration(s.session.durationSeconds) else "—"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ===================== 详情页（独立全屏，由 MainScreen 调用） =====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    s: SessionWithExercises,
    onBack: () -> Unit,
    onSavedAsTemplate: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    var showMenu by remember { mutableStateOf(false) }
    var showTemplateNameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    val vm: TrainingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TrainingViewModelFactory(AppDatabase.get(androidx.compose.ui.platform.LocalContext.current).dao())
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("${s.session.date}｜${s.session.bodyPart}") },
            actions = {
                TextButton(onClick = { showMenu = true }) { Text("⋯") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("保存为模板") }, onClick = {
                        showMenu = false; showTemplateNameDialog = true
                    })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                        showMenu = false; showDeleteConfirm = true
                    })
                }
            }
        )
    }) { p ->
        LazyColumn(
            Modifier.padding(p).fillMaxSize().padding(horizontal = 16.dp)
        ) {
            item {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("目标", s.session.goal.ifBlank { "—" })
                    if (s.session.durationSeconds > 0) InfoRow("时长", formatDuration(s.session.durationSeconds))
                    if (s.session.notes.isNotBlank()) InfoRow("备注", s.session.notes)
                    s.painRecords.forEach { pr ->
                        InfoRow("疼痛", "${pr.location} ${pr.score}/10${if (pr.note.isNotBlank()) "（${pr.note}）" else ""}",
                            valueColor = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
            items(s.exercises) { ex ->
                Column(Modifier.padding(vertical = 12.dp)) {
                    val name = if (ex.exercise.weight < 0) "助力${ex.exercise.exerciseName}" else ex.exercise.exerciseName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${displayLoad(ex.exercise.weight)} × ${ex.exercise.plannedReps} × ${ex.exercise.totalSets}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ex.exercise.restSeconds > 0)
                        Text("组间休息 ${ex.exercise.restSeconds}s", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ex.exercise.notes.isNotBlank())
                        Text("备注：${ex.exercise.notes}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ex.equipmentProfile?.let { pr ->
                        Text("器械：${pr.equipmentName.ifBlank { pr.exerciseName }}（${ex.gymName}）",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    ex.sets.forEach { set ->
                        val mark = if (set.completed) "✓" else "✗"
                        val f = if (!set.completed) " 力竭${set.failureReps}" else ""
                        val d = if (set.durationSeconds > 0) " · ${set.durationSeconds}s" else ""
                        val r = if (set.restTakenSeconds > 0) " · 休${set.restTakenSeconds}s" else ""
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("第${set.setIndex}组", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                            Text("$mark ${set.actualReps}次$f$d$r", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (showTemplateNameDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateNameDialog = false },
            title = { Text("保存为模板") },
            text = { OutlinedTextField(value = templateName, onValueChange = { templateName = it }, label = { Text("模板名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (templateName.isNotBlank()) {
                        vm.saveSessionAsTemplate(s, templateName)
                        showTemplateNameDialog = false
                        onSavedAsTemplate("已保存为模板")
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTemplateNameDialog = false }) { Text("取消") } }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除训练记录") },
            text = { Text("确认删除此训练记录？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(s.session.id)
                    showDeleteConfirm = false
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
fun ExportDialog(vm: TrainingViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var selectedParts by remember { mutableStateOf(setOf<String>()) }
    var format by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val createJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = vm.exportToJson(ctx, uri, from.ifBlank { null }, to.ifBlank { null }, selectedParts.toList())
            busy = false
            if (ok) onDismiss() else snackbar.showSnackbar("导出失败")
        }
    }
    val createZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = vm.exportToZip(ctx, uri, from.ifBlank { null }, to.ifBlank { null }, selectedParts.toList())
            busy = false
            if (ok) onDismiss() else snackbar.showSnackbar("导出失败")
        }
    }
    val createMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val sessions = vm.exportSessions(from.ifBlank { null }, to.ifBlank { null }, selectedParts.toList())
                val md = MarkdownExporter.build(sessions,
                    rangeDesc = if (from.isBlank() && to.isBlank()) "全部" else "${from.ifBlank { "开始" }} ~ ${to.ifBlank { "至今" }}",
                    partDesc = if (selectedParts.isEmpty()) "全部" else selectedParts.joinToString("、"))
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) }
                onDismiss()
            } finally { busy = false }
        }
    }

    Box {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导出") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("格式")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = format == 0, onClick = { format = 0 }, label = { Text("Markdown") })
                        FilterChip(selected = format == 1, onClick = { format = 1 }, label = { Text("JSON") })
                        FilterChip(selected = format == 2, onClick = { format = 2 }, label = { Text("ZIP+照片") })
                    }
                    Text("日期范围（可空）", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(from, { from = it }, label = { Text("开始") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(to, { to = it }, label = { Text("结束") }, modifier = Modifier.fillMaxWidth())
                    Text("部位（不选 = 全部）")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BODY_PARTS.forEach { p ->
                            FilterChip(selected = p in selectedParts,
                                onClick = { selectedParts = if (p in selectedParts) selectedParts - p else selectedParts + p },
                                label = { Text(p) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    val stamp = System.currentTimeMillis()
                    when (format) {
                        0 -> createMdLauncher.launch("训练记录_$stamp.md")
                        1 -> createJsonLauncher.launch("training_backup_$stamp.json")
                        2 -> createZipLauncher.launch("training_backup_$stamp.zip")
                    }
                }) { Text(if (busy) "处理中..." else "导出") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
