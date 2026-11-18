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
        combine(dao.observeAllProfiles(), dao.observeAllProfilePhotos(), dao.observeAllPainRecords()) { p, ph, pr -> Triple(p, ph, pr) }
    ) { (s, e, st), (p, ph, pr) ->
        assembleSessions(s, e, st, p, ph, pr)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val templates: StateFlow<List<WorkoutTemplateWithExercises>> = combine(
        dao.observeTemplates(), dao.observeAllTemplateExercises()
    ) { t, e -> assembleTemplates(t, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLibrary: StateFlow<List<ExerciseLibraryItem>> = dao.observeAllLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProfiles: StateFlow<List<EquipmentProfile>> = dao.observeAllProfiles()
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

        if (block.status == BlockStatus.RUNNING) {
            val newBlock = block.copy(status = BlockStatus.DONE, endMs = now)
            val newEx = ex.copy(blocks = ex.blocks.toMutableList().also { it[setIdx] = newBlock })
            _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
            return
        }
        if (block.status != BlockStatus.IDLE) return
        val anyRunning = w.exercises.any { e -> e.blocks.any { it.status == BlockStatus.RUNNING } }
        if (anyRunning) { emitEvent("请先结束当前组"); return }
        for (i in 0 until setIdx) {
            val b = ex.blocks[i]
            if (b.status != BlockStatus.DONE && b.status != BlockStatus.FAILED) {
                emitEvent("请先完成第${i + 1}组"); return
            }
        }
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

    fun markBlockFailed(exIdx: Int, setIdx: Int, actualReps: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val block = ex.blocks.getOrNull(setIdx) ?: return
        if (block.status != BlockStatus.RUNNING && block.status != BlockStatus.DONE) return
        val now = System.currentTimeMillis()
        val newBlock = block.copy(
            status = BlockStatus.FAILED,
            endMs = if (block.endMs > 0) block.endMs else now,
            actualReps = actualReps
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
        viewModelScope.launch { ensureLibraryItem(w.bodyPart, ep.exerciseName) }
    }

    fun updateActiveExercise(
        exIdx: Int,
        weight: Double,
        plannedReps: Int,
        totalSets: Int,
        restSeconds: Int,
        notes: String,
        equipmentProfileId: Long?
    ) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val startedCount = ex.blocks.count { it.status != BlockStatus.IDLE }
        val minSets = maxOf(startedCount, 1)
        if (totalSets < minSets) {
            emitEvent("组数不能少于已开始/完成的 $minSets 组")
            return
        }
        val newBlocks = when {
            totalSets > ex.blocks.size -> ex.blocks + (ex.blocks.size + 1..totalSets).map { i ->
                ActiveBlock(setIndex = i, status = BlockStatus.IDLE, actualReps = plannedReps)
            }
            totalSets < ex.blocks.size -> ex.blocks.take(totalSets)
            else -> ex.blocks
        }
        val newEx = ex.copy(
            weight = weight, plannedReps = plannedReps, totalSets = totalSets,
            restSeconds = restSeconds, notes = notes,
            equipmentProfileId = equipmentProfileId, blocks = newBlocks
        )
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
    }

    fun removeActiveExercise(exIdx: Int) {
        val w = _activeWorkout.value ?: return
        if (exIdx !in w.exercises.indices) return
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it.removeAt(exIdx) })
    }

    fun skipExercise(exIdx: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = ex.copy(skipped = true) })
    }

    fun moveActiveExercise(from: Int, to: Int) {
        val w = _activeWorkout.value ?: return
        if (from !in w.exercises.indices || to !in w.exercises.indices || from == to) return
        val list = w.exercises.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        _activeWorkout.value = w.copy(exercises = list)
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
                    actualReps = b.actualReps,
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
                SetEntry(exerciseId = exId, setIndex = i, completed = true, actualReps = ep.plannedReps)
            })
        }
    }

    // ===== 历史记录编辑 =====

    suspend fun loadSessionForEdit(sessionId: Long): SessionWithExercises? {
        val session = dao.getSessionById(sessionId) ?: return null
        val exs = dao.getExercisesBySession(sessionId)
        val sets = dao.getAllSets().filter { set -> exs.any { it.id == set.exerciseId } }
        val profiles = dao.getAllProfiles()
        val photos = dao.getAllProfilePhotos()
        val pains = dao.getAllPainRecords().filter { it.sessionId == sessionId }
        return assembleSessions(listOf(session), exs, sets, profiles, photos, pains).firstOrNull()
    }

    fun saveHistoryEdits(
        session: Session,
        exercises: List<ExerciseWithSets>,
        onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        dao.updateSession(session)

        val currentExercises = dao.getExercisesBySession(session.id)
        val currentIds = currentExercises.map { it.id }.toSet()
        val submittedIds = exercises.map { it.exercise.id }.filter { it > 0 }.toSet()

        // 删除不在列表里的动作
        currentIds.forEach { id ->
            if (id !in submittedIds) dao.deleteExercise(id)
        }

        exercises.forEachIndexed { idx, ex ->
            val entry = ex.exercise
            val exId: Long
            if (entry.id == 0L) {
                exId = dao.insertExercise(entry.copy(id = 0, sessionId = session.id, orderIndex = idx))
            } else {
                dao.updateExercise(entry.copy(sessionId = session.id, orderIndex = idx))
                exId = entry.id
            }

            // 组：用现有关联的 id 更新，新的插入，删掉的删除
            val existingSets = if (entry.id == 0L) emptyList() else dao.getSetsByExercise(exId)
            val existingSetIds = existingSets.map { it.id }.toSet()
            val submittedSetIds = ex.sets.map { it.id }.filter { it > 0 }.toSet()

            existingSetIds.forEach { id ->
                if (id !in submittedSetIds) dao.deleteSet(id)
            }

            ex.sets.forEachIndexed { sIdx, s ->
                val newSet = s.copy(exerciseId = exId, setIndex = sIdx + 1)
                if (s.id == 0L) dao.insertSets(listOf(newSet))
                else dao.updateSet(newSet)
            }

            if (entry.exerciseName.isNotBlank()) {
                ensureLibraryItem(session.bodyPart, entry.exerciseName)
            }
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

    suspend fun ensureLibraryItem(bodyPart: String, name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        if (dao.findLibraryItem(bodyPart, t) == null) {
            dao.insertLibraryItem(ExerciseLibraryItem(bodyPart = bodyPart, name = t))
        }
    }

    fun ensureLibraryItemAsync(bodyPart: String, name: String) {
        viewModelScope.launch { ensureLibraryItem(bodyPart, name) }
    }

    fun deleteLibraryItem(id: Long) = viewModelScope.launch { dao.deleteLibraryItem(id) }

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

    fun saveSessionAsTemplate(session: SessionWithExercises, name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val tid = dao.insertTemplate(WorkoutTemplate(name = name, bodyPart = session.session.bodyPart, goal = session.session.goal))
        session.exercises.forEachIndexed { idx, ex ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = tid, exerciseName = ex.exercise.exerciseName,
                weight = ex.exercise.weight, totalSets = ex.exercise.totalSets,
                plannedReps = ex.exercise.plannedReps, restSeconds = ex.exercise.restSeconds,
                notes = ex.exercise.notes, orderIndex = idx
            ))
        }
        onDone()
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

    suspend fun countRefsToProfile(profileId: Long): Int =
        dao.countExercisesReferencingProfile(profileId)

    suspend fun getAllProfiles() = dao.getAllProfiles()
    suspend fun getAllProfilePhotos() = dao.getAllProfilePhotos()

    // ===== 记录删除 =====

    fun deleteSession(id: Long) = viewModelScope.launch { dao.deleteSession(id) }

    // ===== 导入导出 =====

    suspend fun exportSessions(from: String?, to: String?, parts: List<String>): List<SessionWithExercises> {
        val s = if (from != null && to != null) dao.getBetween(from, to) else dao.getAllSessions()
        val all = assembleSessions(
            s, dao.getAllExercises(), dao.getAllSets(),
            dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllPainRecords()
        )
        return if (parts.isEmpty()) all else all.filter { it.session.bodyPart in parts }
    }

    suspend fun exportToJson(ctx: Context, uri: Uri, from: String?, to: String?, parts: List<String>): Boolean {
        val sessions = exportSessions(from, to, parts)
        val profiles = assembleEquipment(dao.getAllProfiles(), dao.getAllProfilePhotos())
        val json = ImportExport.buildJson(sessions, templates.value, profiles, dao.getAllLibrary())
        return ImportExport.exportJson(ctx, json, uri)
    }

    suspend fun exportToZip(ctx: Context, uri: Uri, from: String?, to: String?, parts: List<String>): Boolean {
        val sessions = exportSessions(from, to, parts)
        val profiles = assembleEquipment(dao.getAllProfiles(), dao.getAllProfilePhotos())
        val json = ImportExport.buildJson(sessions, templates.value, profiles, dao.getAllLibrary())
        val photos = dao.getAllProfilePhotos().map { it.filePath }.distinct()
        return ImportExport.exportZip(ctx, json, photos, uri)
    }

    suspend fun importFrom(ctx: Context, uri: Uri): ImportResult = ImportExport.importFromUri(ctx, uri)
}

class TrainingViewModelFactory(private val dao: TrainingDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TrainingViewModel(dao) as T
    }
}
