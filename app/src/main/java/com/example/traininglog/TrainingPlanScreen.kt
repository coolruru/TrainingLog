@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.traininglog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrainingPlanScreen(
    vm: TrainingViewModel,
    onOpenAddRecord: (WorkoutPlan) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val templates by vm.templates.collectAsState()
    val events by vm.events.collectAsState(initial = "")

    var selectedBodyPart by remember { mutableStateOf<String?>(null) }
    var selectedGoal by remember { mutableStateOf<String?>(null) }
    var exercises by remember { mutableStateOf(listOf<ExercisePlan>()) }
    var sessionNotes by remember { mutableStateOf("") }
    var showExercisePicker by remember { mutableStateOf(false) }
    var showTemplateNameDialog by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) { if (events.isNotEmpty()) snackbar.showSnackbar(events) }

    val showActionArea = selectedBodyPart != null && selectedGoal != null
    val allFilled = exercises.isNotEmpty() && exercises.all { it.totalSets > 0 && it.plannedReps > 0 }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                if (!showActionArea) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("选择训练部位", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BODY_PARTS.forEach { p ->
                                FilterChip(
                                    selected = selectedBodyPart == p,
                                    onClick = { selectedBodyPart = p },
                                    label = { Text(p) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("选择训练目标", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TRAINING_GOALS.forEach { g ->
                                FilterChip(
                                    selected = selectedGoal == g,
                                    onClick = { selectedGoal = g },
                                    label = { Text(g) }
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("或", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { showTemplatePicker = true }) { Text("从模板开始") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedBodyPart} · ${selectedGoal}", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            selectedBodyPart = null
                            selectedGoal = null
                            exercises = emptyList()
                            sessionNotes = ""
                            showExercisePicker = false
                        }) { Text("重选") }
                    }
                }
            }

            if (showActionArea) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { showExercisePicker = !showExercisePicker }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        FilledIconButton(
                            onClick = { showExercisePicker = !showExercisePicker },
                            shape = CircleShape
                        ) {
                            Text(if (showExercisePicker) "−" else "+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = showExercisePicker,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ExerciseDropdownPanel(
                            vm = vm,
                            bodyPart = selectedBodyPart ?: "胸",
                            existingNames = exercises.map { it.exerciseName }.toSet(),
                            onCancel = { showExercisePicker = false },
                            onConfirm = { names ->
                                val added = names.filter { n -> exercises.none { it.exerciseName == n } }
                                exercises = exercises + added.map { ExercisePlan(it, 0.0, 8, 5, 90, "") }
                                added.forEach { name ->
                                    scope.launch { vm.ensureLibraryItem(selectedBodyPart ?: "胸", name) }
                                }
                                showExercisePicker = false
                            }
                        )
                    }
                }

                itemsIndexed(exercises) { i, ep ->
                    ExercisePlanCard(
                        exercise = ep,
                        index = i,
                        totalCount = exercises.size,
                        onChange = { u -> exercises = exercises.toMutableList().also { it[i] = u } },
                        onDelete = { exercises = exercises.toMutableList().also { it.removeAt(i) } },
                        onMove = { from, to ->
                            val list = exercises.toMutableList()
                            val item = list.removeAt(from)
                            list.add(to, item)
                            exercises = list
                        }
                    )
                }

                item {
                    OutlinedTextField(
                        value = sessionNotes, onValueChange = { sessionNotes = it },
                        label = { Text("训练备注（可选）") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showTemplateNameDialog = true },
                            enabled = allFilled,
                            modifier = Modifier.weight(1f)
                        ) { Text("保存模板") }
                        Button(
                            onClick = {
                                if (allFilled) {
                                    vm.startWorkout(WorkoutPlan(
                                        date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                                        bodyPart = selectedBodyPart!!,
                                        goal = selectedGoal!!,
                                        sessionNotes = sessionNotes,
                                        exercises = exercises
                                    ))
                                }
                            },
                            enabled = allFilled,
                            modifier = Modifier.weight(1f)
                        ) { Text("开始训练") }
                        OutlinedButton(
                            onClick = {
                                if (allFilled) {
                                    onOpenAddRecord(WorkoutPlan(
                                        date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                                        bodyPart = selectedBodyPart!!,
                                        goal = selectedGoal!!,
                                        sessionNotes = sessionNotes,
                                        exercises = exercises
                                    ))
                                }
                            },
                            enabled = allFilled,
                            modifier = Modifier.weight(1f)
                        ) { Text("添加记录") }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showTemplateNameDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateNameDialog = false },
            title = { Text("保存模板") },
            text = {
                OutlinedTextField(
                    value = templateName, onValueChange = { templateName = it },
                    label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (templateName.isNotBlank()) {
                        vm.saveTemplate(templateName, selectedBodyPart!!, selectedGoal!!, exercises)
                        showTemplateNameDialog = false
                        templateName = ""
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTemplateNameDialog = false }) { Text("取消") } }
        )
    }

    if (showTemplatePicker) {
        TemplatePickerSheet(
            templates = templates,
            onDismiss = { showTemplatePicker = false },
            onPick = { t ->
                selectedBodyPart = t.template.bodyPart
                selectedGoal = t.template.goal
                exercises = t.exercises.map {
                    ExercisePlan(it.exerciseName, it.weight, it.plannedReps, it.totalSets, it.restSeconds, it.notes)
                }
                showTemplatePicker = false
            }
        )
    }
}

@Composable
private fun ExerciseDropdownPanel(
    vm: TrainingViewModel,
    bodyPart: String,
    existingNames: Set<String>,
    onCancel: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allLibrary by vm.allLibrary.collectAsState()
    val library = remember(allLibrary, bodyPart) { allLibrary.filter { it.bodyPart == bodyPart } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deleteTarget by remember { mutableStateOf<ExerciseLibraryItem?>(null) }
    var custom by remember { mutableStateOf("") }

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                ) {
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(library, key = { it.id }) { item ->
                            val isExisting = item.name in existingNames
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        enabled = !isExisting,
                                        onClick = {
                                            selected = if (item.name in selected) selected - item.name
                                            else selected + item.name
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.name in selected || isExisting,
                                    enabled = !isExisting,
                                    onCheckedChange = null
                                )
                                Spacer(Modifier.width(6.dp))
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
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { onConfirm(selected.toList()) }, modifier = Modifier.weight(1f)) { Text("确认") }
                }
            }
        }
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
fun ExercisePlanCard(
    exercise: ExercisePlan,
    index: Int,
    totalCount: Int,
    onChange: (ExercisePlan) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    var weightStr by remember(exercise.exerciseName) { mutableStateOf(if (exercise.weight == 0.0) "" else exercise.weight.toString()) }
    var setsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.totalSets.toString()) }
    var repsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.plannedReps.toString()) }
    var restStr by remember(exercise.exerciseName) { mutableStateOf(exercise.restSeconds.toString()) }
    var showEquipmentPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var linkedProfile by remember { mutableStateOf<EquipmentProfile?>(null) }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val vm: TrainingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TrainingViewModelFactory(AppDatabase.get(ctx).dao())
    )

    LaunchedEffect(exercise.equipmentProfileId) {
        if (exercise.equipmentProfileId != null) {
            linkedProfile = vm.getAllProfiles().firstOrNull { it.id == exercise.equipmentProfileId }
        } else linkedProfile = null
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                OutlinedTextField(
                    value = weightStr,
                    onValueChange = {
                        weightStr = it
                        onChange(exercise.copy(weight = it.toDoubleOrNull() ?: 0.0))
                    },
                    label = { Text("重量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = setsStr,
                    onValueChange = {
                        setsStr = it
                        onChange(exercise.copy(totalSets = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("组数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f)
                )
                OutlinedTextField(
                    value = repsStr,
                    onValueChange = {
                        repsStr = it
                        onChange(exercise.copy(plannedReps = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f)
                )
                OutlinedTextField(
                    value = restStr,
                    onValueChange = {
                        restStr = it
                        onChange(exercise.copy(restSeconds = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("休息s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f)
                )
            }
            OutlinedTextField(
                value = exercise.notes,
                onValueChange = { onChange(exercise.copy(notes = it)) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showEquipmentPicker = true }) {
                    Text(linkedProfile?.let { "已关联：${it.equipmentName.ifBlank { it.exerciseName }}" } ?: "关联器械")
                }
                if (linkedProfile != null) {
                    TextButton(onClick = {
                        linkedProfile = null
                        onChange(exercise.copy(equipmentProfileId = null))
                    }) { Text("清除") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showEquipmentPicker) {
        ProfilePickerDialog(
            vm = vm,
            exerciseName = exercise.exerciseName,
            onDismiss = { showEquipmentPicker = false },
            onSelect = {
                linkedProfile = it
                onChange(exercise.copy(equipmentProfileId = it.id))
                showEquipmentPicker = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("移除动作") },
            text = { Text("确认从本次训练移除“${exercise.exerciseName}”？重量、组数、次数、休息、备注和关联器械将一并移除，不影响动作库和历史记录。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TemplatePickerSheet(
    templates: List<WorkoutTemplateWithExercises>,
    onDismiss: () -> Unit,
    onPick: (WorkoutTemplateWithExercises) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选择模板", style = MaterialTheme.typography.titleMedium)
            if (templates.isEmpty()) Text("还没有模板。先配置动作并保存模板。")
            else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(templates) { t ->
                    Card(onClick = { onPick(t) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(t.template.name, style = MaterialTheme.typography.titleSmall)
                            Text("${t.template.bodyPart} · ${t.template.goal}｜${t.exercises.size} 动作",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
