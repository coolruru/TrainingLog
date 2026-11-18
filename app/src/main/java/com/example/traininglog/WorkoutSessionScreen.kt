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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.foundation.BorderStroke

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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
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
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        contentItems(active, now, restingInfo,
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
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        title = {
                            Column {
                                Text(
                                    formatDuration(totalElapsed),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "${active.bodyPart} · ${active.goal}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = { showPainDialog = true }) {
                                Text("疼痛", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { showAddExercise = true }) {
                                Text("加动作", color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { showExitConfirm = true }) {
                                Text("结束", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )
                }
            ) { padding ->
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    contentItems(active, now, restingInfo,
                        onBlockClick = { exIdx, setIdx -> vm.clickBlock(exIdx, setIdx) },
                        onBlockLongPress = { exIdx, setIdx -> pendingFail = exIdx to setIdx },
                        onEdit = { exIdx -> editTarget = exIdx },
                        onDelete = { exIdx -> deleteTarget = exIdx },
                        onMove = { from, to -> vm.moveActiveExercise(from, to) }
                    )
                }
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
        val block = active.exercises[exIdx].blocks.getOrNull(setIdx)
        val defaultReps = when (block?.status) {
            BlockStatus.DONE -> block.actualReps
            BlockStatus.RUNNING -> active.exercises[exIdx].plannedReps
            else -> active.exercises[exIdx].plannedReps
        }
        FailBlockDialog(
            defaultReps = defaultReps,
            onDismiss = { pendingFail = null },
            onConfirm = { a ->
                vm.markBlockFailed(exIdx, setIdx, a)
                pendingFail = null
            }
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
                containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除动作", color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Text(
                        if (doneCount > 0)
                            "该动作已有 $doneCount 组开始/完成，删除后这些记录将丢失。确认删除“${ex.exerciseName}”？"
                        else
                            "确认删除“${ex.exerciseName}”？",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { showExitConfirm = false },
            title = { Text("结束训练", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("当前训练尚未结束，结束会保存已完成的组。确定结束？", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.endWorkout()
                }) { Text("结束并保存", color = MaterialTheme.colorScheme.primary) }
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
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDuration(elapsed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("$bodyPart · $goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPain) { Text("疼痛", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onAdd) { Text("加动作", color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onEnd) { Text("结束", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.contentItems(
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    active.painRecords.forEach { p ->
                        Text(
                            "⚠️ 疼痛记录：${p.location} ${p.score}/10${if (p.note.isNotBlank()) "（${p.note}）" else ""}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
    item { Spacer(Modifier.height(80.dp)) }
}

private data class RestingInfo(val exIdx: Int, val blockIdx: Int, val endMs: Long)

private fun findRestingInfo(active: ActiveWorkout): RestingInfo? {
    if (active.exercises.any { e -> e.blocks.any { it.status == BlockStatus.RUNNING } }) return null

    val anyIdle = active.exercises.any { e ->
        !e.skipped && e.blocks.any { it.status == BlockStatus.IDLE }
    }
    if (!anyIdle) return null

    var last: RestingInfo? = null
    for (exIdx in active.exercises.indices) {
        val ex = active.exercises[exIdx]
        if (ex.skipped) continue
        for (setIdx in ex.blocks.indices) {
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

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = if (ex.weight < 0) "助力${ex.exerciseName}" else ex.exerciseName
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${displayLoad(ex.weight)} · ${ex.totalSets}×${ex.plannedReps} · ${ex.restSeconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                        Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { showMenu = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
                Box(
                    Modifier.size(36.dp).pointerInput(exIdx, totalCount) {
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
                ) { Text("≡", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (ex.notes.isNotBlank()) {
                Text(
                    "备注：${ex.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                        if (i < ex.blocks.size - 1) Spacer(Modifier.width(8.dp))
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
        initialValue = -1.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val modifier = if (isFull) Modifier.offset(x = dx.dp) else Modifier
    Column(modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF00E5FF))
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (isFull) "⚡ 休息完" else "休息 ${remainingSec}s",
                style = MaterialTheme.typography.labelSmall,
                color = if (isFull) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BlockView(block: ActiveBlock, now: Long, onClick: () -> Unit, onLongPress: () -> Unit) {
    val bg = when (block.status) {
        BlockStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        BlockStatus.RUNNING -> Color(0xFFFF5C00)
        BlockStatus.DONE -> Color(0xFF10B981)
        BlockStatus.FAILED -> Color(0xFFFF3B30)
    }
    val textColor = when (block.status) {
        BlockStatus.DONE, BlockStatus.FAILED, BlockStatus.RUNNING -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (block.status) {
        BlockStatus.IDLE -> "${block.setIndex}"
        BlockStatus.RUNNING -> "${((now - block.startMs) / 1000).toInt()}s"
        BlockStatus.DONE -> {
            val d = if (block.endMs > block.startMs) ((block.endMs - block.startMs) / 1000).toInt() else 0
            "${block.setIndex}·${d}s"
        }
        BlockStatus.FAILED -> "未"
    }
    val restLabel = if ((block.status == BlockStatus.DONE || block.status == BlockStatus.FAILED) && block.restTakenMs > 0)
        "休${block.restTakenMs / 1000}s" else ""

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 62.dp, height = 50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(
                    width = if (block.status == BlockStatus.IDLE) 1.dp else 0.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(block) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = {
                            if (block.status != BlockStatus.IDLE) onLongPress()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (restLabel.isNotEmpty()) {
            Text(
                restLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun PainDialog(onDismiss: () -> Unit, onConfirm: (String, Int, String) -> Unit) {
    var location by remember { mutableStateOf(PAIN_LOCATIONS.first()) }
    var score by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("记录疼痛", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("部位", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PAIN_LOCATIONS.forEach { p ->
                        FilterChip(
                            selected = location == p,
                            onClick = { location = p },
                            label = { Text(p) }
                        )
                    }
                }
                Text("指数：${score.toInt()} / 10", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Slider(value = score, onValueChange = { score = it }, valueRange = 0f..10f, steps = 9)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(location, score.toInt(), note) }) { Text("记录", color = MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FailBlockDialog(defaultReps: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var actual by remember { mutableStateOf(defaultReps.toString()) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("未完成标记", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            OutlinedTextField(
                value = actual, onValueChange = { actual = it },
                label = { Text("实际完成次数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(actual.toIntOrNull() ?: 0) }) { Text("确认", color = MaterialTheme.colorScheme.primary) }
        },
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
    var linkedProfile by remember { mutableStateOf<EquipmentProfile?>(null) }
    var showEquipmentPicker by remember { mutableStateOf(false) }

    LaunchedEffect(ex.equipmentProfileId) {
        if (ex.equipmentProfileId != null) {
            linkedProfile = vm.getAllProfiles().firstOrNull { it.id == ex.equipmentProfileId }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("编辑动作参数", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ex.exerciseName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = weightStr, onValueChange = { weightStr = it },
                        label = { Text("重量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = setsStr, onValueChange = { setsStr = it },
                        label = { Text("组") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                    OutlinedTextField(
                        value = repsStr, onValueChange = { repsStr = it },
                        label = { Text("次") },
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
                        Text(linkedProfile?.let { "已关联：${it.equipmentName.ifBlank { it.exerciseName }}" } ?: "关联器械")
                    }
                    if (linkedProfile != null) {
                        TextButton(onClick = { linkedProfile = null }) { Text("清除") }
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
                onConfirm(weight, reps, sets, rest, notes, linkedProfile?.id)
            }) { Text("保存", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showEquipmentPicker) {
        ProfilePickerDialog(
            vm = vm,
            exerciseName = ex.exerciseName,
            onDismiss = { showEquipmentPicker = false },
            onSelect = { linkedProfile = it; showEquipmentPicker = false }
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
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text("添加训练动作", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("动作名称") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    library.forEach { item ->
                        AssistChip(onClick = { name = item.name }, label = { Text(item.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }) { Text("添加", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
