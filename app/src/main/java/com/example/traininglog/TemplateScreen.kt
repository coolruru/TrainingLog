package com.example.traininglog

import androidx.activity.compose.BackHandler
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

    BackHandler(enabled = editing != null) { editing = null }

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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(templates) { t ->
            TemplateRow(t, onOpen = { editing = t },
                onDelete = { vm.deleteTemplate(t.template.id) },
                onDuplicate = { vm.duplicateTemplate(t) })
        }
    }
}

// ===== 以下 TemplateRow / TemplateEditScreen / TemplateExerciseCard 保持原样 =====
// 这里省略，直接使用你现有文件里这三个函数不变
