package com.example.traininglog

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
val PHOTO_TAGS = listOf("座位","靠背","握把","配重","插销","助力","滑轮","其他")

data class SetInput(val index: Int, val completed: Boolean, val actual: Int, val failure: Int)
data class PhotoInput(val filePath: String, val tag: String)

fun fileForPath(ctx: Context, relPath: String): File =
    File(if (relPath.startsWith("/")) relPath else File(ctx.filesDir, relPath).path)

suspend fun saveImageToPrivate(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val dir = File(ctx.filesDir, "equipment_photos").apply { mkdirs() }
        val dest = File(dir, "img_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (maxDim / sample > 1600) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dec)
            ?: return@withContext null
        dest.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        dest.relativeTo(ctx.filesDir).path
    } catch (e: Exception) {
        null
    }
}

class TrainingViewModel(private val dao: TrainingDao) : ViewModel() {

    val sessions = combine(
        combine(dao.observeSessions(), dao.observeAllExercises(), dao.observeAllSets()) { s, e, st ->
            Triple(s, e, st)
        },
        combine(dao.getAllProfilesFlow(), dao.observeAllProfilePhotos(), dao.observeGyms()) { p, ph, g ->
            Triple(p, ph, g)
        }
    ) { (s, e, st), (p, ph, g) ->
        assembleSessions(s, e, st, p, ph, g)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gyms: StateFlow<List<Gym>> = dao.observeGyms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession

    private val _currentExercisePart = MutableStateFlow<String?>(null)
    val currentExercisePart: StateFlow<String?> = _currentExercisePart

    private val _currentExercises = MutableStateFlow<List<ExerciseWithSets>>(emptyList())
    val currentExercises: StateFlow<List<ExerciseWithSets>> = _currentExercises

    fun startOrResumeSession(
        date: String, part: String,
        painLoc: String, painScore: Int, notes: String
    ) = viewModelScope.launch {
        if (part == "核心") {
            val target = dao.getLatestNonCoreSessionOfDate(date)
                ?: dao.getLatestSessionOfDate(date)
            if (target != null) {
                val updated = target.copy(
                    painLocation = if (painLoc != "无") painLoc else target.painLocation,
                    painScore = if (painScore > 0) painScore else target.painScore,
                    notes = if (notes.isNotBlank()) notes else target.notes
                )
                dao.updateSession(updated)
                _currentSession.value = updated
                _currentExercisePart.value = "核心"
                loadCurrentExercises(updated.id)
                return@launch
            }
        }
        val existing = dao.getSessionByDateAndPart(date, part)
        if (existing != null) {
            val updated = existing.copy(
                painLocation = painLoc, painScore = painScore, notes = notes
            )
            dao.updateSession(updated)
            _currentSession.value = updated
            _currentExercisePart.value = part
            loadCurrentExercises(existing.id)
        } else {
            val id = dao.insertSession(
                Session(date = date, bodyPart = part, painLocation = painLoc, painScore = painScore, notes = notes)
            )
            val s = Session(id = id, date = date, bodyPart = part,
                painLocation = painLoc, painScore = painScore, notes = notes)
            _currentSession.value = s
            _currentExercisePart.value = part
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
        val profiles = dao.getAllProfiles()
        val photos = dao.getAllProfilePhotos()
        val gyms = dao.getAllGyms()
        val profById = profiles.associateBy { it.id }
        val gymById = gyms.associateBy { it.id }
        val firstPhoto = photos.groupBy { it.profileId }.mapValues { it.value.firstOrNull() }
        _currentExercises.value = exs.map { ex ->
            val profile = ex.equipmentProfileId?.let { profById[it] }
            ExerciseWithSets(
                ex,
                sets.filter { it.exerciseId == ex.id }.sortedBy { it.setIndex },
                profile,
                profile?.let { firstPhoto[it.id] },
                profile?.let { gymById[it.gymId]?.name } ?: ""
            )
        }
    }

    fun addExerciseToCurrentSession(
        exerciseName: String, weight: Double, plannedReps: Int, totalSets: Int,
        exerciseNotes: String, sets: List<Triple<Boolean, Int, Int>>,
        equipmentProfileId: Long?
    ) = viewModelScope.launch {
        val session = _currentSession.value ?: return@launch
        val part = _currentExercisePart.value ?: session.bodyPart
        val isCore = part == "核心"
        val exerciseId = dao.insertExercise(
            ExerciseEntry(
                sessionId = session.id, exerciseName = exerciseName,
                weight = weight, plannedReps = plannedReps, totalSets = totalSets,
                notes = exerciseNotes, isCoreAddon = isCore,
                equipmentProfileId = equipmentProfileId
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
        _currentExercisePart.value = null
        _currentExercises.value = emptyList()
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        dao.deleteSession(id)
        if (_currentSession.value?.id == id) endCurrentSession()
    }

    suspend fun exportSessions(from: String?, to: String?, parts: List<String>): List<SessionWithExercises> {
        val s = if (from != null && to != null) dao.getBetween(from, to) else dao.getAllSessions()
        val all = assembleSessions(
            s, dao.getAllExercises(), dao.getAllSets(),
            dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllGyms()
        )
        return if (parts.isEmpty()) all else all.filter { it.session.bodyPart in parts }
    }

    // ===== 器械模块 =====

    fun addGym(name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        if (dao.findGymByName(trimmed) == null) {
            dao.insertGym(Gym(name = trimmed))
        }
    }

    fun deleteGym(gym: Gym) = viewModelScope.launch {
        if (dao.countProfilesInGym(gym.id) > 0) return@launch
        dao.deleteGym(gym.id)
    }

    fun saveProfile(
        profile: EquipmentProfile,
        photos: List<PhotoInput>,
        removedPhotoIds: List<Long>
    ) = viewModelScope.launch {
        val profileId = if (profile.id == 0L) {
            dao.insertProfile(profile)
        } else {
            dao.updateProfile(profile)
            profile.id
        }
        photos.forEach { p ->
            dao.insertProfilePhoto(
                EquipmentProfilePhoto(profileId = profileId, filePath = p.filePath, tag = p.tag)
            )
        }
        removedPhotoIds.forEach { id -> dao.deleteProfilePhoto(id) }
    }

    suspend fun loadProfilePhotos(profileId: Long): List<EquipmentProfilePhoto> =
        dao.getPhotosByProfile(profileId)

    fun deleteProfile(ctx: Context, profileId: Long) = viewModelScope.launch {
        val photos = dao.getPhotosByProfile(profileId)
        photos.forEach { p -> runCatching { fileForPath(ctx, p.filePath).delete() } }
        dao.clearExerciseRefsToProfile(profileId)
        dao.deleteProfile(profileId)
    }

    fun deleteProfilePhoto(ctx: Context, photoId: Long, filePath: String) = viewModelScope.launch {
        runCatching { fileForPath(ctx, filePath).delete() }
        dao.deleteProfilePhoto(photoId)
    }

    fun observeProfilesByGym(gymId: Long): Flow<List<EquipmentProfile>> =
        dao.observeProfilesByGym(gymId)

    fun observePhotosByProfile(profileId: Long): Flow<List<EquipmentProfilePhoto>> =
        dao.observePhotosByProfile(profileId)

    suspend fun getAllProfiles(): List<EquipmentProfile> = dao.getAllProfiles()
    suspend fun getAllProfilePhotos(): List<EquipmentProfilePhoto> = dao.getAllProfilePhotos()
    suspend fun getAllGyms(): List<Gym> = dao.getAllGyms()
}

@Dao
interface ExtraDao {
    @Query("SELECT * FROM equipment_profiles")
    fun getAllProfilesFlow(): Flow<List<EquipmentProfile>>

    @Query("SELECT * FROM equipment_profile_photos")
    fun observeAllProfilePhotos(): Flow<List<EquipmentProfilePhoto>>

    @Query("SELECT * FROM gyms ORDER BY createdAt ASC")
    fun observeGyms(): Flow<List<Gym>>
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
        enableEdgeToEdge()
        val dao = AppDatabase.get(this).dao()
        val store = ThemeStore(this)
        setContent {
            var themeMode by remember { mutableStateOf(store.get()) }
            AppTheme(themeMode) {
                val vm: TrainingViewModel = viewModel(factory = TrainingViewModelFactory(dao))
                MainScreen(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeModeChange = { m -> themeMode = m; store.set(m) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: TrainingViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("记录","历史","器械","导出","设置")
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
                2 -> EquipmentScreen(vm)
                3 -> ExportScreen(vm)
                4 -> SettingsScreen(themeMode, onThemeModeChange)
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

fun displayLoad(weight: Double): String = when {
    weight < 0 -> "助力${fmtW(-weight)}kg"
    weight == 0.0 -> "自重"
    else -> "${fmtW(weight)}kg"
}

fun fmtW(w: Double): String = if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()

// ==================== 记录页 ====================

@Composable
fun DateField(date: String, onDateChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf(date) }
    LaunchedEffect(date) { manual = date }
    val cal = remember(date) {
        runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            Calendar.getInstance().apply { time = f.parse(date) ?: Date() }
        }.getOrElse { Calendar.getInstance() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it; if (it.length == 10) onDateChange(it) },
            label = { Text("日期") },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedButton(onClick = {
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    val s = String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d)
                    manual = s
                    onDateChange(s)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }) { Text("日历") }
    }
}

@Composable
fun RecordScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val currentSession by vm.currentSession.collectAsState()
    val currentPart by vm.currentExercisePart.collectAsState()
    val currentExercises by vm.currentExercises.collectAsState()
    val allGyms by vm.gyms.collectAsState()

    var date by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    }
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
    var linkedProfile by remember { mutableStateOf<EquipmentProfile?>(null) }
    var showProfilePicker by remember { mutableStateOf(false) }

    LaunchedEffect(totalSets, plannedReps) {
        val n = totalSets.toIntOrNull() ?: 1
        val r = plannedReps.toIntOrNull() ?: 0
        sets = List(n) { i -> SetInput(index = i + 1, completed = true, actual = r, failure = 0) }
    }

    val actualPartForLibrary = currentPart ?: bodyPart

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("训练信息", style = MaterialTheme.typography.titleMedium)
        DateField(date) { date = it }

        DropdownField("训练部位", bodyPart, BODY_PARTS) {
            bodyPart = it
            exercise = ""
            if (currentSession != null) {
                if (currentSession?.bodyPart != it && it != "核心") {
                    vm.endCurrentSession()
                }
            }
        }

        DropdownField("疼痛部位", painLoc, PAIN_LOCATIONS) { painLoc = it }
        Text("疼痛指数：${painScore.toInt()} / 10")
        Slider(value = painScore, onValueChange = { painScore = it }, valueRange = 0f..10f, steps = 9)
        OutlinedTextField(
            value = sessionNotes, onValueChange = { sessionNotes = it },
            label = { Text("训练备注") }, modifier = Modifier.fillMaxWidth()
        )

        if (currentSession == null) {
            Button(
                onClick = { vm.startOrResumeSession(date, bodyPart, painLoc, painScore.toInt(), sessionNotes) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始 / 继续本次训练") }
            Text("核心会合并到当天已有训练里。", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val s = currentSession!!
        val isCoreMode = actualPartForLibrary == "核心"
        Card {
            Column(Modifier.padding(10.dp)) {
                Text("当前训练：${s.date}｜${s.bodyPart}", style = MaterialTheme.typography.titleMedium)
                if (isCoreMode && s.bodyPart != "核心") {
                    Text("正在添加：训练后核心附加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
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

        Text("添加动作（当前：$actualPartForLibrary）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = exercise, onValueChange = { exercise = it },
            label = { Text("动作（可选可填）") }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXERCISES[actualPartForLibrary]?.take(4)?.forEach { e ->
                AssistChip(onClick = { exercise = e }, label = { Text(e) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXERCISES[actualPartForLibrary]?.drop(4)?.forEach { e ->
                AssistChip(onClick = { exercise = e }, label = { Text(e) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = weight, onValueChange = { weight = it },
                label = { Text("重量kg（0/空=自重，负数=助力）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = plannedReps, onValueChange = { plannedReps = it },
                label = { Text("次数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = totalSets, onValueChange = { totalSets = it },
                label = { Text("组数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = exerciseNotes, onValueChange = { exerciseNotes = it },
            label = { Text("动作备注（坐4、助力40、宽握等）") }, modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showProfilePicker = true }) {
                Text(linkedProfile?.let { "已关联：${it.equipmentName.ifBlank { it.exerciseName }}" } ?: "关联器械（可选）")
            }
            if (linkedProfile != null) {
                TextButton(onClick = { linkedProfile = null }) { Text("清除") }
            }
        }

        if (showProfilePicker) {
            ProfilePickerDialog(
                vm = vm,
                exerciseName = exercise,
                gyms = allGyms,
                onDismiss = { showProfilePicker = false },
                onSelect = { linkedProfile = it; showProfilePicker = false }
            )
        }

        Text("每组完成情况", style = MaterialTheme.typography.titleSmall)
        // 每行两列
        val rows = (sets.size + 1) / 2
        repeat(rows) { rowIndex ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val i1 = rowIndex * 2
                if (i1 < sets.size) {
                    Box(Modifier.weight(1f)) {
                        SetCard(sets[i1]) { upd ->
                            sets = sets.toMutableList().also { it[i1] = upd }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                val i2 = rowIndex * 2 + 1
                if (i2 < sets.size) {
                    Box(Modifier.weight(1f)) {
                        SetCard(sets[i2]) { upd ->
                            sets = sets.toMutableList().also { it[i2] = upd }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Button(
            onClick = {
                if (exercise.isBlank()) return@Button
                val w = weight.trim().let {
                    if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: 0.0
                }
                vm.addExerciseToCurrentSession(
                    exerciseName = exercise,
                    weight = w,
                    plannedReps = plannedReps.toIntOrNull() ?: 0,
                    totalSets = totalSets.toIntOrNull() ?: sets.size,
                    exerciseNotes = exerciseNotes,
                    sets = sets.map { Triple(it.completed, it.actual, it.failure) },
                    equipmentProfileId = linkedProfile?.id
                )
                exercise = ""
                weight = ""
                exerciseNotes = ""
                linkedProfile = null
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加到本次训练") }

        HorizontalDivider()

        Text("本次已记录动作", style = MaterialTheme.typography.titleMedium)
        val mainList = currentExercises.filter { !it.exercise.isCoreAddon }
        val coreList = currentExercises.filter { it.exercise.isCoreAddon }
        mainList.forEach { ex ->
            ExerciseRow(ex) { vm.removeExercise(ex.exercise.id) }
        }
        if (coreList.isNotEmpty()) {
            Text("训练后核心附加", style = MaterialTheme.typography.titleSmall)
            coreList.forEach { ex ->
                ExerciseRow(ex) { vm.removeExercise(ex.exercise.id) }
            }
        }
    }
}

@Composable
fun SetCard(s: SetInput, onChange: (SetInput) -> Unit) {
    Card {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.completed, onCheckedChange = {
                    onChange(s.copy(completed = it, failure = if (it) 0 else s.failure))
                })
                Text("第${s.index}组", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = s.actual.toString(),
                onValueChange = { v -> onChange(s.copy(actual = v.toIntOrNull() ?: 0)) },
                label = { Text("实际次数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            if (!s.completed) {
                OutlinedTextField(
                    value = s.failure.toString(),
                    onValueChange = { v -> onChange(s.copy(failure = v.toIntOrNull() ?: 0)) },
                    label = { Text("力竭次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ExerciseRow(ex: ExerciseWithSets, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = if (ex.exercise.weight < 0) "助力${ex.exercise.exerciseName}" else ex.exercise.exerciseName
            val done = ex.sets.count { it.completed }
            Text("$name  ${displayLoad(ex.exercise.weight)}×${ex.exercise.plannedReps}×${ex.exercise.totalSets}  完成$done/${ex.exercise.totalSets}")
            if (ex.exercise.notes.isNotBlank()) {
                Text("备注：${ex.exercise.notes}", style = MaterialTheme.typography.bodySmall)
            }
            ex.equipmentProfile?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ex.equipmentPhoto?.let { ph ->
                        AsyncImage(
                            model = fileForPath(ctx, ph.filePath),
                            contentDescription = "器械",
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("器械：${p.equipmentName.ifBlank { p.exerciseName }}（${ex.gymName}）",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onDelete) { Text("删除这个动作") }
        }
    }
}

@Composable
fun ProfilePickerDialog(
    vm: TrainingViewModel,
    exerciseName: String,
    gyms: List<Gym>,
    onDismiss: () -> Unit,
    onSelect: (EquipmentProfile) -> Unit
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<Pair<EquipmentProfile, String>>>(emptyList()) }
    LaunchedEffect(exerciseName) {
        val all = vm.getAllProfiles()
        val gymList = vm.getAllGyms()
        val gymById = gymList.associateBy { it.id }
        profiles = all
            .filter { exerciseName.isBlank() || it.exerciseName == exerciseName }
            .map { it to (gymById[it.gymId]?.name ?: "") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择器械档案") },
        text = {
            if (profiles.isEmpty()) {
                Text("没有匹配的器械档案。先到“器械”页添加。")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(profiles) { pair ->
                        val (p, g) = pair
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { onSelect(p) }
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${p.equipmentName.ifBlank { p.exerciseName }}", style = MaterialTheme.typography.titleSmall)
                                Text("动作：${p.exerciseName}｜健身房：$g", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 历史页 ====================

@Composable
fun HistoryScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val list by vm.sessions.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list) { s ->
            Card {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${s.session.date}｜${s.session.bodyPart}", style = MaterialTheme.typography.titleMedium)
                    if (s.session.painScore > 0)
                        Text("疼痛：${s.session.painLocation} ${s.session.painScore}/10",
                            color = MaterialTheme.colorScheme.error)
                    if (s.session.notes.isNotBlank())
                        Text("备注：${s.session.notes}", style = MaterialTheme.typography.bodySmall)
                    s.exercises.forEach { ex ->
                        val name = if (ex.exercise.weight < 0) "助力${ex.exercise.exerciseName}" else ex.exercise.exerciseName
                        val done = ex.sets.count { it.completed }
                        Text("· $name ${displayLoad(ex.exercise.weight)}×${ex.exercise.plannedReps}×${ex.exercise.totalSets} 完成$done/${ex.exercise.totalSets}" +
                            (if (ex.exercise.isCoreAddon) " [核心]" else ""))
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

// ==================== 器械页 ====================

@Composable
fun EquipmentScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gyms by vm.gyms.collectAsState()
    var selectedGymId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(gyms) {
        if (selectedGymId == null || gyms.none { it.id == selectedGymId }) {
            selectedGymId = gyms.firstOrNull()?.id
        }
    }
    var profiles by remember { mutableStateOf<List<EquipmentWithPhotos>>(emptyList()) }
    LaunchedEffect(selectedGymId) {
        selectedGymId?.let { gid ->
            val ps = vm.getAllProfiles().filter { it.gymId == gid }
            val allPhotos = vm.getAllProfilePhotos()
            val gymName = gyms.firstOrNull { it.id == gid }?.name ?: ""
            profiles = assembleEquipment(ps, allPhotos, gyms)
        } ?: run { profiles = emptyList() }
    }
    var showAddGym by remember { mutableStateOf(false) }
    var newGymName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<EquipmentProfile?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(gyms) { g ->
                FilterChip(
                    selected = g.id == selectedGymId,
                    onClick = { selectedGymId = g.id },
                    label = { Text(g.name) }
                )
            }
            item {
                AssistChip(
                    onClick = { showAddGym = true },
                    label = { Text("+") }
                )
            }
        }
        if (showAddGym) {
            AlertDialog(
                onDismissRequest = { showAddGym = false },
                title = { Text("添加健身房") },
                text = {
                    OutlinedTextField(
                        value = newGymName, onValueChange = { newGymName = it },
                        label = { Text("健身房名称") }, singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.addGym(newGymName); newGymName = ""; showAddGym = false
                    }) { Text("添加") }
                },
                dismissButton = { TextButton(onClick = { showAddGym = false }) { Text("取消") } }
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("器械档案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = {
                val gid = selectedGymId
                if (gid == null) return@Button
                editing = EquipmentProfile(gymId = gid, exerciseName = "")
            }) { Text("+ 添加") }
        }

        if (selectedGymId == null) {
            Text("先添加一个健身房。", Modifier.padding(12.dp))
        } else if (profiles.isEmpty()) {
            Text("当前健身房还没有器械档案。", Modifier.padding(12.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(profiles) { p ->
                    Card(
                        Modifier.fillMaxWidth(),
                        onClick = { editing = p.profile }
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            p.photos.firstOrNull()?.let { ph ->
                                AsyncImage(
                                    model = fileForPath(ctx, ph.filePath),
                                    contentDescription = "器械",
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("${p.profile.equipmentName.ifBlank { p.profile.exerciseName }}",
                                    style = MaterialTheme.typography.titleSmall)
                                Text("动作：${p.profile.exerciseName}｜照片：${p.photos.size}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { profile ->
        EquipmentEditScreen(
            vm = vm,
            initial = profile,
            gyms = gyms,
            onDone = { editing = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentEditScreen(
    vm: TrainingViewModel,
    initial: EquipmentProfile,
    gyms: List<Gym>,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(initial) }
    var newPhotos by remember { mutableStateOf(listOf<PhotoInput>()) }
    var existingPhotos by remember { mutableStateOf(listOf<EquipmentProfilePhoto>()) }
    var removedIds by remember { mutableStateOf(listOf<Long>()) }
    var pendingCamera by remember { mutableStateOf<File?>(null) }
    var exercise by remember { mutableStateOf(initial.exerciseName) }
    var equipmentName by remember { mutableStateOf(initial.equipmentName) }
    var seat by remember { mutableStateOf(initial.seat) }
    var backrest by remember { mutableStateOf(initial.backrest) }
    var grip by remember { mutableStateOf(initial.grip) }
    var weightStack by remember { mutableStateOf(initial.weightStack) }
    var pin by remember { mutableStateOf(initial.pin) }
    var assist by remember { mutableStateOf(initial.assist) }
    var pulley by remember { mutableStateOf(initial.pulley) }
    var other by remember { mutableStateOf(initial.other) }
    var notes by remember { mutableStateOf(initial.notes) }

    LaunchedEffect(initial.id) {
        if (initial.id != 0L) {
            existingPhotos = vm.loadProfilePhotos(initial.id)
        }
    }

    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCamera
        if (ok && f != null && f.exists()) {
            newPhotos = newPhotos + PhotoInput(f.relativeTo(ctx.filesDir).path, "其他")
        }
        pendingCamera = null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val p = saveImageToPrivate(ctx, uri)
                if (p != null) newPhotos = newPhotos + PhotoInput(p, "其他")
            }
        }
    }
    fun launchCamera() {
        val dir = File(ctx.filesDir, "equipment_photos").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCamera = file
        takePic.launch(uri)
    }

    val allExercises = EXERCISES.values.flatten().distinct()
    var selectedGymId by remember { mutableStateOf(initial.gymId) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (initial.id == 0L) "添加器械档案" else "编辑器械档案") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DropdownField("健身房", gyms.firstOrNull { it.id == selectedGymId }?.name ?: "未选择",
                    gyms.map { it.name }) { name ->
                    gyms.firstOrNull { it.name == name }?.let { selectedGymId = it.id }
                }
                OutlinedTextField(
                    value = exercise, onValueChange = { exercise = it },
                    label = { Text("动作（可选可填）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allExercises.take(10)) { e ->
                        AssistChip(onClick = { exercise = e }, label = { Text(e) })
                    }
                }
                OutlinedTextField(
                    value = equipmentName, onValueChange = { equipmentName = it },
                    label = { Text("器械名称（大剪刀、助力引体机等）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(seat, { seat = it }, label = { Text("座位") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(backrest, { backrest = it }, label = { Text("靠背") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(grip, { grip = it }, label = { Text("握把") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(weightStack, { weightStack = it }, label = { Text("配重") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pin, { pin = it }, label = { Text("插销") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(assist, { assist = it }, label = { Text("助力") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pulley, { pulley = it }, label = { Text("滑轮") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(other, { other = it }, label = { Text("其他") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { launchCamera() }) { Text("拍照片") }
                    OutlinedButton(onClick = { pick.launch("image/*") }) { Text("相册") }
                }
                existingPhotos.forEach { ph ->
                    Card {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = fileForPath(ctx, ph.filePath),
                                contentDescription = ph.tag,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(ph.tag, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                vm.deleteProfilePhoto(ctx, ph.id, ph.filePath)
                                existingPhotos = existingPhotos.filter { it.id != ph.id }
                            }) { Text("删除") }
                        }
                    }
                }
                newPhotos.forEachIndexed { i, p ->
                    Card {
                        Column(Modifier.padding(6.dp)) {
                            AsyncImage(
                                model = fileForPath(ctx, p.filePath),
                                contentDescription = "新照片",
                                modifier = Modifier.fillMaxWidth().height(120.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PHOTO_TAGS.forEach { t ->
                                    FilterChip(
                                        selected = p.tag == t,
                                        onClick = {
                                            newPhotos = newPhotos.toMutableList().also { it[i] = it[i].copy(tag = t) }
                                        },
                                        label = { Text(t) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val gid = selectedGymId ?: return@TextButton
                if (exercise.isBlank()) return@TextButton
                val updated = profile.copy(
                    gymId = gid, exerciseName = exercise, equipmentName = equipmentName,
                    seat = seat, backrest = backrest, grip = grip, weightStack = weightStack,
                    pin = pin, assist = assist, pulley = pulley, other = other, notes = notes
                )
                scope.launch {
                    vm.saveProfile(updated, newPhotos, removedIds)
                    onDone()
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) {
                    TextButton(onClick = {
                        scope.launch { vm.deleteProfile(ctx, initial.id); onDone() }
                    }) { Text("删除") }
                }
                TextButton(onClick = onDone) { Text("取消") }
            }
        }
    )
}

// ==================== 导出页 ====================

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
                            rangeDesc = if (from.isBlank() && to.isBlank()) "全部"
                                else "${from.ifBlank { "开始" }} ~ ${to.ifBlank { "至今" }}",
                            partDesc = if (selectedParts.isEmpty()) "全部"
                                else selectedParts.joinToString("、")
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
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "导出中..." else "导出 Markdown 并分享") }
    }
}

// ==================== 设置页 ====================

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = themeMode == m, onClick = { onThemeModeChange(m) })
                Text(m.label)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("数据存储", style = MaterialTheme.typography.titleMedium)
        Text("训练记录存本机 App 私有数据库；器械照片存 App 私有目录 files/equipment_photos。", style = MaterialTheme.typography.bodySmall)
        Text("覆盖安装不丢，卸载/清 App 数据才丢。建议每周导出 Markdown 备份。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("版本", style = MaterialTheme.typography.titleMedium)
        Text("训练记录 1.3 · 四分化 · 核心合并 · 器械档案 · 暗黑模式", style = MaterialTheme.typography.bodySmall)
    }
}
