package com.example.traininglog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSessionScreen(vm: TrainingViewModel, active: ActiveWorkout, embedded: Boolean = false) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(100); now = System.currentTimeMillis() } }

    val events by vm.events.collectAsState(initial = "")
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) { if (events.isNotEmpty()) snackbar.showSnackbar(events) }

    var showPainDialog by remember { mutableStateOf(false) }
    var pendingFail by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showAddExercise by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<Int?>(null) }

    BackHandler { showExitConfirm = true }

    val totalElapsed = ((now - active.startMs) / 1000).toInt()
    val restingInfo = findRestingInfo(active)

    val contentModifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)

    if (embedded) {
        Column(Modifier.fillMaxSize()) {
            SessionTopBar(
                elapsed = totalElapsed,
                bodyPart = active.bodyPart,
                goal = active.goal,
                onPain = { showPainDialog = true },
                onAdd = { showAddExercise = true },
                onEnd = { showExitConfirm = true }
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    contentModifier,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    contentItems(
                        active, now, restingInfo,
                        onBlockClick = { exIdx, setIdx -> vm.clickBlock(exIdx, setIdx) },
                        onBlockLongPress = { exIdx, setIdx -> pendingFail = exIdx to setIdx },
                        onEdit = { exIdx -> editTarget = exIdx },
                        onDelete = { exIdx -> deleteTarget = exIdx },
                        onMove = { from, to -> vm.moveActiveExercise(from, to) }
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(formatDuration(totalElapsed), fontWeight = FontWeight.Bold)
                            Text("${active.bodyPart} · ${active.goal}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showPainDialog = true }) { Text("疼痛") }
                        TextButton(onClick = { showAddExercise = true }) { Text("加动作") }
                        TextButton(onClick = { showExitConfirm = true }) { Text("结束") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                contentItems(
                    active, now, restingInfo,
                    onBlockClick = { exIdx, setIdx -> vm.clickBlock(exIdx, setIdx) },
                    onBlockLongPress = { exIdx, setIdx -> pendingFail = exIdx to setIdx },
                    onEdit = { exIdx -> editTarget = exIdx },
                    onDelete = { exIdx -> deleteTarget = exIdx },
                    onMove = { from, to -> vm.moveActiveExercise(from, to) }
                )
            }
        }
    }

    if (showPainDialog) {
        PainDialog(
            onDismiss = { showPainDialog = false },
            onConfirm = { loc, sc, n -> vm.addPainRecord(loc, sc, n); showPainDialog = false }
        )
    }
    if (pendingFail != null) {
        val (exIdx, setIdx) = pendingFail!!
        FailBlockDialog(
            plannedReps = active.exercises[exIdx].plannedReps,
            onDismiss = { pendingFail = null },
            onConfirm = { a, f -> vm.markBlockFailed(exIdx, setIdx, a, f); pendingFail = null }
        )
    }
    if (showAddExercise) {
        AddExerciseDialog(
            vm = vm,
            bodyPart = active.bodyPart,
            onDismiss = { showAddExercise = false },
            onConfirm = { ep -> vm.addExerciseDuringWorkout(ep); showAddExercise = false }
        )
    }
    if (editTarget != null) {
        val idx = editTarget!!
        val ex = active.exercises.getOrNull(idx)
        if (ex != null) {
            EditActiveExerciseDialog(
                vm = vm,
                ex = ex,
                onDismiss = { editTarget = null },
                onConfirm = { weight, reps, sets, rest, notes, eqId ->
                    vm.updateActiveExercise(idx, weight, reps, sets, rest, notes, eqId)
                    editTarget = null
                }
            )
        } else editTarget = null
    }
    if (deleteTarget != null) {
        val idx = deleteTarget!!
        val ex = active.exercises.getOrNull(idx)
        if (ex != null) {
            val doneCount = ex.blocks.count { it.status != BlockStatus.IDLE }
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除动作") },
                text = {
                    Text(
                        if (doneCount > 0)
                            "该动作已有 $doneCount 组开始/完成，删除后这些记录将丢失。确认删除“${ex.exerciseName}”？"
                        else
                            "确认删除“${ex.exerciseName}”？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.removeActiveExercise(idx)
                        deleteTarget = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
            )
        } else deleteTarget = null
    }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("结束训练") },
            text = { Text("当前训练尚未结束，结束会保存已完成的组。确定结束？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.endWorkout()
                }) { Text("结束并保存") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续训练") }
            }
        )
    }
}

@Composable
private fun SessionTopBar(
    elapsed: Int,
    bodyPart: String,
    goal: String,
    onPain: () -> Unit,
    onAdd: () -> Unit,
    onEnd: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatDuration(elapsed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$bodyPart · $goal", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onPain) { Text("疼痛") }
            TextButton(onClick = onAdd) { Text("加动作") }
            TextButton(onClick = onEnd) { Text("结束") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.contentItems(
    active: ActiveWorkout,
    now: Long,
    restingInfo: RestingInfo?,
    onBlockClick: (Int, Int) -> Unit,
    onBlockLongPress: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    if (active.painRecords.isNotEmpty()) {
        item {
            Column {
                active.painRecords.forEach { p ->
                    Text(
                        "疼痛：${p.location} ${p.score}/10${if (p.note.isNotBlank()) "（${p.note}）" else ""}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    itemsIndexed(active.exercises) { exIdx, ex ->
        ExerciseRow(
            exIdx = exIdx,
            ex = ex,
            now = now,
            restingInfo = restingInfo,
            totalCount = active.exercises.size,
            onClickBlock = { i -> onBlockClick(exIdx, i) },
            onLongPressBlock = { i -> onBlockLongPress(exIdx, i) },
            onEdit = { onEdit(exIdx) },
            onDelete = { onDelete(exIdx) },
            onMove = onMove
        )
        Spacer(Modifier.height(4.dp))
    }
    item { Spacer(Modifier.height(60.dp)) }
}

private data class RestingInfo(val exIdx: Int, val blockIdx: Int, val endMs: Long)

private fun findRestingInfo(active: ActiveWorkout): RestingInfo? {
    val anyRunning = active.exercises.any { e -> e.blocks.any { it.status == BlockStatus.RUNNING } }
    if (anyRunning) return null

    var firstIdle: Pair<Int, Int>? = null
    outer@ for (exIdx in active.exercises.indices) {
        val ex = active.exercises[exIdx]
        if (ex.skipped) continue
        for (setIdx in ex.blocks.indices) {
            if (ex.blocks[setIdx].status == BlockStatus.IDLE) {
                firstIdle = exIdx to setIdx
                break@outer
            }
        }
    }
    val idle = firstIdle ?: return null

    var last: RestingInfo? = null
    outer@ for (exIdx in active.exercises.indices) {
        val ex = active.exercises[exIdx]
        if (ex.skipped) continue
        for (setIdx in ex.blocks.indices) {
            if (exIdx == idle.first && setIdx == idle.second) break@outer
            val b = ex.blocks[setIdx]
            if (b.status == BlockStatus.DONE || b.status == BlockStatus.FAILED) {
                last = RestingInfo(exIdx, setIdx, b.endMs)
            }
        }
    }
    return last
}

@Composable
private fun ExerciseRow(
    exIdx: Int,
    ex: ActiveExercise,
    now: Long,
    restingInfo: RestingInfo?,
    totalCount: Int,
    onClickBlock: (Int) -> Unit,
    onLongPressBlock: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val density = LocalDensity.current
    var showMenu by remember { mutableStateOf(false) }
    val isRestingHere = restingInfo != null && restingInfo.exIdx == exIdx
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = if (ex.weight < 0) "助力${ex.exerciseName}" else ex.exerciseName
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${displayLoad(ex.weight)} · ${ex.totalSets}×${ex.plannedReps} · ${ex.restSeconds}s",
                    style = MaterialTheme.typography.bodySmall
                )
                Box {
                    IconButton(onClick = { showMenu = true }) { Text("⋮") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { showMenu = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
                Box(
                    Modifier.size(40.dp).pointerInput(exIdx, totalCount) {
                        val thresholdPx = with(density) { 80.dp.toPx() }
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
                                    val target = (exIdx + rows).coerceIn(0, totalCount - 1)
                                    if (target != exIdx) onMove(exIdx, target)
                                }
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    },
                    contentAlignment = Alignment.Center
                ) { Text("≡", style = MaterialTheme.typography.titleMedium) }
            }
            if (ex.notes.isNotBlank()) Text("备注：${ex.notes}", style = MaterialTheme.typography.bodySmall)
            if (ex.skipped) {
                Text("已跳过", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ex.blocks.forEachIndexed { i, b ->
                        BlockView(
                            block = b, now = now,
                            onClick = { onClickBlock(i) },
                            onLongPress = { onLongPressBlock(i) }
                        )
                        if (i < ex.blocks.size - 1) Spacer(Modifier.width(6.dp))
                    }
                }
                if (isRestingHere) {
                    val restElapsed = now - restingInfo!!.endMs
                    val restMs = ex.restSeconds * 1000L
                    val progress = if (restMs > 0) (restElapsed.toFloat() / restMs).coerceIn(0f, 1f) else 1f
                    val remaining = if (restMs > 0) ((restMs - restElapsed) / 1000).coerceAtLeast(0L).toInt() else 0
                    BlueRestBar(progress = progress, remainingSec = remaining)
                }
            }
        }
    }
}

@Composable
private fun BlueRestBar(progress: Float, remainingSec: Int) {
    val isFull = progress >= 1f
    val infinite = rememberInfiniteTransition()
    val dx by infinite.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val modifier = if (isFull) Modifier.offset(x = dx.dp) else Modifier
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E88E5))
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isFull) "休息完" else "${remainingSec}s",
                style = MaterialTheme.typography.labelSmall,
                color = if (isFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BlockView(block: ActiveBlock, now: Long, onClick: () -> Unit, onLongPress: () -> Unit) {
    val bg = when (block.status) {
        BlockStatus.IDLE -> Color(0xFFE0E0E0)
        BlockStatus.RUNNING -> Color(0xFFFF9800)
        BlockStatus.DONE -> Color(0xFF4CAF50)
        BlockStatus.FAILED -> Color(0xFFF44336)
    }
    val textColor = when (block.status) {
        BlockStatus.DONE, BlockStatus.FAILED, BlockStatus.RUNNING -> Color.White
        else -> Color.Black
    }
    val label = when (block.status) {
        BlockStatus.IDLE -> "${block.setIndex}"
        BlockStatus.RUNNING -> "${((now - block.startMs) / 1000).toInt()}s"
        BlockStatus.DONE -> {
            val d = if (block.endMs > block.startMs) ((block.endMs - block.startMs) / 1000).toInt() else 0
            "${block.setIndex}·${d}s"
        }
        BlockStatus.FAILED -> "未完成"
    }
    val restLabel = if ((block.status == BlockStatus.DONE || block.status == BlockStatus.FAILED) && block.restTakenMs > 0)
        "休${block.restTakenMs / 1000}s" else ""
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 66.dp, height = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                .pointerInput(block) {
                    detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (restLabel.isNotEmpty()) Text(restLabel, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PainDialog(onDismiss: () -> Unit, onConfirm: (String, Int, String) -> Unit) {
    var location by remember { mutableStateOf(PAIN_LOCATIONS.first()) }
    var score by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录疼痛") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("部位")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PAIN_LOCATIONS.forEach { p ->
                        FilterChip(selected = location == p, onClick = { location = p }, label = { Text(p) })
                    }
                }
                Text("指数：${score.toInt()} / 10")
                Slider(value = score, onValueChange = { score = it }, valueRange = 0f..10f, steps = 9)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(location, score.toInt(), note) }) { Text("记录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FailBlockDialog(plannedReps: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var actual by remember { mutableStateOf(plannedReps.toString()) }
    var failure by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = actual, onValueChange = { actual = it },
                    label = { Text("实际完成次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = failure, onValueChange = { failure = it },
                    label = { Text("其中力竭次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(actual.toIntOrNull() ?: 0, failure.toIntOrNull() ?: 0) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EditActiveExerciseDialog(
    vm: TrainingViewModel,
    ex: ActiveExercise,
    onDismiss: () -> Unit,
    onConfirm: (weight: Double, reps: Int, sets: Int, rest: Int, notes: String, equipmentProfileId: Long?) -> Unit
) {
    var weightStr by remember { mutableStateOf(if (ex.weight == 0.0) "" else ex.weight.toString()) }
    var setsStr by remember { mutableStateOf(ex.totalSets.toString()) }
    var repsStr by remember { mutableStateOf(ex.plannedReps.toString()) }
    var restStr by remember { mutableStateOf(ex.restSeconds.toString()) }
    var notes by remember { mutableStateOf(ex.notes) }
    var linkedProfileId by remember { mutableStateOf(ex.equipmentProfileId) }
    var showEquipmentPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑动作") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ex.exerciseName, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = weightStr, onValueChange = { weightStr = it },
                        label = { Text("重量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = setsStr, onValueChange = { setsStr = it },
                        label = { Text("组数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                    OutlinedTextField(
                        value = repsStr, onValueChange = { repsStr = it },
                        label = { Text("次数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                    OutlinedTextField(
                        value = restStr, onValueChange = { restStr = it },
                        label = { Text("休息s") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showEquipmentPicker = true }) {
                        Text(linkedProfileId?.let { "已关联器械 #$it" } ?: "关联器械")
                    }
                    if (linkedProfileId != null) {
                        TextButton(onClick = { linkedProfileId = null }) { Text("清除") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val weight = weightStr.toDoubleOrNull() ?: 0.0
                val sets = setsStr.toIntOrNull() ?: ex.totalSets
                val reps = repsStr.toIntOrNull() ?: ex.plannedReps
                val rest = restStr.toIntOrNull() ?: ex.restSeconds
                onConfirm(weight, reps, sets, rest, notes, linkedProfileId)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showEquipmentPicker) {
        ProfilePickerDialog(
            vm = vm,
            exerciseName = ex.exerciseName,
            onDismiss = { showEquipmentPicker = false },
            onSelect = { linkedProfileId = it.id; showEquipmentPicker = false }
        )
    }
}

@Composable
private fun AddExerciseDialog(
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
        title = { Text("加动作") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
