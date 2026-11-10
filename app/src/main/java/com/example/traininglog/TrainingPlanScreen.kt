package com.example.traininglog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val templates by vm.templates.collectAsState()

    var selectedBodyPart by remember { mutableStateOf<String?>(null) }
    var selectedGoal by remember { mutableStateOf<String?>(null) }
    var exercises by remember { mutableStateOf(listOf<ExercisePlan>()) }
    var sessionNotes by remember { mutableStateOf("") }
    var showExercisePicker by remember { mutableStateOf(false) }
    var showTemplateNameDialog by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }

    val showActionArea = selectedBodyPart != null && selectedGoal != null
    val allFilled = exercises.isNotEmpty() && exercises.all { it.totalSets > 0 && it.plannedReps > 0 }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(if (showActionArea) 12.dp else 0.dp)
                .then(if (!showActionArea) Modifier.fillMaxHeight(0.45f) else Modifier),
            verticalArrangement = if (showActionArea) Arrangement.spacedBy(8.dp) else Arrangement.Center,
            horizontalAlignment = if (showActionArea) Alignment.Start else Alignment.CenterHorizontally
        ) {
            if (!showActionArea) {
                Text("选择训练部位", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BODY_PARTS.forEach { p ->
                        FilterChip(selected = selectedBodyPart == p, onClick = { selectedBodyPart = p }, label = { Text(p) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("选择训练目标", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TRAINING_GOALS.forEach { g ->
                        FilterChip(selected = selectedGoal == g, onClick = { selectedGoal = g }, label = { Text(g) })
                    }
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { showTemplatePicker = true }) { Text("从模板开始") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${selectedBodyPart} · ${selectedGoal}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        selectedBodyPart = null; selectedGoal = null; exercises = emptyList()
                    }) { Text("重选") }
                }
            }
        }

        AnimatedVisibility(visible = showActionArea, enter = slideInVertically(initialOffsetY = { it })) {
            Column(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("选择动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            FilledIconButton(onClick = { showExercisePicker = true }, shape = CircleShape) {
                                Text("+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    itemsIndexed(exercises) { i, ep ->
                        ExercisePlanCard(ep) { u -> exercises = exercises.toMutableList().also { it[i] = u } }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                if (allFilled) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sessionNotes, onValueChange = { sessionNotes = it },
                            label = { Text("训练备注（可选）") }, modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { showTemplateNameDialog = true }, modifier = Modifier.weight(1f)) { Text("保存模板") }
                            Button(onClick = {
                                val plan = WorkoutPlan(
                                    date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                                    bodyPart = selectedBodyPart!!, goal = selectedGoal!!,
                                    sessionNotes = sessionNotes, exercises = exercises
                                )
                                vm.startWorkout(plan)
                            }, modifier = Modifier.weight(1f)) { Text("开始训练") }
                            OutlinedButton(onClick = {
                                val plan = WorkoutPlan(
                                    date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                                    bodyPart = selectedBodyPart!!, goal = selectedGoal!!,
                                    sessionNotes = sessionNotes, exercises = exercises
                                )
                                scope.launch { vm.addRecordDirectly(plan) }
                                exercises = emptyList(); sessionNotes = ""
                            }, modifier = Modifier.weight(1f)) { Text("添加记录") }
                        }
                    }
                }
            }
        }
    }

    if (showExercisePicker) {
        ExercisePickerSheet(
            bodyPart = selectedBodyPart ?: "胸",
            existingNames = exercises.map { it.exerciseName }.toSet(),
            onDismiss = { showExercisePicker = false },
            onConfirm = { names ->
                val added = names.filter { n -> exercises.none { it.exerciseName == n } }
                exercises = exercises + added.map { ExercisePlan(it, 0.0, 8, 5, 90, "") }
                showExercisePicker = false
            }
        )
    }
    if (showTemplateNameDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateNameDialog = false },
            title = { Text("保存模板") },
            text = { OutlinedTextField(value = templateName, onValueChange = { templateName = it },
                label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (templateName.isNotBlank()) {
                        vm.saveTemplate(templateName, selectedBodyPart!!, selectedGoal!!, exercises)
                        showTemplateNameDialog = false
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

@OptIn(ExperimentalMaterial3Api::class)
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

@Composable
fun ExercisePlanCard(exercise: ExercisePlan, onChange: (ExercisePlan) -> Unit) {
    var showEquipmentPicker by remember { mutableStateOf(false) }
    var linkedProfileId by remember(exercise.equipmentProfileId) { mutableStateOf(exercise.equipmentProfileId) }
    val ctx = LocalContext.current
    val vm: TrainingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TrainingViewModelFactory(AppDatabase.get(ctx).dao())
    )
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(exercise.exerciseName, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = if (exercise.weight == 0.0) "" else exercise.weight.toString(),
                    onValueChange = { onChange(exercise.copy(weight = it.toDoubleOrNull() ?: 0.0)) },
                    label = { Text("重量(负=助力)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(value = exercise.totalSets.toString(),
                    onValueChange = { onChange(exercise.copy(totalSets = it.toIntOrNull() ?: 1)) },
                    label = { Text("组数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
                OutlinedTextField(value = exercise.plannedReps.toString(),
                    onValueChange = { onChange(exercise.copy(plannedReps = it.toIntOrNull() ?: 1)) },
                    label = { Text("次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
                OutlinedTextField(value = exercise.restSeconds.toString(),
                    onValueChange = { onChange(exercise.copy(restSeconds = it.toIntOrNull() ?: 0)) },
                    label = { Text("休息s") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f))
            }
            OutlinedTextField(value = exercise.notes, onValueChange = { onChange(exercise.copy(notes = it)) },
                label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { showEquipmentPicker = true }) {
                    Text(linkedProfileId?.let { "已关联器械 #$it" } ?: "关联器械")
                }
                if (linkedProfileId != null) {
                    TextButton(onClick = { linkedProfileId = null; onChange(exercise.copy(equipmentProfileId = null)) }) { Text("清除") }
                }
            }
        }
    }
    if (showEquipmentPicker) {
        ProfilePickerDialog(vm = vm, exerciseName = exercise.exerciseName,
            onDismiss = { showEquipmentPicker = false },
            onSelect = { linkedProfileId = it.id; onChange(exercise.copy(equipmentProfileId = it.id)); showEquipmentPicker = false })
    }
}

@Composable
fun ProfilePickerDialog(vm: TrainingViewModel, exerciseName: String, onDismiss: () -> Unit, onSelect: (EquipmentProfile) -> Unit) {
    var profiles by remember { mutableStateOf<List<Pair<EquipmentProfile, String>>>(emptyList()) }
    LaunchedEffect(exerciseName) {
        val all = vm.getAllProfiles()
        val gyms = vm.getAllGyms()
        val gymById = gyms.associateBy { it.id }
        profiles = all.filter { exerciseName.isBlank() || it.exerciseName == exerciseName }
            .map { it to (gymById[it.gymId]?.name ?: "") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择器械档案") },
        text = {
            if (profiles.isEmpty()) Text("没有匹配的器械档案，先到“器械”页添加。")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(profiles.size) { i ->
                    val (p, g) = profiles[i]
                    Card(onClick = { onSelect(p) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(p.equipmentName.ifBlank { p.exerciseName }, style = MaterialTheme.typography.titleSmall)
                            Text("动作：${p.exerciseName}｜健身房：$g", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    bodyPart: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val library = EXERCISES[bodyPart].orEmpty()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var custom by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择动作（$bodyPart）", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(library.size) { i ->
                    val name = library[i]
                    val isExisting = name in existingNames
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = name in selected, enabled = !isExisting,
                            onCheckedChange = { c -> selected = if (c) selected + name else selected - name })
                        Text(name + if (isExisting) "（已添加）" else "")
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = custom, onValueChange = { custom = it },
                    label = { Text("添加自定义动作") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (custom.isNotBlank()) { val t = custom.trim(); if (t !in selected) selected = selected + t; custom = "" }
                }) { Text("添加") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onConfirm(selected.toList()) }, modifier = Modifier.weight(1f)) { Text("确认") }
            }
        }
    }
}
