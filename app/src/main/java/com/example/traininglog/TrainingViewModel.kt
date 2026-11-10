package com.example.traininglog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BlockStatus { IDLE, RUNNING, DONE, FAILED }

data class ActiveBlock(
    val setIndex: Int,
    val status: BlockStatus = BlockStatus.IDLE,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val actualReps: Int = 0,
    val failureReps: Int = 0
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
        combine(
            dao.observeSessions(),
            dao.observeAllExercises(),
            dao.observeAllSets()
        ) { s, e, st -> Triple(s, e, st) },
        combine(
            dao.observeAllProfiles(),
            dao.observeAllProfilePhotos(),
            dao.observeGyms()
        ) { p, ph, g -> Triple(p, ph, g) },
        dao.observeAllPainRecords()
    ) { (s, e, st), (p, ph, g), pr ->
        assembleSessions(s, e, st, p, ph, g, pr)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gyms: StateFlow<List<Gym>> = dao.observeGyms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val templates: StateFlow<List<WorkoutTemplateWithExercises>> = combine(
        dao.observeTemplates(),
        dao.observeAllTemplateExercises()
    ) { t, e -> assembleTemplates(t, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeWorkout = MutableStateFlow<ActiveWorkout?>(null)
    val activeWorkout: StateFlow<ActiveWorkout?> = _activeWorkout

    // ===== 执行 =====

    fun startWorkout(plan: WorkoutPlan) {
        _activeWorkout.value = ActiveWorkout(
            date = plan.date,
            bodyPart = plan.bodyPart,
            goal = plan.goal,
            sessionNotes = plan.sessionNotes,
            startMs = System.currentTimeMillis(),
            exercises = plan.exercises.map { ep ->
                ActiveExercise(
                    exerciseName = ep.exerciseName,
                    weight = ep.weight,
                    plannedReps = ep.plannedReps,
                    totalSets = ep.totalSets,
                    restSeconds = ep.restSeconds,
                    notes = ep.notes,
                    isCoreAddon = ep.isCoreAddon,
                    equipmentProfileId = ep.equipmentProfileId,
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
        val newBlock = when (block.status) {
            BlockStatus.IDLE -> block.copy(status = BlockStatus.RUNNING, startMs = now, actualReps = ex.plannedReps)
            BlockStatus.RUNNING -> block.copy(status = BlockStatus.DONE, endMs = now)
            else -> return
        }
        val newEx = ex.copy(blocks = ex.blocks.toMutableList().also { it[setIdx] = newBlock })
        val newW = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
        _activeWorkout.value = newW
    }

    fun markBlockFailed(exIdx: Int, setIdx: Int, actualReps: Int, failureReps: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val block = ex.blocks.getOrNull(setIdx) ?: return
        if (block.status != BlockStatus.RUNNING) return
        val now = System.currentTimeMillis()
        val newBlock = block.copy(
            status = BlockStatus.FAILED, endMs = now,
            actualReps = actualReps, failureReps = failureReps
        )
        val newEx = ex.copy(blocks = ex.blocks.toMutableList().also { it[setIdx] = newBlock })
        val newW = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
        _activeWorkout.value = newW
    }

    fun addExerciseDuringWorkout(ep: ExercisePlan) {
        val w = _activeWorkout.value ?: return
        val newEx = ActiveExercise(
            exerciseName = ep.exerciseName,
            weight = ep.weight,
            plannedReps = ep.plannedReps,
            totalSets = ep.totalSets,
            restSeconds = ep.restSeconds,
            notes = ep.notes,
            isCoreAddon = ep.isCoreAddon,
            equipmentProfileId = ep.equipmentProfileId,
            blocks = (1..ep.totalSets).map { i -> ActiveBlock(setIndex = i, status = BlockStatus.IDLE, actualReps = ep.plannedReps) }
        )
        _activeWorkout.value = w.copy(exercises = w.exercises + newEx)
    }

    fun skipExercise(exIdx: Int) {
        val w = _activeWorkout.value ?: return
        val ex = w.exercises.getOrNull(exIdx) ?: return
        val newEx = ex.copy(skipped = true)
        _activeWorkout.value = w.copy(exercises = w.exercises.toMutableList().also { it[exIdx] = newEx })
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
                date = w.date,
                bodyPart = w.bodyPart,
                goal = w.goal,
                notes = w.sessionNotes,
                startTime = w.startMs,
                endTime = endMs,
                durationSeconds = duration,
                createdAt = w.startMs
            ))
        }

        w.exercises.forEachIndexed { exIdx, ex ->
            if (ex.skipped) return@forEachIndexed
            val exId = dao.insertExercise(ExerciseEntry(
                sessionId = sessionId,
                exerciseName = ex.exerciseName,
                weight = ex.weight,
                plannedReps = ex.plannedReps,
                totalSets = ex.totalSets,
                notes = ex.notes,
                isCoreAddon = ex.isCoreAddon,
                equipmentProfileId = ex.equipmentProfileId,
                restSeconds = ex.restSeconds,
                orderIndex = exIdx
            ))
            val sets = ex.blocks.map { b ->
                SetEntry(
                    exerciseId = exId,
                    setIndex = b.setIndex,
                    completed = b.status == BlockStatus.DONE,
                    actualReps = b.actualReps,
                    failureReps = b.failureReps,
                    durationSeconds = if (b.endMs > 0 && b.startMs > 0) ((b.endMs - b.startMs) / 1000).toInt() else 0
                )
            }
            dao.insertSets(sets)
        }

        w.painRecords.forEach { pr ->
            dao.insertPainRecord(PainRecord(sessionId = sessionId, location = pr.location, score = pr.score, note = pr.note))
        }

        _activeWorkout.value = null
    }

    fun cancelWorkout() { _activeWorkout.value = null }

    // ===== 添加记录（不进入计时，直接落库） =====

    fun addRecordDirectly(plan: WorkoutPlan) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val existing = dao.getSessionByDateAndPart(plan.date, plan.bodyPart)
        val sessionId = if (existing != null) {
            dao.updateSession(existing.copy(
                goal = if (plan.goal.isNotBlank()) plan.goal else existing.goal,
                notes = if (plan.sessionNotes.isNotBlank()) plan.sessionNotes else existing.notes
            ))
            existing.id
        } else {
            dao.insertSession(Session(
                date = plan.date,
                bodyPart = plan.bodyPart,
                goal = plan.goal,
                notes = plan.sessionNotes,
                createdAt = now
            ))
        }
        plan.exercises.forEachIndexed { idx, ep ->
            val exId = dao.insertExercise(ExerciseEntry(
                sessionId = sessionId,
                exerciseName = ep.exerciseName,
                weight = ep.weight,
                plannedReps = ep.plannedReps,
                totalSets = ep.totalSets,
                notes = ep.notes,
                isCoreAddon = ep.isCoreAddon,
                equipmentProfileId = ep.equipmentProfileId,
                restSeconds = ep.restSeconds,
                orderIndex = idx
            ))
            dao.insertSets((1..ep.totalSets).map { i ->
                SetEntry(exerciseId = exId, setIndex = i, completed = true, actualReps = ep.plannedReps, failureReps = 0)
            })
        }
    }

    // ===== 模板 =====

    fun saveTemplate(name: String, bodyPart: String, goal: String, exercises: List<ExercisePlan>) = viewModelScope.launch {
        val tid = dao.insertTemplate(WorkoutTemplate(name = name, bodyPart = bodyPart, goal = goal))
        exercises.forEachIndexed { idx, ep ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = tid,
                exerciseName = ep.exerciseName,
                weight = ep.weight,
                totalSets = ep.totalSets,
                plannedReps = ep.plannedReps,
                restSeconds = ep.restSeconds,
                notes = ep.notes,
                orderIndex = idx
            ))
        }
    }

    fun updateTemplate(templateId: Long, name: String, bodyPart: String, goal: String, exercises: List<ExercisePlan>) = viewModelScope.launch {
        dao.updateTemplate(WorkoutTemplate(id = templateId, name = name, bodyPart = bodyPart, goal = goal, createdAt = System.currentTimeMillis()))
        dao.deleteTemplateExercises(templateId)
        exercises.forEachIndexed { idx, ep ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = templateId,
                exerciseName = ep.exerciseName,
                weight = ep.weight,
                totalSets = ep.totalSets,
                plannedReps = ep.plannedReps,
                restSeconds = ep.restSeconds,
                notes = ep.notes,
                orderIndex = idx
            ))
        }
    }

    fun deleteTemplate(id: Long) = viewModelScope.launch { dao.deleteTemplate(id) }

    fun duplicateTemplate(t: WorkoutTemplateWithExercises) = viewModelScope.launch {
        val all = dao.getAllTemplates()
        val base = t.template.name
        var n = 2
        var newName: String
        do {
            newName = "$base$n"; n++
        } while (all.any { it.name == newName })
        val newId = dao.insertTemplate(WorkoutTemplate(name = newName, bodyPart = t.template.bodyPart, goal = t.template.goal))
        t.exercises.forEachIndexed { idx, ex ->
            dao.insertTemplateExercise(TemplateExercise(
                templateId = newId,
                exerciseName = ex.exerciseName,
                weight = ex.weight,
                totalSets = ex.totalSets,
                plannedReps = ex.plannedReps,
                restSeconds = ex.restSeconds,
                notes = ex.notes,
                orderIndex = idx
            ))
        }
    }

    suspend fun getTemplateExercises(templateId: Long): List<TemplateExercise> = dao.getTemplateExercises(templateId)

    // ===== 器械（沿用）=====

    fun addGym(name: String) = viewModelScope.launch {
        val trimmed = name.trim(); if (trimmed.isEmpty()) return@launch
        if (dao.findGymByName(trimmed) == null) dao.insertGym(Gym(name = trimmed))
    }
    fun deleteGym(gym: Gym) = viewModelScope.launch {
        if (dao.countProfilesInGym(gym.id) > 0) return@launch
        dao.deleteGym(gym.id)
    }
    fun saveProfile(profile: EquipmentProfile, photos: List<PhotoInput>, removedPhotoIds: List<Long>) = viewModelScope.launch {
        val pid = if (profile.id == 0L) dao.insertProfile(profile) else { dao.updateProfile(profile); profile.id }
        photos.forEach { dao.insertProfilePhoto(EquipmentProfilePhoto(profileId = pid, filePath = it.filePath, tag = it.tag)) }
        removedPhotoIds.forEach { dao.deleteProfilePhoto(it) }
    }
    suspend fun loadProfilePhotos(profileId: Long) = dao.getPhotosByProfile(profileId)
    fun deleteProfile(profileId: Long, filesDir: java.io.File) = viewModelScope.launch {
        val photos = dao.getPhotosByProfile(profileId)
        photos.forEach { p -> runCatching { java.io.File(filesDir, p.filePath).delete() } }
        dao.clearExerciseRefsToProfile(profileId)
        dao.deleteProfile(profileId)
    }
    fun deleteProfilePhoto(photoId: Long, filePath: String, filesDir: java.io.File) = viewModelScope.launch {
        runCatching { java.io.File(filesDir, filePath).delete() }
        dao.deleteProfilePhoto(photoId)
    }

    fun deleteSession(id: Long) = viewModelScope.launch { dao.deleteSession(id) }

    suspend fun exportSessions(from: String?, to: String?, parts: List<String>): List<SessionWithExercises> {
        val s = if (from != null && to != null) dao.getBetween(from, to) else dao.getAllSessions()
        val all = assembleSessions(
            s, dao.getAllExercises(), dao.getAllSets(),
            dao.getAllProfiles(), dao.getAllProfilePhotos(), dao.getAllGyms(),
            dao.getAllPainRecords()
        )
        return if (parts.isEmpty()) all else all.filter { it.session.bodyPart in parts }
    }

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
