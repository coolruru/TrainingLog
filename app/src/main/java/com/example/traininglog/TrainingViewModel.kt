package com.example.traininglog

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class BlockStatus { IDLE, RUNNING, DONE, FAILED }

data class ActiveBlock(
    val setIndex: Int,
    val status: BlockStatus = BlockStatus.IDLE,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val actualReps: Int = 0,
    val failureReps: Int = 0,
    val restTakenMs: Long = 0L
)

data class ActiveExercise(
    val exerciseName: String,
    val weight: Double,
    val plannedReps: Int,
    val totalSets: Int,
    val restSeconds: Int,
    val notes: String,
    val isCoreAddon: Boolean,
    val equipmentProfileId: Long?,
    val blocks: List<ActiveBlock>,
    val skipped: Boolean = false
)

data class ActivePainRecord(val location: String, val score: Int, val note: String)

data class ActiveWorkout(
    val date: String,
    val bodyPart: String,
    val goal: String,
    val sessionNotes: String,
    val startMs: Long,
    val exercises: List<ActiveExercise>,
    val painRecords: List<ActivePainRecord>
)

class TrainingViewModel(private val dao: TrainingDao) : ViewModel() {

    val sessions: StateFlow<List<SessionWithExercises>> = combine(
        combine(dao.observeSessions(), dao.observeAllExercises(), dao.observeAllSets()) { s, e, st -> Triple(s, e, st) },
        combine(dao.observeAllProfiles(), dao.observeAllProfilePhotos(), dao.observeGyms()) { p, ph, g -> Triple(p, ph, g) },
        dao.observeAllPainRecords()
    ) { (s, e, st), (p, ph, g), pr -> assembleSessions(s, e, st, p, ph, g, pr) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gyms: StateFlow<List<Gym>> = dao.observeGyms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val templates: StateFlow<List<WorkoutTemplateWithExercises>> = combine(
        dao.observeTemplates(), dao.observeAllTemplateExercises()
    ) { t, e -> assembleTemplates(t, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeWorkout = MutableStateFlow<ActiveWorkout?>(null)
    val activeWorkout: StateFlow<ActiveWorkout?> = _activeWorkout

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    private fun emitEvent(msg: String) { _events.tryEmit(msg) }

    // ===== 计时 =====

    fun startWorkout(plan: WorkoutPlan) {
        _activeWorkout.value = ActiveWorkout(
            date = plan.date, bodyPart = plan.bodyPart, goal = plan.goal,
            sessionNotes = plan.sessionNotes, startMs = System.currentTimeMillis(),
            exercises = plan.exercises.map { ep ->
                ActiveExercise(
                    exerciseName = ep.exerciseName, weight = ep.weight,
                    plannedReps = ep.plannedReps, totalSets = ep.totalSets,
                    restSeconds = ep.restSeconds, notes = ep.notes,
                    isCoreAddon = ep.isCoreAddon, equipmentProfileId = ep.equipmentProfileId,
                    blocks = (1..ep.totalSets).map { i ->
                        ActiveBlock(setIndex = i, status = BlockStatus.IDLE, actualReps = ep.plannedReps)
                    }
                )
            },
            painRecords = emptyList()
        )
    }

    fun clickBlock(exIdx: Int, setIdx: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val block = ex.blocks.getOrNull(setIdx) ?: return
        val now = System.currentTimeMillis()

        // 1. 结束当前 RUNNING 方块
        if (block.status == BlockStatus.RUNNING) {
            val newBlock = block.copy(status = BlockStatus.DONE, endMs = now)
            val newEx = ex.copy(blocks = ex.blocks.toMutableList().also { it[setIdx] = newBlock })
            _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
            return
        }

        // 2. 只有 IDLE 才能开始
        if (block.status != BlockStatus.IDLE) return

        // 3. 全局只能一个 RUNNING
        val anyRunning = w.exercises.any { e -> e.blocks.any { it.status == BlockStatus.RUNNING } }
        if (anyRunning) { emitEvent("请先结束当前组"); return }

        // 4. 同动作内按组顺序
        for (i in 0 until setIdx) {
            val b = ex.blocks[i]
            if (b.status != BlockStatus.DONE && b.status != BlockStatus.FAILED) {
                emitEvent("请先完成第${i + 1}组"); return
            }
        }

        // 5. 开始新一组；把休息时长写入上一组
        var newW = w
        findPrevCompleted(w, exIdx, setIdx)?.let { (pe, pi) ->
            val prevEx = newW.exercises[pe]
            val prevBlock = prevEx.blocks[pi]
            if (prevBlock.endMs > 0) {
                val updatedPrev = prevBlock.copy(restTakenMs = now - prevBlock.endMs)
                val updatedPrevEx = prevEx.copy(blocks = prevEx.blocks.toMutableList().also { it[pi] = updatedPrev })
                newW = newW.copy(exercises = newW.exercises.toMutableList().also { it[pe] = updatedPrevEx })
            }
        }
        val curEx = newW.exercises[exIdx]
        val newBlock = curEx.blocks[setIdx].copy(status = BlockStatus.RUNNING, startMs = now, actualReps = curEx.plannedReps)
        val newEx = curEx.copy(blocks = curEx.blocks.toMutableList().also { it[setIdx] = newBlock })
        _activeWorkout.value = newW.copy(exercises = newW.exercises.toMutableList().also { it[exIdx] = newEx })
    }

    private fun findPrevCompleted(w: ActiveWorkout, exIdx: Int, setIdx: Int): Pair<Int, Int>? {
        for (i in setIdx - 1 downTo 0) {
            val b = w.exercises[exIdx].blocks[i]
            if (b.status == BlockStatus.DONE || b.status == BlockStatus.FAILED) return exIdx to i
        }
        for (e in exIdx - 1 downTo 0) {
            val ex = w.exercises[e]
            if (ex.skipped) continue
            for (i in ex.blocks.indices.reversed()) {
                val b = ex.blocks[i]
                if (b.status == BlockStatus.DONE || b.status == BlockStatus.FAILED) return e to i
            }
        }
        return null
    }

    fun markBlockFailed(exIdx: Int, setIdx: Int, actualReps: Int, failureReps: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val block = ex.blocks.getOrNull(setIdx) ?: return
        if (block.status != BlockStatus.RUNNING) return
        val newBlock = block.copy(
            status = BlockStatus.FAILED, endMs = System.currentTimeMillis(),
            actualReps = actualReps, failureReps = failureReps
        )
        val newEx = ex.copy(blocks = ex.blocks.toMutableList().also { it[setIdx] = newBlock })
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
    }

    fun addExerciseDuringWorkout(ep: ExercisePlan) {
        val w = _activeWorkout.value ?: return
        val newEx = ActiveExercise(
            exerciseName = ep.exerciseName, weight = ep.weight, plannedReps = ep.plannedReps,
            totalSets = ep.totalSets, restSeconds = ep.restSeconds, notes = ep.notes,
            isCoreAddon = ep.isCoreAddon, equipmentProfileId = ep.equipmentProfileId,
            blocks = (1..ep.totalSets).map { i -> ActiveBlock(setIndex = i, status = BlockStatus.IDLE, actualReps = ep.plannedReps) }
        )
        _activeWorkout.value = w.copy(exercises = w.exercises + newEx)
    }

    fun skipExercise(exIdx: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = ex.copy(skipped = true) })
    }

    fun addPainRecord(location: String, score: Int, note: String) {
        val w = _activeWorkout.value ?: return
        _activeWorkout.value = w.copy(painRecords = w.painRecords + ActivePainRecord(location, score, note))
    }

    fun endWorkout() = viewModelScope.launch {
        val w = _activeWorkout.value ?: return@launch
        val endMs = System.currentTimeMillis()
        val duration = ((endMs - w.startMs) / 1000).toInt()

        val existing = dao.getSessionByDateAndPart(w.date, w.bodyPart)
        val sessionId = if (existing != null) {
            dao.updateSession(existing.copy(
                goal = if (w.goal.isNotBlank()) w.goal else existing.goal,
                notes = if (w.sessionNotes.isNotBlank()) w.sessionNotes else existing.notes,
                startTime = if (existing.startTime == 0L) w.startMs else existing.startTime,
                endTime = endMs,
                durationSeconds = existing.durationSeconds + duration
            ))
            existing.id
        } else {
            dao.insertSession(Session(
                date = w.date, bodyPart = w.bodyPart, goal = w.goal, notes = w.sessionNotes,
                startTime = w.startMs, endTime = endMs, durationSeconds = duration, createdAt = w.startMs
            ))
        }

        w.exercises.forEachIndexed { exIdx, ex ->
            if (ex.skipped) return@forEachIndexed
            val exId = dao.insertExercise(ExerciseEntry(
                sessionId = sessionId, exerciseName = ex.exerciseName, weight = ex.weight,
                plannedReps = ex.plannedReps, totalSets = ex.totalSets, notes = ex.notes,
                isCoreAddon = ex.isCoreAddon, equipmentProfileId = ex.equipmentProfileId,
                restSeconds = ex.restSeconds, orderIndex = exIdx
            ))
            dao.insertSets(ex.blocks.map { b ->
                SetEntry(
                    exerciseId = exId, setIndex = b.setIndex,
                    completed = b.status == BlockStatus.DONE,
                    actualReps = b.actualReps, failureReps = b.failureReps,
                    durationSeconds = if (b.endMs > 0 && b.startMs > 0) ((b.endMs - b.startMs) / 1000).toInt() else 0,
                    restTakenSeconds = (b.restTakenMs / 1000).toInt()
                )
            })
        }

        w.painRecords.forEach { pr ->
            dao.insertPainRecord(PainRecord(sessionId = sessionId, location = pr.location, score = pr.score, note = pr.note))
        }
        _activeWorkout.value = null
    }

    fun cancelWorkout() { _activeWorkout.value = null }

    // ===== 添加记录（不进入计时）=====

    fun addRecordDirectly(plan: WorkoutPlan) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val existing = dao.getSessionByDateAndPart(plan.date, plan.bodyPart)
        val sessionId = if (existing != null) {
            dao.updateSession(existing.copy(
                goal = if (plan.goal.isNotBlank()) plan.goal else existing.goal,
                notes = if (plan.sessionNotes.isNotBlank()) plan.sessionNotes else existing.notes,
                durationSeconds = if (plan.durationOverrideSeconds > 0) plan.durationOverrideSeconds else existing.durationSeconds
            ))
            existing.id
        } else {
            dao.insertSession(Session(
                date = plan.date, bodyPart = plan.bodyPart,
                goal = plan.goal, notes = plan.sessionNotes,
                durationSeconds = plan.durationOverrideSeconds,
                createdAt = now
            ))
        }
        plan.exercises.forEachIndexed { idx, ep ->
            val exId = dao.insertExercise(ExerciseEntry(
                sessionId = sessionId, exerciseName = ep.exerciseName, weight = ep.weight,
                plannedReps = ep.plannedReps, totalSets = ep.totalSets, notes = ep.notes,
                isCoreAddon = ep.isCoreAddon, equipmentProfileId = ep.equipmentProfileId,
                restSeconds = ep.restSeconds, orderIndex = idx
            ))
            dao.insertSets((1..ep.totalSets).map { i ->
                SetEntry(exerciseId = exId, setIndex = i, completed = true, actualReps = ep.plannedReps, failureReps = 0)
            })
        }
    }

    // ===== 记录 → 模板 =====

    fun saveSessionAsTemplate(session: SessionWithExercises, name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val tid = dao.insertTemplate(WorkoutTemplate(name = name, bodyPart = session.session.bodyPart, goal = session.session.goal))
        session.exercises.forEachIndexed { idx, ex ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = tid,
                exerciseName = ex.exercise.exerciseName,
                weight = ex.exercise.weight,
                totalSets = ex.exercise.totalSets,
                plannedReps = ex.exercise.plannedReps,
                restSeconds = ex.exercise.restSeconds,
                notes = ex.exercise.notes,
                orderIndex = idx
            ))
        }
        onDone()
    }

    // ===== 动作库 =====

    fun observeLibrary(bodyPart: String) = dao.observeLibraryByPart(bodyPart)
    suspend fun addLibraryItem(bodyPart: String, name: String): Boolean {
        val t = name.trim(); if (t.isEmpty()) return false
        val existing = dao.findLibraryItem(bodyPart, t)
        return if (existing != null) false
        else { dao.insertLibraryItem(ExerciseLibraryItem(bodyPart = bodyPart, name = t)); true }
    }
    fun deleteLibraryItem(id: Long) = viewModelScope.launch { dao.deleteLibraryItem(id) }
    fun restoreDefaultLibrary() = viewModelScope.launch {
        DEFAULT_EXERCISES.forEach { (part, names) ->
            names.forEach { name -> dao.insertLibraryItem(ExerciseLibraryItem(bodyPart = part, name = name)) }
        }
    }
    suspend fun getAllLibrary() = dao.getAllLibrary()

    // ===== 模板 =====

    fun saveTemplate(name: String, bodyPart: String, goal: String, exercises: List<ExercisePlan>) = viewModelScope.launch {
        val tid = dao.insertTemplate(WorkoutTemplate(name = name, bodyPart = bodyPart, goal = goal))
        exercises.forEachIndexed { idx, ep ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = tid, exerciseName = ep.exerciseName, weight = ep.weight,
                totalSets = ep.totalSets, plannedReps = ep.plannedReps,
                restSeconds = ep.restSeconds, notes = ep.notes, orderIndex = idx
            ))
        }
    }
    fun deleteTemplate(id: Long) = viewModelScope.launch { dao.deleteTemplate(id) }
    fun duplicateTemplate(t: WorkoutTemplateWithExercises) = viewModelScope.launch {
        val current = templates.value.map { it.template.name }.toSet()
        val base = t.template.name
        var n = 2; var newName: String
        do { newName = "$base$n"; n++ } while (newName in current)
        val newId = dao.insertTemplate(WorkoutTemplate(name = newName, bodyPart = t.template.bodyPart, goal = t.template.goal))
        t.exercises.forEachIndexed { idx, ex ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = newId, exerciseName = ex.exerciseName, weight = ex.weight,
                totalSets = ex.totalSets, plannedReps = ex.plannedReps,
                restSeconds = ex.restSeconds, notes = ex.notes, orderIndex = idx
            ))
        }
    }

    // ===== 器械 =====

    fun addGym(name: String) = viewModelScope.launch {
        val t = name.trim(); if (t.isEmpty()) return@launch
        if (dao.findGymByName(t) == null) dao.insertGym(Gym(name = t))
    }
    fun saveProfile(profile: EquipmentProfile, photos: List<PhotoInput>, removedPhotoIds: List<Long>) = viewModelScope.launch {
        val pid = if (profile.id == 0L) dao.insertProfile(profile) else { dao.updateProfile(profile); profile.id }
        photos.forEach { dao.insertProfilePhoto(EquipmentProfilePhoto(profileId = pid, filePath = it.filePath, tag = it.tag)) }
        removedPhotoIds.forEach { dao.deleteProfilePhoto(it) }
    }
    suspend fun loadProfilePhotos(profileId: Long) = dao.getPhotosByProfile(profileId)
    fun deleteProfile(profileId: Long, filesDir: File) = viewModelScope.launch {
        val photos = dao.getPhotosByProfile(profileId)
        photos.forEach { p -> runCatching { File(filesDir, p.filePath).delete() } }
        dao.clearExerciseRefsToProfile(profileId)
        dao.deleteProfile(profileId)
    }
    fun deleteProfilePhoto(photoId: Long, filePath: String, filesDir: File) = viewModelScope.launch {
        runCatching { File(filesDir, filePath).delete() }
        dao.deleteProfilePhoto(photoId)
    }

    fun deleteSession(id: Long) = viewModelScope.launch { dao.deleteSession(id) }

    // ===== 导出/导入 =====

    suspend fun exportSessions(from: String?, to: String?, parts: List<String>): List<SessionWithExercises> {
        val s = if (from != null && to != null) dao.getBetween(from, to) else dao.getAllSessions()
        val all = assembleSessions(s, dao.getAllExercises(), dao.getAllSets(),
            dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllGyms(), dao.getAllPainRecords())
        return if (parts.isEmpty()) all else all.filter { it.session.bodyPart in parts }
    }

    suspend fun exportToJson(ctx: Context, uri: Uri, from: String?, to: String?, parts: List<String>): Boolean {
        val sessions = exportSessions(from, to, parts)
        val profiles = assembleEquipment(dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllGyms())
        val json = ImportExport.buildJson(sessions, templates.value, profiles, dao.getAllGyms(), dao.getAllLibrary())
        return ImportExport.exportJson(ctx, json, uri)
    }

    suspend fun exportToZip(ctx: Context, uri: Uri, from: String?, to: String?, parts: List<String>): Boolean {
        val sessions = exportSessions(from, to, parts)
        val profiles = assembleEquipment(dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllGyms())
        val gyms = dao.getAllGyms()
        val library = dao.getAllLibrary()
        val json = ImportExport.buildJson(sessions, templates.value, profiles, gyms, library)
        val photos = dao.getAllProfilePhotos().map { it.filePath }.distinct()
        return ImportExport.exportZip(ctx, json, photos, uri)
    }

    suspend fun importFrom(ctx: Context, uri: Uri): ImportResult = ImportExport.importFromUri(ctx, uri)

    suspend fun getAllProfiles() = dao.getAllProfiles()
    suspend fun getAllProfilePhotos() = dao.getAllProfilePhotos()
    suspend fun getAllGyms() = dao.getAllGyms()
}

class TrainingViewModelFactory(private val dao: TrainingDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TrainingViewModel(dao) as T
    }
}
