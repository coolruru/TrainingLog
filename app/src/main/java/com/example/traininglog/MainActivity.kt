package com.example.traininglog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

val EXERCISES = mapOf(
    "胸" to listOf("杠铃卧推","哑铃卧推","上斜卧推","下斜推胸","固定上斜卧推","双杠臂屈伸","坐姿飞鸟","绳索夹胸"),
    "背" to listOf("引体向上","助力引体","杠铃划船","高位下拉","超级高位下拉","坐姿划船","悍马划船","直臂下压","硬拉"),
    "肩" to listOf("哑铃推举","固定肩推","悍马肩推","侧平举","绳索侧平举","面拉","后束飞鸟"),
    "腿" to listOf("深蹲","硬拉","腿举","罗马尼亚硬拉"),
    "核心" to listOf("悬垂举腿","器械卷腹")
)
val BODY_PARTS = EXERCISES.keys.toList()
val PAIN_LOCATIONS = listOf("无","肩前","肩后","肘","下背","膝","髋","其他")

data class SetInput(val index: Int, val completed: Boolean, val actual: Int, val failure: Int)

class TrainingViewModel(private val dao: TrainingDao) : ViewModel() {

    val sessions = combine(
        dao.observeSessions(),
        dao.observeAllExercises(),
        dao.observeAllSets()
    ) { s, e, st -> assemble(s, e, st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession

    private val _currentExercises = MutableStateFlow<List<ExerciseWithSets>>(emptyList())
    val currentExercises: StateFlow<List<ExerciseWithSets>> = _currentExercises

    fun startOrResumeSession(
        date: String, part: String,
        painLoc: String, painScore: Int, notes: String
    ) = viewModelScope.launch {
        val existing = dao.getSessionByDateAndPart(date, part)
        if (existing != null) {
            val updated = existing.copy(painLocation = painLoc, painScore = painScore, notes = notes)
            dao.updateSession(updated)
            _currentSession.value = updated
            loadCurrentExercises(existing.id)
        } else {
            val id = dao.insertSession(
                Session(date = date, bodyPart = part, painLocation = painLoc, painScore = painScore, notes = notes)
            )
            _currentSession.value = Session(
                id = id, date = date, bodyPart = part,
                painLocation = painLoc, painScore = painScore, notes = notes
            )
            _currentExercises.value = emptyList()
        }
    }

    fun updateCurrentSessionInfo(painLoc: String, painScore: Int, notes: String) = viewModelScope.launch {
        val s = _currentSession.value ?: return@launch
        val updated = s.copy(painLocation = painLoc, painScore = painScore, notes = notes)
        dao.updateSession(updated)
        _currentSession.value = updated
    }

    private suspend fun loadCurrentExercises(sessionId: Long) {
        val exs = dao.getExercisesBySession(sessionId)
        val sets = if (exs.isEmpty()) emptyList() else dao.getSetsByExerciseIds(exs.map { it.id })
        _currentExercises.value = exs.map { ex ->
            ExerciseWithSets(ex, sets.filter { it.exerciseId == ex.id }.sortedBy { it.setIndex })
        }
    }

    fun addExerciseToCurrentSession(
        exerciseName: String, weight: Double, plannedReps: Int, totalSets: Int,
        exerciseNotes: String, sets: List<Triple<Boolean, Int, Int>>
    ) = viewModelScope.launch {
        val session = _currentSession.value ?: return@launch
        val exerciseId = dao.insertExercise(
            ExerciseEntry(
                sessionId = session.id, exerciseName = exerciseName,
                weight = weight, plannedReps = plannedReps, totalSets = totalSets, notes = exerciseNotes
            )
        )
        dao.insertSets(sets.mapIndexed { i, t ->
            SetEntry(
                exerciseId = exerciseId, setIndex = i + 1,
                completed = t.first, actualReps = t.second, failureReps = t.third
            )
        })
        loadCurrentExercises(session.id)
    }

    fun removeExercise(exerciseId: Long) = viewModelScope.launch {
        dao.deleteExercise(exerciseId)
        _currentSession.value?.let { loadCurrentExercises(it.id) }
    }

    fun endCurrentSession() {
        _currentSession.value = null
        _currentExercises.value = emptyList()
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        dao.deleteSession(id)
        if (_currentSession.value?.id == id) endCurrentSession()
    }

    suspend fun exportSessions(from: String?, to: String?, parts: List<String>): List<SessionWithExercises> {
        val sessions = if (from != null && to != null) dao.getBetween(from, to) else dao.getAllSessions()
        val all = assemble(sessions, dao.getAllExercises(), dao.getAllSets())
        return if (parts.isEmpty()) all else all.filter { it.session.bodyPart in parts }
    }
}

class TrainingViewModelFactory(private val dao: TrainingDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TrainingViewModel(dao) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).dao()
        setContent {
            MaterialTheme {
                val vm: TrainingViewModel = viewModel(factory = TrainingViewModelFactory(dao))
                MainScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: TrainingViewModel) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("记录","历史","导出")
    Scaffold(
        topBar = { TopAppBar(title = { Text("训练记录") }) },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t) },
                        label = { Text(t) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> RecordScreen(vm)
                1 -> HistoryScreen(vm)
                2 -> ExportScreen(vm)
            }
        }
    }
}

@Composable
fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label：$value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
fun RecordScreen(vm: TrainingViewModel) {
    val currentSession by vm.currentSession.collectAsState()
    val currentExercises by vm.currentExercises.collectAsState()

    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var bodyPart by remember { mutableStateOf("胸") }
    var painLoc by remember { mutableStateOf("无") }
    var painScore by remember { mutableStateOf(0f) }
    var sessionNotes by remember { mutableStateOf("") }

    var exercise by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var plannedReps by remember { mutableStateOf("8") }
    var totalSets by remember { mutableStateOf("5") }
    var exerciseNotes by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf(listOf<SetInput>()) }

    LaunchedEffect(totalSets, plannedReps) {
        val n = totalSets.toIntOrNull() ?: 1
        val r = plannedReps.toIntOrNull() ?: 0
        sets = List(n) { i -> SetInput(index = i + 1, completed = true, actual = r, failure = 0) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("训练信息", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(date, { date = it }, label = { Text("日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())

        DropdownField("训练部位", bodyPart, BODY_PARTS) {
            bodyPart = it
            exercise = ""
            if (currentSession != null && currentSession?.bodyPart != it) {
                vm.endCurrentSession()
            }
        }

        DropdownField("疼痛部位", painLoc, PAIN_LOCATIONS) { painLoc = it }
        Text("疼痛指数：${painScore.toInt()} / 10")
        Slider(value = painScore, onValueChange = { painScore = it }, valueRange = 0f..10f, steps = 9)
        OutlinedTextField(sessionNotes, { sessionNotes = it }, label = { Text("训练备注") }, modifier = Modifier.fillMaxWidth())

        if (currentSession == null) {
            Button(
                onClick = {
                    vm.startOrResumeSession(date, bodyPart, painLoc, painScore.toInt(), sessionNotes)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始 / 继续本次训练") }
            Text("提示：同一天同部位会合并到同一次训练，继续添加动作即可。", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val s = currentSession!!
        Card {
            Column(Modifier.padding(10.dp)) {
                Text("当前训练：${s.date}｜${s.bodyPart}", style = MaterialTheme.typography.titleMedium)
                Text("已记录 ${currentExercises.size} 个动作", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        vm.updateCurrentSessionInfo(painLoc, painScore.toInt(), sessionNotes)
                    }) { Text("更新疼痛/备注") }
                    OutlinedButton(onClick = { vm.endCurrentSession() }) { Text("结束本次训练") }
                }
            }
        }

        HorizontalDivider()

        Text("添加动作", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            exercise, { exercise = it },
            label = { Text("动作（可选可填）") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXERCISES[bodyPart]?.take(4)?.forEach { e ->
                AssistChip(onClick = { exercise = e }, label = { Text(e) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXERCISES[bodyPart]?.drop(4)?.forEach { e ->
                AssistChip(onClick = { exercise = e }, label = { Text(e) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(weight, { weight = it }, label = { Text("重量kg") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            OutlinedTextField(plannedReps, { plannedReps = it }, label = { Text("次数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
            OutlinedTextField(totalSets, { totalSets = it }, label = { Text("组数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
        }

        OutlinedTextField(exerciseNotes, { exerciseNotes = it }, label = { Text("动作备注") }, modifier = Modifier.fillMaxWidth())

        Text("每组完成情况", style = MaterialTheme.typography.titleSmall)
        sets.forEachIndexed { i, st ->
            Card {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = st.completed, onCheckedChange = {
                            sets = sets.toMutableList().also { l ->
                                l[i] = st.copy(completed = it, failure = if (it) 0 else st.failure)
                            }
                        })
                        Text("第${st.index}组 完成")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            st.actual.toString(),
                            { v ->
                                sets = sets.toMutableList().also { l ->
                                    l[i] = st.copy(actual = v.toIntOrNull() ?: 0)
                                }
                            },
                            label = { Text("实际次数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        if (!st.completed) {
                            OutlinedTextField(
                                st.failure.toString(),
                                { v ->
                                    sets = sets.toMutableList().also { l ->
                                        l[i] = st.copy(failure = v.toIntOrNull() ?: 0)
                                    }
                                },
                                label = { Text("力竭次数") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                if (exercise.isBlank()) return@Button
                vm.addExerciseToCurrentSession(
                    exerciseName = exercise,
                    weight = weight.toDoubleOrNull() ?: 0.0,
                    plannedReps = plannedReps.toIntOrNull() ?: 0,
                    totalSets = totalSets.toIntOrNull() ?: sets.size,
                    exerciseNotes = exerciseNotes,
                    sets = sets.map { Triple(it.completed, it.actual, it.failure) }
                )
                exercise = ""
                exerciseNotes = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加到本次训练") }

        HorizontalDivider()

        Text("本次已记录动作", style = MaterialTheme.typography.titleMedium)
        if (currentExercises.isEmpty()) {
            Text("还没有动作，添加后会显示在这里。", style = MaterialTheme.typography.bodySmall)
        } else {
            currentExercises.forEach { ex ->
                Card {
                    Column(Modifier.padding(10.dp)) {
                        val done = ex.sets.count { it.completed }
                        Text("${ex.exercise.exerciseName}  ${ex.exercise.weight}kg×${ex.exercise.plannedReps}×${ex.exercise.totalSets}  完成$done/${ex.exercise.totalSets}")
                        TextButton(onClick = { vm.removeExercise(ex.exercise.id) }) { Text("删除这个动作") }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: TrainingViewModel) {
    val list by vm.sessions.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list) { s ->
            Card {
                Column(Modifier.padding(10.dp)) {
                    Text("${s.session.date}｜${s.session.bodyPart}", style = MaterialTheme.typography.titleMedium)
                    if (s.session.painScore > 0)
                        Text("疼痛：${s.session.painLocation} ${s.session.painScore}/10",
                            color = MaterialTheme.colorScheme.error)
                    s.exercises.forEach { ex ->
                        val done = ex.sets.count { it.completed }
                        Text("· ${ex.exercise.exerciseName} ${ex.exercise.weight}kg×${ex.exercise.plannedReps}×${ex.exercise.totalSets} 完成$done/${ex.exercise.totalSets}")
                    }
                    TextButton(onClick = { vm.deleteSession(s.session.id) }) { Text("删除本次训练") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var selectedParts by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(from, { from = it }, label = { Text("开始日期 可空") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(to, { to = it }, label = { Text("结束日期 可空") }, modifier = Modifier.fillMaxWidth())

        Text("部位（不选 = 全部）")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BODY_PARTS.forEach { p ->
                FilterChip(
                    selected = p in selectedParts,
                    onClick = {
                        selectedParts = if (p in selectedParts) selectedParts - p else selectedParts + p
                    },
                    label = { Text(p) }
                )
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val sessions = vm.exportSessions(
                            from.ifBlank { null }, to.ifBlank { null }, selectedParts.toList()
                        )
                        val md = MarkdownExporter.build(
                            sessions,
                            rangeDesc = if (from.isBlank() && to.isBlank()) "全部" else "${from.ifBlank { "开始" }} ~ ${to.ifBlank { "至今" }}",
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
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "导出中..." else "导出 Markdown 并分享") }
    }
}
