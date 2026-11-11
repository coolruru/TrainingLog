package com.example.traininglog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun EquipmentScreen(vm: TrainingViewModel) {
    val ctx = LocalContext.current
    val gyms by vm.gyms.collectAsState()
    var selectedGymId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(gyms) {
        if (selectedGymId == null || gyms.none { it.id == selectedGymId }) {
            selectedGymId = gyms.firstOrNull()?.id
        }
    }
    var profiles by remember { mutableStateOf<List<EquipmentWithPhotos>>(emptyList()) }
    LaunchedEffect(selectedGymId, gyms) {
        val gid = selectedGymId
        if (gid == null) profiles = emptyList()
        else {
            val ps = vm.getAllProfiles().filter { it.gymId == gid }
            profiles = assembleEquipment(ps, vm.getAllProfilePhotos(), gyms)
        }
    }
    var showAddGym by remember { mutableStateOf(false) }
    var newGymName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<EquipmentProfile?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(gyms) { g ->
                FilterChip(selected = g.id == selectedGymId, onClick = { selectedGymId = g.id }, label = { Text(g.name) })
            }
            item { AssistChip(onClick = { showAddGym = true }, label = { Text("+") }) }
        }
        if (showAddGym) {
            AlertDialog(
                onDismissRequest = { showAddGym = false },
                title = { Text("添加健身房") },
                text = { OutlinedTextField(value = newGymName, onValueChange = { newGymName = it }, label = { Text("名称") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.addGym(newGymName); newGymName = ""; showAddGym = false }) { Text("添加") } },
                dismissButton = { TextButton(onClick = { showAddGym = false }) { Text("取消") } }
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("器械档案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = {
                val gid = selectedGymId ?: return@Button
                editing = EquipmentProfile(gymId = gid, exerciseName = "")
            }) { Text("+ 添加") }
        }

        if (selectedGymId == null) {
            Text("先添加一个健身房。", Modifier.padding(12.dp))
        } else if (profiles.isEmpty()) {
            Text("当前健身房还没有器械档案。", Modifier.padding(12.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(profiles) { p ->
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
                                Text(p.profile.equipmentName.ifBlank { p.profile.exerciseName }, style = MaterialTheme.typography.titleSmall)
                                Text("动作：${p.profile.exerciseName}｜照片：${p.photos.size}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { profile ->
        EquipmentEditDialog(vm = vm, initial = profile, gyms = gyms, onDone = { editing = null })
    }
}

@Composable
private fun EquipmentEditDialog(
    vm: TrainingViewModel,
    initial: EquipmentProfile,
    gyms: List<Gym>,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var existingPhotos by remember { mutableStateOf(listOf<EquipmentProfilePhoto>()) }
    var newPhotos by remember { mutableStateOf(listOf<PhotoInput>()) }
    var pendingCamera by remember { mutableStateOf<File?>(null) }
    var selectedGymId by remember { mutableStateOf(initial.gymId) }

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
        onDismissRequest = onDone,
        title = { Text(if (initial.id == 0L) "添加器械档案" else "编辑器械档案") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DropdownField("健身房", gyms.firstOrNull { it.id == selectedGymId }?.name ?: "未选择", gyms.map { it.name }) { name ->
                    gyms.firstOrNull { it.name == name }?.let { selectedGymId = it.id }
                }
                OutlinedTextField(exercise, { exercise = it }, label = { Text("动作") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allExercises.size) { i ->
                        AssistChip(onClick = { exercise = allExercises[i] }, label = { Text(allExercises[i]) })
                    }
                }
                OutlinedTextField(equipmentName, { equipmentName = it }, label = { Text("器械名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                            AsyncImage(
                                model = fileForPath(ctx, ph.filePath),
                                contentDescription = ph.tag,
                                modifier = Modifier.size(64.dp)
                            )
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
                            AsyncImage(
                                model = fileForPath(ctx, p.filePath),
                                contentDescription = "新照片",
                                modifier = Modifier.fillMaxWidth().height(120.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PHOTO_TAGS.forEach { t ->
                                    FilterChip(
                                        selected = p.tag == t,
                                        onClick = { newPhotos = newPhotos.toMutableList().also { it[i] = it[i].copy(tag = t) } },
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
                val updated = initial.copy(
                    gymId = gid, exerciseName = exercise, equipmentName = equipmentName,
                    seat = seat, backrest = backrest, grip = grip, weightStack = weightStack,
                    pin = pin, assist = assist, pulley = pulley, other = other, notes = notes
                )
                scope.launch { vm.saveProfile(updated, newPhotos, emptyList()); onDone() }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) {
                    TextButton(onClick = {
                        scope.launch { vm.deleteProfile(initial.id, ctx.filesDir); onDone() }
                    }) { Text("删除") }
                }
                TextButton(onClick = onDone) { Text("取消") }
            }
        }
    )
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
