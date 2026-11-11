package com.example.traininglog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(vm: TrainingViewModel) {
    val templates by vm.templates.collectAsState()
    var editing by remember { mutableStateOf<WorkoutTemplateWithExercises?>(null) }

    if (editing != null) {
        TemplateEditScreen(vm, editing!!, onDone = { editing = null })
        return
    }

    if (templates.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有模板。在“训练”页配置好动作后点“保存模板”。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(templates) { t ->
            TemplateRow(t, onOpen = { editing = t },
                onDelete = { vm.deleteTemplate(t.template.id) },
                onDuplicate = { vm.duplicateTemplate(t) })
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
                Text("${template.template.bodyPart} · ${template.template.goal}｜${template.exercises.size} 动作｜$dateStr",
                    style = MaterialTheme.typography.bodySmall)
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
private fun TemplateEditScreen(
    vm: TrainingViewModel,
    initial: WorkoutTemplateWithExercises,
    onDone: () -> Unit
) {
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

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("编辑模板", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BODY_PARTS.forEach { p -> FilterChip(selected = bodyPart == p, onClick = { bodyPart = p }, label = { Text(p) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TRAINING_GOALS.forEach { g -> FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(g) }) }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledIconButton(onClick = { showPicker = true }) { Text("+") }
                }
            }
            items(exercises.size) { i ->
                val ep = exercises[i]
                TemplateExerciseCard(ep) { u -> exercises = exercises.toMutableList().also { it[i] = u } }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { showNameDialog = true }, modifier = Modifier.weight(1f)) { Text("保存") }
        }
    }

    if (showPicker) {
        ExercisePickerSheet(
            vm = vm,
            bodyPart = bodyPart,
            existingNames = exercises.map { it.exerciseName }.toSet(),
            onDismiss = { showPicker = false },
            onConfirm = { names ->
                exercises = exercises + names.map { ExercisePlan(it, 0.0, 8, 5, 90, "") }
                showPicker = false
            }
        )
    }
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("另存为新模板") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("新模板名称") }, singleLine = true)
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
private fun TemplateExerciseCard(exercise: ExercisePlan, onChange: (ExercisePlan) -> Unit) {
    var weightStr by remember(exercise.exerciseName) { mutableStateOf(if (exercise.weight == 0.0) "" else exercise.weight.toString()) }
    var setsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.totalSets.toString()) }
    var repsStr by remember(exercise.exerciseName) { mutableStateOf(exercise.plannedReps.toString()) }
    var restStr by remember(exercise.exerciseName) { mutableStateOf(exercise.restSeconds.toString()) }
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(exercise.exerciseName, style = MaterialTheme.typography.titleSmall)
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
