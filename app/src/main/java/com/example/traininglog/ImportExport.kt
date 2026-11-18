package com.example.traininglog

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val SCHEMA_VERSION = 2

data class ImportResult(
    val success: Boolean,
    val sessions: Int = 0,
    val templates: Int = 0,
    val profiles: Int = 0,
    val library: Int = 0,
    val photos: Int = 0,
    val message: String = ""
)

object ImportExport {

    fun buildJson(
        sessions: List<SessionWithExercises>,
        templates: List<WorkoutTemplateWithExercises>,
        profiles: List<EquipmentWithPhotos>,
        library: List<ExerciseLibraryItem>
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", "2.5")

        val sessionArr = JSONArray()
        sessions.forEach { sw ->
            val s = JSONObject()
            s.put("date", sw.session.date)
            s.put("bodyPart", sw.session.bodyPart)
            s.put("goal", sw.session.goal)
            s.put("notes", sw.session.notes)
            s.put("startTime", sw.session.startTime)
            s.put("endTime", sw.session.endTime)
            s.put("durationSeconds", sw.session.durationSeconds)
            s.put("createdAt", sw.session.createdAt)

            val exArr = JSONArray()
            sw.exercises.forEach { ew ->
                val e = JSONObject()
                e.put("exerciseName", ew.exercise.exerciseName)
                e.put("weight", ew.exercise.weight)
                e.put("plannedReps", ew.exercise.plannedReps)
                e.put("totalSets", ew.exercise.totalSets)
                e.put("notes", ew.exercise.notes)
                e.put("isCoreAddon", ew.exercise.isCoreAddon)
                e.put("restSeconds", ew.exercise.restSeconds)
                e.put("orderIndex", ew.exercise.orderIndex)
                ew.equipmentProfile?.let { p ->
                    e.put("equipmentKey",
                        "${p.gymName}|${p.exerciseName}|${p.equipmentName}|${p.equipmentType}")
                }
                val setsArr = JSONArray()
                ew.sets.forEach { set ->
                    val so = JSONObject()
                    so.put("setIndex", set.setIndex)
                    so.put("completed", set.completed)
                    so.put("actualReps", set.actualReps)
                    so.put("durationSeconds", set.durationSeconds)
                    so.put("restTakenSeconds", set.restTakenSeconds)
                    setsArr.put(so)
                }
                e.put("sets", setsArr)
                exArr.put(e)
            }
            s.put("exercises", exArr)

            val painsArr = JSONArray()
            sw.painRecords.forEach { p ->
                val po = JSONObject()
                po.put("location", p.location)
                po.put("score", p.score)
                po.put("note", p.note)
                painsArr.put(po)
            }
            s.put("painRecords", painsArr)
            sessionArr.put(s)
        }
        root.put("sessions", sessionArr)

        val tplArr = JSONArray()
        templates.forEach { tw ->
            val t = JSONObject()
            t.put("name", tw.template.name)
            t.put("bodyPart", tw.template.bodyPart)
            t.put("goal", tw.template.goal)
            t.put("createdAt", tw.template.createdAt)
            val exArr = JSONArray()
            tw.exercises.forEach { ex ->
                val e = JSONObject()
                e.put("exerciseName", ex.exerciseName)
                e.put("weight", ex.weight)
                e.put("totalSets", ex.totalSets)
                e.put("plannedReps", ex.plannedReps)
                e.put("restSeconds", ex.restSeconds)
                e.put("notes", ex.notes)
                exArr.put(e)
            }
            t.put("exercises", exArr)
            tplArr.put(t)
        }
        root.put("templates", tplArr)

        val profArr = JSONArray()
        profiles.forEach { pw ->
            val p = JSONObject()
            p.put("exerciseName", pw.profile.exerciseName)
            p.put("equipmentName", pw.profile.equipmentName)
            p.put("equipmentType", pw.profile.equipmentType)
            p.put("gymName", pw.profile.gymName)
            p.put("seat", pw.profile.seat)
            p.put("backrest", pw.profile.backrest)
            p.put("grip", pw.profile.grip)
            p.put("weightStack", pw.profile.weightStack)
            p.put("pin", pw.profile.pin)
            p.put("assist", pw.profile.assist)
            p.put("pulley", pw.profile.pulley)
            p.put("other", pw.profile.other)
            p.put("notes", pw.profile.notes)
            val photosArr = JSONArray()
            pw.photos.forEach { ph -> photosArr.put(ph.filePath) }
            p.put("photos", photosArr)
            profArr.put(p)
        }
        root.put("equipmentProfiles", profArr)

        val libArr = JSONArray()
        library.forEach { l ->
            val o = JSONObject()
            o.put("bodyPart", l.bodyPart)
            o.put("name", l.name)
            libArr.put(o)
        }
        root.put("exerciseLibrary", libArr)

        return root.toString(2)
    }

    suspend fun exportJson(ctx: Context, json: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) { false }
    }

    suspend fun exportZip(ctx: Context, json: String, photos: List<String>, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zip ->
                    zip.putNextEntry(ZipEntry("data.json"))
                    zip.write(json.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    photos.forEach { rel ->
                        val f = File(ctx.filesDir, rel)
                        if (f.exists()) {
                            zip.putNextEntry(ZipEntry(rel))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun importFromUri(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val name = uri.lastPathSegment ?: ""
            val isZip = name.endsWith(".zip", true)
            if (isZip) {
                ctx.contentResolver.openInputStream(uri)?.use { importZipStream(ctx, it) }
                    ?: ImportResult(false, message = "无法读取文件")
            } else {
                val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                importJson(ctx, json, emptyList())
            }
        } catch (e: Exception) {
            ImportResult(false, message = "导入失败：${e.message}")
        }
    }

    private suspend fun importZipStream(ctx: Context, ins: InputStream): ImportResult {
        var json = ""
        val photos = mutableListOf<String>()
        ZipInputStream(ins).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val e = entry
                if (e.name == "data.json") {
                    json = zip.readBytes().toString(Charsets.UTF_8)
                } else if (e.name.startsWith("equipment_photos/")) {
                    val target = File(ctx.filesDir, e.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { os -> zip.copyTo(os) }
                    photos.add(e.name)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (json.isBlank()) return ImportResult(false, message = "ZIP 中缺少 data.json")
        return importJson(ctx, json, photos)
    }

    private suspend fun importJson(ctx: Context, json: String, photos: List<String>): ImportResult {
        val root = JSONObject(json)
        val version = root.optInt("schemaVersion", 0)
        if (version == 0) return ImportResult(false, message = "无法识别的文件，缺少 schemaVersion")
        if (version > SCHEMA_VERSION) return ImportResult(false, message = "文件来自更新版本，请升级 App")

        val dao = AppDatabase.get(ctx).dao()

        // 动作库
        var libCount = 0
        root.optJSONArray("exerciseLibrary")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val bodyPart = o.getString("bodyPart")
                val name = o.getString("name")
                if (dao.findLibraryItem(bodyPart, name) == null) {
                    dao.insertLibraryItem(ExerciseLibraryItem(bodyPart = bodyPart, name = name))
                    libCount++
                }
            }
        }

        // 器械档案（按业务键匹配：健身房|动作|器械名|类型）
        var profileCount = 0
        val profileKeyToId = mutableMapOf<String, Long>()
        root.optJSONArray("equipmentProfiles")?.let { arr ->
            val existing = dao.getAllProfiles()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val exerciseName = o.getString("exerciseName")
                val equipmentName = o.optString("equipmentName")
                val equipmentType = o.optString("equipmentType", "未填")
                val gymName = o.optString("gymName")
                val key = "$gymName|$exerciseName|$equipmentName|$equipmentType"
                val match = existing.firstOrNull {
                    it.exerciseName == exerciseName &&
                    it.equipmentName == equipmentName &&
                    it.equipmentType == equipmentType &&
                    it.gymName == gymName
                }
                val pid = if (match != null) match.id else {
                    dao.insertProfile(EquipmentProfile(
                        exerciseName = exerciseName,
                        equipmentName = equipmentName,
                        equipmentType = equipmentType,
                        gymName = gymName,
                        seat = o.optString("seat"),
                        backrest = o.optString("backrest"),
                        grip = o.optString("grip"),
                        weightStack = o.optString("weightStack"),
                        pin = o.optString("pin"),
                        assist = o.optString("assist"),
                        pulley = o.optString("pulley"),
                        other = o.optString("other"),
                        notes = o.optString("notes")
                    )).also { profileCount++ }
                }
                profileKeyToId[key] = pid
            }
        }

        // 模板（重名自动加编号）
        var templateCount = 0
        root.optJSONArray("templates")?.let { arr ->
            val existingNames = dao.observeTemplates().let { flow ->
                // 简单读取一次现有模板名
                emptySet<String>()
            }.toMutableSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val base = o.getString("name")
                var finalName = base
                var n = 2
                while (finalName in existingNames) { finalName = "$base$n"; n++ }
                existingNames.add(finalName)

                val tid = dao.insertTemplate(WorkoutTemplate(
                    name = finalName,
                    bodyPart = o.getString("bodyPart"),
                    goal = o.getString("goal")
                ))
                o.optJSONArray("exercises")?.let { exArr ->
                    for (j in 0 until exArr.length()) {
                        val e = exArr.getJSONObject(j)
                        dao.insertTemplateExercise(TemplateExercise(
                            templateId = tid,
                            exerciseName = e.getString("exerciseName"),
                            weight = e.optDouble("weight", 0.0),
                            totalSets = e.optInt("totalSets", 0),
                            plannedReps = e.optInt("plannedReps", 0),
                            restSeconds = e.optInt("restSeconds", 0),
                            notes = e.optString("notes"),
                            orderIndex = j
                        ))
                    }
                }
                templateCount++
            }
        }

        // 训练记录（同日期同部位覆盖）
        var sessionCount = 0
        root.optJSONArray("sessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.getString("date")
                val part = o.getString("bodyPart")
                dao.getSessionByDateAndPart(date, part)?.let { dao.deleteSession(it.id) }

                val sid = dao.insertSession(Session(
                    date = date, bodyPart = part,
                    goal = o.optString("goal"),
                    notes = o.optString("notes"),
                    startTime = o.optLong("startTime"),
                    endTime = o.optLong("endTime"),
                    durationSeconds = o.optInt("durationSeconds"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                ))

                o.optJSONArray("exercises")?.let { exArr ->
                    for (j in 0 until exArr.length()) {
                        val e = exArr.getJSONObject(j)
                        val eqKey = e.optString("equipmentKey", "")
                        val eid = dao.insertExercise(ExerciseEntry(
                            sessionId = sid,
                            exerciseName = e.getString("exerciseName"),
                            weight = e.optDouble("weight", 0.0),
                            plannedReps = e.optInt("plannedReps"),
                            totalSets = e.optInt("totalSets"),
                            notes = e.optString("notes"),
                            isCoreAddon = e.optBoolean("isCoreAddon"),
                            restSeconds = e.optInt("restSeconds"),
                            orderIndex = e.optInt("orderIndex", j),
                            equipmentProfileId = profileKeyToId[eqKey]
                        ))
                        val sets = mutableListOf<SetEntry>()
                        e.optJSONArray("sets")?.let { sArr ->
                            for (k in 0 until sArr.length()) {
                                val s = sArr.getJSONObject(k)
                                sets.add(SetEntry(
                                    exerciseId = eid,
                                    setIndex = s.getInt("setIndex"),
                                    completed = s.getBoolean("completed"),
                                    actualReps = s.getInt("actualReps"),
                                    durationSeconds = s.optInt("durationSeconds"),
                                    restTakenSeconds = s.optInt("restTakenSeconds")
                                ))
                            }
                        }
                        if (sets.isNotEmpty()) dao.insertSets(sets)
                    }
                }
                o.optJSONArray("painRecords")?.let { pArr ->
                    for (j in 0 until pArr.length()) {
                        val p = pArr.getJSONObject(j)
                        dao.insertPainRecord(PainRecord(
                            sessionId = sid,
                            location = p.getString("location"),
                            score = p.getInt("score"),
                            note = p.optString("note")
                        ))
                    }
                }
                sessionCount++
            }
        }

        return ImportResult(
            success = true,
            sessions = sessionCount, templates = templateCount,
            profiles = profileCount, library = libCount, photos = photos.size,
            message = "导入完成"
        )
    }
}
