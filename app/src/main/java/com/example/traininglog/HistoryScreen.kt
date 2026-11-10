package com.example.traininglog

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HistoryScreen(vm: TrainingViewModel) {
    val list by vm.sessions.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list) { s ->
            Card {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.session.date}｜${s.session.bodyPart}", style = MaterialTheme.typography.titleMedium)
                        if (s.session.goal.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text("· ${s.session.goal}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (s.session.durationSeconds > 0) {
                        Text("时长：${formatDuration(s.session.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (s.session.notes.isNotBlank()) {
                        Text("备注：${s.session.notes}", style = MaterialTheme.typography.bodySmall)
                    }
                    s.painRecords.forEach { p ->
                        Text("疼痛：${p.location} ${p.score}/10${if (p.note.isNotBlank()) "（${p.note}）" else ""}",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    s.exercises.forEach { ex ->
                        val name = if (ex.exercise.weight < 0) "助力${ex.exercise.exerciseName}" else ex.exercise.exerciseName
                        val done = ex.sets.count { it.completed }
                        val totalSec = ex.sets.sumOf { it.durationSeconds }
                        Text(
                            "· $name ${displayLoad(ex.exercise.weight)}×${ex.exercise.plannedReps}×${ex.exercise.totalSets} 完成$done/${ex.exercise.totalSets}" +
                                (if (totalSec > 0) " 用时${totalSec}s" else "") +
                                (if (ex.exercise.isCoreAddon) " [核心]" else "")
                        )
                        ex.equipmentProfile?.let { p ->
                            Text("  器械：${p.equipmentName.ifBlank { p.exerciseName }}（${ex.gymName}）",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { vm.deleteSession(s.session.id) }) { Text("删除本次训练") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(vm: TrainingViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var selectedParts by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 Markdown") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("开始日期 可空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(to, { to = it }, label = { Text("结束日期 可空") }, modifier = Modifier.fillMaxWidth())
                Text("部位（不选 = 全部）")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BODY_PARTS.forEach { p ->
                        FilterChip(
                            selected = p in selectedParts,
                            onClick = { selectedParts = if (p in selectedParts) selectedParts - p else selectedParts + p },
                            label = { Text(p) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val sessions = vm.exportSessions(from.ifBlank { null }, to.ifBlank { null }, selectedParts.toList())
                            val md = MarkdownExporter.build(
                                sessions,
                                rangeDesc = if (from.isBlank() && to.isBlank()) "全部"
                                    else "${from.ifBlank { "开始" }} ~ ${to.ifBlank { "至今" }}",
                                partDesc = if (selectedParts.isEmpty()) "全部" else selectedParts.joinToString("、")
                            )
                            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                            val file = File(dir, "训练记录_${System.currentTimeMillis()}.md")
                            file.writeText(md)
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "分享 Markdown"))
                            onDismiss()
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "导出中..." else "导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
