@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.traininglog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun EquipmentScreen(
    vm: TrainingViewModel,
    showAddDialog: Boolean = false,
    onShowAddConsumed: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val allProfiles by vm.allProfiles.collectAsState()
    var filterAction by remember { mutableStateOf<String?>(null) }
    var filterType by remember { mutableStateOf<String?>(null) }
    var filterGym by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EquipmentProfile?>(null) }

    LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            editing = EquipmentProfile(exerciseName = "")
            onShowAddConsumed()
        }
    }

    val actions = remember(allProfiles) { allProfiles.map { it.exerciseName }.distinct().sorted() }
    val types = remember(allProfiles) { allProfiles.map { it.equipmentType }.distinct().sorted() }
    val gyms = remember(allProfiles) { allProfiles.map { it.gymName }.filter { it.isNotBlank() }.distinct().sorted() }

    val filtered = remember(allProfiles, filterAction, filterType, filterGym) {
        allProfiles.filter { p ->
            (filterAction == null || p.exerciseName == filterAction) &&
            (filterType == null || p.equipmentType == filterType) &&
            (filterGym == null || p.gymName == filterGym)
        }
    }

    val photos by vm.allProfiles.collectAsState()
    var profilesWithPhotos by remember { mutableStateOf<List<EquipmentWithPhotos>>(emptyList()) }
    LaunchedEffect(filtered) {
        val allPhotos = vm.getAllProfilePhotos()
        profilesWithPhotos = assembleEquipment(filtered, allPhotos)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterRow("动作", actions, filterAction) { filterAction = it }
            FilterRow("类型", types, filterType) { filterType = it }
            FilterRow("健身房", gyms, filterGym) { filterGym = it }
        }
        HorizontalDivider()

        if (profilesWithPhotos.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有器械档案。点右上角 ⋮ 添加。", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(profilesWithPhotos) { p ->
                    Card(onClick = { editing = p.profile }, modifier = Modifier.fillMaxWidth()) {
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
                                Text(
                                    p.profile.equipmentName.ifBlank { p.profile.exerciseName },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                val extra = buildList {
                                    add("动作：${p.profile.exerciseName}")
                                    if (p.profile.equipmentType.isNotBlank() && p.profile.equipmentType != "未填")
                                        add(p.profile.equipmentType)
                                    if (p.profile.gymName.isNotBlank()) add(p.profile.gymName)
                                    add("照片：${p.photos.size}")
                                }
                                Text(extra.joinToString("｜"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    editing?.let { profile ->
        EquipmentEditDialog(
            vm = vm,
            initial = profile,
            onDone = { saved ->
                editing = null
            }
        )
    }
}

@Composable
private fun FilterRow(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label：", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text("全部") }
                )
            }
            items(options) { o ->
                FilterChip(
                    selected = selected == o,
                    onClick = { onSelect(o) },
                    label = { Text(o) }
                )
            }
        }
    }
}

@Composable
fun ProfilePickerDialog(
    vm: TrainingViewModel,
    exerciseName: String,
    onDismiss: () -> Unit,
    onSelect: (EquipmentProfile) -> Unit
) {
    var profiles by remember { mutableStateOf<List<EquipmentProfile>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() {
        LaunchedEffect(exerciseName) {
            profiles = vm.getAllProfiles().filter { exerciseName.isBlank() || it.exerciseName == exerciseName }
        }
    }
    LaunchedEffect(exerciseName) {
        profiles = vm.getAllProfiles().filter { exerciseName.isBlank() || it.exerciseName == exerciseName }
    }

    if (showAddDialog) {
        EquipmentEditDialog(
            vm = vm,
            initial = EquipmentProfile(exerciseName = exerciseName),
            onDone = { saved ->
                showAddDialog = false
                if (saved != null) {
                    profiles = vm.getAllProfiles().filter { exerciseName.isBlank() || it.exerciseName == exerciseName }
                    onSelect(saved)
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择器械档案") },
        text = {
            if (profiles.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FilledIconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineLarge)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(profiles.size) { i ->
                            val p = profiles[i]
                            Card(onClick = { onSelect(p) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(p.equipmentName.ifBlank { p.exerciseName },
                                        style = MaterialTheme.typography.titleSmall)
                                    val extra = buildList {
                                        add("动作：${p.exerciseName}")
                                        if (p.equipmentType.isNotBlank() && p.equipmentType != "未填")
                                            add(p.equipmentType)
                                        if (p.gymName.isNotBlank()) add(p.gymName)
                                    }
                                    Text(extra.joinToString("｜"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ 添加器械") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentEditDialog(
    vm: TrainingViewModel,
    initial: EquipmentProfile,
    onDone: (EquipmentProfile?) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exercise by remember { mutableStateOf(initial.exerciseName) }
    var equipmentName by remember { mutableStateOf(initial.equipmentName) }
    var equipmentType by remember { mutableStateOf(initial.equipmentType.ifBlank { "未填" }) }
    var gymName by remember { mutableStateOf(initial.gymName) }
    var seat by remember { mutableStateOf(initial.seat) }
    var backrest by remember { mutableStateOf(initial.backrest) }
    var grip by remember { mutableStateOf(initial.grip) }
    var weightStack by remember { mutableStateOf(initial.weightStack) }
    var pin by remember { mutableStateOf(initial.pin) }
    var assist by remember { mutableStateOf(initial.assist) }
    var pulley by remember { mutableStateOf(initial.pulley) }
    var other by remember { mutableStateOf(initial.other) }
    var notes by remember { mutableStateOf(initial.notes) }
    var existingPhotos by remember { mutableStateOf(listOf<EquipmentProfilePhoto>()) }
    var newPhotos by remember { mutableStateOf(listOf<PhotoInput>()) }
    var pendingCamera by remember { mutableStateOf<File?>(null) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var refCount by remember { mutableStateOf(0) }

    LaunchedEffect(initial.id) {
        if (initial.id != 0L) existingPhotos = vm.loadProfilePhotos(initial.id)
    }

    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCamera
        if (ok && f != null && f.exists()) newPhotos = newPhotos + PhotoInput(f.relativeTo(ctx.filesDir).path, "其他")
        pendingCamera = null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val p = saveImageToPrivate(ctx, uri)
            if (p != null) newPhotos = newPhotos + PhotoInput(p, "其他")
        }
    }
    fun launchCamera() {
        val dir = File(ctx.filesDir, "equipment_photos").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCamera = file
        takePic.launch(uri)
    }

    val allExercises = DEFAULT_EXERCISES.values.flatten().distinct()

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (initial.id == 0L) "添加器械档案" else "编辑器械档案") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(exercise, { exercise = it }, label = { Text("动作") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allExercises.size) { i ->
                        AssistChip(onClick = { exercise = allExercises[i] }, label = { Text(allExercises[i]) })
                    }
                }
                OutlinedTextField(equipmentName, { equipmentName = it }, label = { Text("器械名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(
                        onClick = { showTypeMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("器械类型：$equipmentType") }
                    DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        EQUIPMENT_TYPES.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { equipmentType = t; showTypeMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(gymName, { gymName = it }, label = { Text("健身房") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
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
                    OutlinedButton(onClick = { launchCamera() }) { Text("拍照") }
                    OutlinedButton(onClick = { pick.launch("image/*") }) { Text("相册") }
                }
                existingPhotos.forEach { ph ->
                    Card {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = fileForPath(ctx, ph.filePath), contentDescription = ph.tag,
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(ph.tag, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                vm.deleteProfilePhoto(ph.id, ph.filePath, ctx.filesDir)
                                existingPhotos = existingPhotos.filter { it.id != ph.id }
                            }) { Text("删除") }
                        }
                    }
                }
                newPhotos.forEachIndexed { i, p ->
                    Card {
                        Column(Modifier.padding(6.dp)) {
                            AsyncImage(model = fileForPath(ctx, p.filePath), contentDescription = "新照片",
                                modifier = Modifier.fillMaxWidth().height(120.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PHOTO_TAGS.forEach { t ->
                                    FilterChip(selected = p.tag == t,
                                        onClick = { newPhotos = newPhotos.toMutableList().also { it[i] = it[i].copy(tag = t) } },
                                        label = { Text(t) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (exercise.isBlank()) return@TextButton
                val updated = initial.copy(
                    exerciseName = exercise, equipmentName = equipmentName,
                    equipmentType = equipmentType, gymName = gymName,
                    seat = seat, backrest = backrest, grip = grip, weightStack = weightStack,
                    pin = pin, assist = assist, pulley = pulley, other = other, notes = notes
                )
                scope.launch {
                    vm.saveProfile(updated, newPhotos, emptyList())
                    val saved = vm.getAllProfiles().firstOrNull {
                        it.exerciseName == exercise && it.equipmentName == equipmentName &&
                        it.equipmentType == equipmentType && it.gymName == gymName
                    }
                    onDone(saved)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) {
                    TextButton(onClick = {
                        scope.launch {
                            refCount = vm.countRefsToProfile(initial.id)
                            showDeleteConfirm = true
                        }
                    }) { Text("删除") }
                }
                TextButton(onClick = { onDone(null) }) { Text("取消") }
            }
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除器械档案") },
            text = {
                Text(
                    if (refCount > 0)
                        "有 $refCount 条训练记录引用了该器械，删除后关联将失效。确定删除？"
                    else
                        "确认删除该器械档案？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.deleteProfile(initial.id, ctx.filesDir)
                        showDeleteConfirm = false
                        onDone(null)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}
