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
                        Text("时长：${formatDuration(s.session.d
