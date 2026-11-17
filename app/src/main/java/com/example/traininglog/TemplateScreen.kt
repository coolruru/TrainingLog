@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.traininglog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TemplateScreen(
    vm: TrainingViewModel,
    onEditTemplate: (WorkoutTemplateWithExercises) -> Unit
) {
    val templates by vm.templates.collectAsState()
    if (templates.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有模板。在“训练”页配置好动作后点“保存模板”。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(templates) { t ->
            TemplateRow(
                template = t,
                onOpen = { onEditTemplate(t) },
                onDelete = { vm.deleteTemplate(t.template.id) },
                onDuplicate = { vm.duplicateTemplate(t) }
            )
        }
    }
}

@Composable
private fun TemplateRow(
    template: WorkoutTemplateWithExercises,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(template.template.createdAt))
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(template.template.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${template.template.bodyPart} · ${template.template.goal}｜${template.exercises.size} 动作｜$dateStr",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Text("⋮") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("删除") }, onClick = { showMenu = false; onDelete() })
                    DropdownMenuItem(text = { Text("复制") }, onClick = { showMenu = false; onDuplicate() })
                }
            }
        }
    }
}

@Composable
fun TemplateEditScreen(
    vm: TrainingViewModel,
    initial: WorkoutTemplateWithExercises,
    onDone: () -> Unit
) {
    BackHandler { onDone() }
    val scope = rememberCoroutineScope()
    var bodyPart by remember { mutableStateOf(initial.template.bodyPart) }
    var goal by remember { mutableStateOf(initial.template.goal) }
    var exercises by remember {
        mutableStateOf(initial.exercises.map {
            ExercisePlan(it.exerciseName, it.weight, it.plannedReps, it.totalSets, it.restSeconds, it.notes)
        })
    }
    var showPicker by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑模板") },
                navigationIcon = { TextButton(onClick = onDone) { Text("取消") } },
                actions = { TextButton(onClick = { showNameDialog = true }) { Text("保存") } }
            )
        }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BODY_PARTS.forEach { b ->
                        FilterChip(selected = bodyPart == b, onClick = { bodyPart = b }, label = { Text(b) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TRAINING_GOALS.forEach { g ->
                        FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(g) })
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        FilledIconButton(onClick = { showPicker = true }) { Text("+") }
                    }
                }
                items(exercises.size) { i ->
                    val ep = exercises[i]
                    TemplateExerciseCard(
                        exercise = ep,
                        index = i,
                        totalCount = exercises.size,
                        onChange = { u -> exercises = exercises.toMutableList().also { it[i] = u } },
                        onMove = { from, to ->
                            val list = exercises.toMutableList()
                            val item = list.removeAt(from)
                            list.add(to, item)
                            exercises = list
                        }
                    )
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    if (showPicker) {
        TemplateExercisePickerDialog(
            vm = vm,
            bodyPart = bodyPart,
            existingNames = exercises.map { it.exerciseName }.toSet(),
            onDismiss = { showPicker = false },
            onConfirm = { names ->
                exercises = exercises + names.map { ExercisePlan(it, 0.0, 8, 5, 90, "") }
                names.forEach { n -> scope.launch { vm.ensureLibraryItem(bodyPart, n) } }
                showPicker = false
            }
        )
    }
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("另存为新模板") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("新模板名称") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        scope.launch {
                            vm.saveTemplate(newName, bodyPart, goal, exercises)
                            showNameDialog = false
                            onDone()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TemplateExercisePickerDialog(
    vm: TrainingViewModel,
    bodyPart: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allLibrary by vm.allLibrary.collectAsState()
    val library = remember(allLibrary, bodyPart) { allLibrary.filter { it.bodyPart == bodyPart } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deleteTarget by remember { mutableStateOf<ExerciseLibraryItem?>(null) }
    var custom by remember { mutableStateOf("") }

    Box {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择动作（$bodyPart）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(library, key = { it.id }) { item ->
                            val isExisting = item.name in existingNames
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        enabled = !isExisting,
                                        onClick = {
                                            selected = if (item.name in selected) selected - item.name else selected + item.name
                                        }
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.name in selected || isExisting,
                                    enabled = !isExisting,
                                    onCheckedChange = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.name + if (isExisting) "（已添加）" else "",
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { deleteTarget = item }) {
                                    Text("✕", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = custom, onValueChange = { custom = it },
                            label = { Text("添加自定义动作") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val t = custom.trim()
                            if (t.isNotEmpty()) {
                                scope.launch {
                                    val ok = vm.addLibraryItem(bodyPart, t)
                                    if (ok) {
                                        if (t !in selected) selected = selected + t
                                        custom = ""
                                    } else snackbar.showSnackbar("该动作已存在")
                                }
                            }
                        }) { Text("添加") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(selected.toList()) }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除动作") },
            text = { Text("从动作库删除“${item.name}”？历史训练记录将保留。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteLibraryItem(item.id)
                    selected = selected - item.name
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TemplateExerciseCard(
    exercise: ExercisePlan,
    index: Int,
    totalCount: Int,
    onChange: (ExercisePlan) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    var weightStr by remember(exercise.exerciseName) { mutableStateOf(if (exercise.weight == 0.0) "" else exercise.weight.toString()) }
    var setsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.totalSets.toString()) }
    var repsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.plannedReps.toString()) }
    var restStr by remember(exercise.exerciseName) { mutableStateOf(exercise.restSeconds.toString()) }
    val density = LocalDensity.current
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exercise.exerciseName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).pointerInput(index, totalCount) {
                        val thresholdPx = with(density) { 60.dp.toPx() }
                        var totalDrag = 0f
                        detectDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount.y
                            },
                            onDragEnd = {
                                val rows = (totalDrag / thresholdPx).roundToInt()
                                if (rows != 0) {
                                    val target = (index + rows).coerceIn(0, totalCount - 1)
                                    if (target != index) onMove(index, target)
                                }
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    },
                    contentAlignment = Alignment.Center
                ) { Text("≡", style = MaterialTheme.typography.titleLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = weightStr,
                    onValueChange = { weightStr = it; onChange(exercise.copy(weight = it.toDoubleOrNull() ?: 0.0)) },
                    label = { Text("重量") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.4f))
                OutlinedTextField(value = setsStr,
                    onValueChange = { setsStr = it; onChange(exercise.copy(totalSets = it.toIntOrNull() ?: 0)) },
                    label = { Text("组数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
                OutlinedTextField(value = repsStr,
                    onValueChange = { repsStr = it; onChange(exercise.copy(plannedReps = it.toIntOrNull() ?: 0)) },
                    label = { Text("次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
                OutlinedTextField(value = restStr,
                    onValueChange = { restStr = it; onChange(exercise.copy(restSeconds = it.toIntOrNull() ?: 0)) },
                    label = { Text("休息s") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
            }
            OutlinedTextField(value = exercise.notes, onValueChange = { onChange(exercise.copy(notes = it)) },
                label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
