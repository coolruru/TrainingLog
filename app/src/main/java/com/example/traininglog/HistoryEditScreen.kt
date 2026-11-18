@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.traininglog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

@Composable
fun HistoryEditScreen(
    vm: TrainingViewModel,
    session: SessionWithExercises,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    var exercises by remember { mutableStateOf(session.exercises) }
    var showAddExercise by remember { mutableStateOf(false) }
    var deleteExerciseTarget by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${session.session.date}｜${session.session.bodyPart}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("取消") } },
                actions = {
                    TextButton(enabled = !saving, onClick = {
                        saving = true
                        vm.saveHistoryEdits(session.session, exercises) {
                            saving = false
                            onBack()
                        }
                    }) { Text(if (saving) "保存中..." else "保存") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(exercises) { idx, ex ->
                EditableExerciseCard(
                    ex = ex,
                    onChange = { newEx -> exercises = exercises.toMutableList().also { it[idx] = newEx } },
                    onDelete = { deleteExerciseTarget = idx }
                )
            }
            item {
                OutlinedButton(
                    onClick = { showAddExercise = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) { Text("+ 添加动作") }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (showAddExercise) {
        AddHistoryExerciseDialog(
            vm = vm,
            bodyPart = session.session.bodyPart,
            onDismiss = { showAddExercise = false },
            onConfirm = { ep ->
                val newEx = ExerciseWithSets(
                    exercise = ExerciseEntry(
                        id = 0,
                        sessionId = session.session.id,
                        exerciseName = ep.exerciseName,
                        weight = ep.weight,
                        plannedReps = ep.plannedReps,
                        totalSets = ep.totalSets,
                        restSeconds = ep.restSeconds,
                        notes = ep.notes,
                        isCoreAddon = ep.isCoreAddon,
                        equipmentProfileId = ep.equipmentProfileId,
                        orderIndex = exercises.size
                    ),
                    sets = (1..ep.totalSets).map { i ->
                        SetEntry(
                            id = 0,
                            exerciseId = 0,
                            setIndex = i,
                            completed = true,
                            actualReps = ep.plannedReps,
                            durationSeconds = 0,
                            restTakenSeconds = 0
                        )
                    }
                )
                exercises = exercises + newEx
                showAddExercise = false
            }
        )
    }

    deleteExerciseTarget?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteExerciseTarget = null },
            title = { Text("删除动作") },
            text = { Text("该动作的 ${exercises[idx].sets.size} 组记录将一并删除。确定删除“${exercises[idx].exercise.exerciseName}”？") },
            confirmButton = {
                TextButton(onClick = {
                    exercises = exercises.toMutableList().also { it.removeAt(idx) }
                    deleteExerciseTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteExerciseTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EditableExerciseCard(
    ex: ExerciseWithSets,
    onChange: (ExerciseWithSets) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(4.dp)) {
                    Text(if (expanded) "▼" else "▶")
                }
                val name = if (ex.exercise.weight < 0) "助力${ex.exercise.exerciseName}" else ex.exercise.exerciseName
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${displayLoad(ex.exercise.weight)} × ${ex.sets.size}组",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ex.sets.forEachIndexed { sIdx, set ->
                        EditableSetRow(
                            set = set,
                            onChange = { newSet ->
                                onChange(ex.copy(sets = ex.sets.toMutableList().also { it[sIdx] = newSet }))
                            },
                            onDelete = {
                                onChange(ex.copy(sets = ex.sets.toMutableList().also { it.removeAt(sIdx) }))
                            }
                        )
                    }
                    OutlinedButton(onClick = {
                        val last = ex.sets.lastOrNull()
                        val newSet = SetEntry(
                            id = 0,
                            exerciseId = ex.exercise.id,
                            setIndex = ex.sets.size + 1,
                            completed = last?.completed ?: true,
                            actualReps = last?.actualReps ?: ex.exercise.plannedReps,
                            durationSeconds = last?.durationSeconds ?: 0,
                            restTakenSeconds = last?.restTakenSeconds ?: 0
                        )
                        onChange(ex.copy(sets = ex.sets + newSet))
                    }, modifier = Modifier.fillMaxWidth()) { Text("+ 添加组") }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除动作") },
            text = { Text("该动作的 ${ex.sets.size} 组记录将一并删除。确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun EditableSetRow(
    set: SetEntry,
    onChange: (SetEntry) -> Unit,
    onDelete: () -> Unit
) {
    var actualStr by remember(set.actualReps, set.id) { mutableStateOf(set.actualReps.toString()) }
    var durStr by remember(set.durationSeconds, set.id) { mutableStateOf(set.durationSeconds.toString()) }
    var restStr by remember(set.restTakenSeconds, set.id) { mutableStateOf(set.restTakenSeconds.toString()) }

    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = set.completed, onCheckedChange = { onChange(set.copy(completed = it)) })
                Text("第${set.setIndex}组",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (set.completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                IconButton(onClick = onDelete) { Text("✕", color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = actualStr,
                    onValueChange = {
                        actualStr = it
                        onChange(set.copy(actualReps = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = durStr,
                    onValueChange = {
                        durStr = it
                        onChange(set.copy(durationSeconds = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("用时s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = restStr,
                    onValueChange = {
                        restStr = it
                        onChange(set.copy(restTakenSeconds = it.toIntOrNull() ?: 0))
                    },
                    label = { Text("组后休息s") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.2f),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun AddHistoryExerciseDialog(
    vm: TrainingViewModel,
    bodyPart: String,
    onDismiss: () -> Unit,
    onConfirm: (ExercisePlan) -> Unit
) {
    val allLibrary by vm.allLibrary.collectAsState()
    val library = remember(allLibrary, bodyPart) { allLibrary.filter { it.bodyPart == bodyPart } }
    var name by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("5") }
    var reps by remember { mutableStateOf("8") }
    var rest by remember { mutableStateOf("90") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加动作") },
        text = {
            Column(
                Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("动作") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    library.forEach { item ->
                        AssistChip(onClick = { name = item.name }, label = { Text(item.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("重量") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = sets, onValueChange = { sets = it }, label = { Text("组") }, modifier = Modifier.weight(0.7f))
                    OutlinedTextField(value = reps, onValueChange = { reps = it }, label = { Text("次") }, modifier = Modifier.weight(0.7f))
                    OutlinedTextField(value = rest, onValueChange = { rest = it }, label = { Text("休息s") }, modifier = Modifier.weight(0.8f))
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onConfirm(ExercisePlan(name, weight.toDoubleOrNull() ?: 0.0,
                        reps.toIntOrNull() ?: 8, sets.toIntOrNull() ?: 5, rest.toIntOrNull() ?: 90, note))
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
