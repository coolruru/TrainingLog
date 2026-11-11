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
fun WorkoutSessionScreen(vm: TrainingViewModel, active: ActiveWorkout) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(100); now = System.currentTimeMillis() } }

    val events by vm.events.collectAsState(initial = "")
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) { if (events.isNotEmpty()) snackbar.showSnackbar(events) }

    var showPainDialog by remember { mutableStateOf(false) }
    var pendingFail by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showAddExercise by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    BackHandler { showExitConfirm = true }

    val totalElapsed = ((now - active.startMs) / 1000).toInt()
    val restingInfo = findRestingInfo(active)

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
                    cupAfterBlock = restingInfo?.takeIf { it.exIdx == exIdx }?.blockIdx,
                    cupEndMs = restingInfo?.takeIf { it.exIdx == exIdx }?.endMs ?: 0L,
                    totalCount = active.exercises.size,
                    onClickBlock = { i -> vm.clickBlock(exIdx, i) },
                    onLongPressBlock = { i -> pendingFail = exIdx to i },
                    onSkip = { vm.skipExercise(exIdx) },
                    onMove = { from, to -> vm.moveActiveExercise(from, to) }
                )
                Spacer(Modifier.height(4.dp))
            }
            item { Spacer(Modifier.height(60.dp)) }
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
            bodyPart = active.bodyPart,
            onDismiss = { showAddExercise = false },
            onConfirm = { ep -> vm.addExerciseDuringWorkout(ep); showAddExercise = false }
        )
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

private data class RestingInfo(val exIdx: Int, val blockIdx: Int, val endMs: Long)

private fun findRestingInfo(active: ActiveWorkout): RestingInfo? {
    // 只要有一个 RUNNING 方块，就说明休息已经结束
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
    // 没有 IDLE，说明全部完成，不显示杯子
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
    cupAfterBlock: Int?,
    cupEndMs: Long,
    totalCount: Int,
    onClickBlock: (Int) -> Unit,
    onLongPressBlock: (Int) -> Unit,
    onSkip: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val density = LocalDensity.current
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = if (ex.weight < 0) "助力${ex.exerciseName}" else ex.exerciseName
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (ex.skipped) {
                    Text("已跳过", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${displayLoad(ex.weight)} · ${ex.totalSets}×${ex.plannedReps} · ${ex.restSeconds}s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onSkip) { Text("跳过") }
                }
                // 拖动手柄
                Box(
                    Modifier
                        .size(40.dp)
                        .pointerInput(exIdx, totalCount) {
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
                ) {
                    Text("≡", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (ex.notes.isNotBlank()) Text("备注：${ex.notes}", style = MaterialTheme.typography.bodySmall)
            if (!ex.skipped) {
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
                        if (cupAfterBlock == i) {
                            val restElapsed = now - cupEndMs
                            val restMs = ex.restSeconds * 1000L
                            val progress = if (restMs > 0)
                                (1f - restElapsed.toFloat() / restMs).coerceIn(0f, 1f) else 1f
                            val shaking = restMs > 0 && restElapsed >= restMs
                            WaterCup(
                                progress = progress,
                                shaking = shaking,
                                restElapsedSec = (restElapsed / 1000).toInt()
                            )
                        } else if (i < ex.blocks.size - 1) {
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
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
private fun WaterCup(progress: Float, shaking: Boolean, restElapsedSec: Int) {
    val infinite = rememberInfiniteTransition()
    val dx by infinite.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse)
    )
    val modifier = if (shaking) Modifier.offset(x = dx.dp) else Modifier
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .size(width = 22.dp, height = 32.dp)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp, topStart = 2.dp, topEnd = 2.dp))
                .background(Color(0x11000000))
                .border(1.dp, Color.Gray, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp, topStart = 2.dp, topEnd = 2.dp)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(progress).background(Color(0xFF4FC3F7)))
        }
        Text(if (shaking) "休息完" else "${restElapsedSec}s", style = MaterialTheme.typography.labelSmall)
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
                OutlinedTextField(value = actual, onValueChange = { actual = it },
                    label = { Text("实际完成次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = failure, onValueChange = { failure = it },
                    label = { Text("其中力竭次数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(actual.toIntOrNull() ?: 0, failure.toIntOrNull() ?: 0) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddExerciseDialog(bodyPart: String, onDismiss: () -> Unit, onConfirm: (ExercisePlan) -> Unit) {
    val library = DEFAULT_EXERCISES[bodyPart].orEmpty()
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
                    library.forEach { n -> AssistChip(onClick = { name = n }, label = { Text(n) }) }
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
        confirmButton = { TextButton(onClick = {
            if (name.isNotBlank()) onConfirm(ExercisePlan(name, weight.toDoubleOrNull() ?: 0.0,
                reps.toIntOrNull() ?: 8, sets.toIntOrNull() ?: 5, rest.toIntOrNull() ?: 90, note))
        }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
