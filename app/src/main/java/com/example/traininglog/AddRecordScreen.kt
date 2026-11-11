package com.example.traininglog

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordScreen(
    bodyPart: String,
    goal: String,
    exercises: List<ExercisePlan>,
    sessionNotes: String,
    onCancel: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var durationInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加记录") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("取消") }
                },
                actions = {
                    TextButton(onClick = {
                        val secs = parseDurationToSeconds(durationInput) ?: 0
                        onConfirm(date, secs)
                    }) { Text("确认") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("$bodyPart · $goal", style = MaterialTheme.typography.titleMedium)

            Text("日期", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        runCatching {
                            val f = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            time = f.parse(date) ?: Date()
                        }
                    }
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d ->
                            val picked = Calendar.getInstance().apply {
                                set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d)
                            }
                            val today = Calendar.getInstance()
                            if (!picked.after(today)) {
                                date = String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d)
                            }
                        },
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                    ).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                }) { Text("日历") }
            }

            Text("总时长（选填）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = durationInput, onValueChange = { durationInput = it },
                label = { Text("如 68 或 1:05") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("支持：68 = 68分钟；1:05 = 1小时05分；1:05:30 = 时分秒",
                style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("将保存以下动作：", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(exercises) { ep ->
                    Card {
                        Column(Modifier.padding(10.dp)) {
                            Text(ep.exerciseName, style = MaterialTheme.typography.bodyMedium)
                            Text("${displayLoad(ep.weight)} × ${ep.plannedReps}次 × ${ep.totalSets}组" +
                                (if (ep.restSeconds > 0) "，休息${ep.restSeconds}s" else ""),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
