package com.example.traininglog

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val bodyPart: String,
    val painLocation: String = "无",
    val painScore: Int = 0,
    val notes: String = "",
    val goal: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val durationSeconds: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "exercises",
    foreignKeys = [ForeignKey(
        entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ExerciseEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseName: String,
    val weight: Double = 0.0,
    val plannedReps: Int = 0,
    val totalSets: Int = 0,
    val notes: String = "",
    val isCoreAddon: Boolean = false,
    val equipmentProfileId: Long? = null,
    val restSeconds: Int = 0,
    val orderIndex: Int = 0
)

@Entity(
    tableName = "sets",
    foreignKeys = [ForeignKey(
        entity = ExerciseEntry::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("exerciseId")]
)
data class SetEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val setIndex: Int,
    val completed: Boolean,
    val actualReps: Int,
    val durationSeconds: Int = 0,
    val restTakenSeconds: Int = 0
)

@Entity(
    tableName = "equipment_photos",
    foreignKeys = [ForeignKey(
        entity = ExerciseEntry::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("exerciseId")]
)
data class LegacyEquipmentPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val filePath: String,
    val tag: String = "其他",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "equipment_profiles")
data class EquipmentProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseName: String,
    val equipmentName: String = "",
    val equipmentType: String = "未填",
    val gymName: String = "",
    val seat: String = "",
    val backrest: String = "",
    val grip: String = "",
    val weightStack: String = "",
    val pin: String = "",
    val assist: String = "",
    val pulley: String = "",
    val other: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "equipment_profile_photos",
    foreignKeys = [ForeignKey(
        entity = EquipmentProfile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("profileId")]
)
data class EquipmentProfilePhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val filePath: String,
    val tag: String = "其他",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pain_records",
    foreignKeys = [ForeignKey(
        entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class PainRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val location: String,
    val score: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "templates")
data class WorkoutTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val bodyPart: String,
    val goal: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "template_exercises",
    foreignKeys = [ForeignKey(
        entity = WorkoutTemplate::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("templateId")]
)
data class TemplateExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val exerciseName: String,
    val weight: Double,
    val totalSets: Int,
    val plannedReps: Int,
    val restSeconds: Int,
    val notes: String = "",
    val orderIndex: Int = 0
)

@Entity(
    tableName = "exercise_library",
    indices = [Index(value = ["bodyPart", "name"], unique = true)]
)
data class ExerciseLibraryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bodyPart: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class TemplateExerciseItem(
    val id: Long,
    val exerciseName: String,
    val weight: Double,
    val totalSets: Int,
    val plannedReps: Int,
    val restSeconds: Int,
    val notes: String
)

data class WorkoutTemplateWithExercises(
    val template: WorkoutTemplate,
    val exercises: List<TemplateExerciseItem>
)

data class ExerciseWithSets(
    val exercise: ExerciseEntry,
    val sets: List<SetEntry>,
    val equipmentProfile: EquipmentProfile? = null,
    val equipmentPhoto: EquipmentProfilePhoto? = null
)

data class SessionWithExercises(
    val session: Session,
    val exercises: List<ExerciseWithSets>,
    val painRecords: List<PainRecord> = emptyList()
)

data class EquipmentWithPhotos(
    val profile: EquipmentProfile,
    val photos: List<EquipmentProfilePhoto>
)

@Dao
interface TrainingDao {
    @Query("SELECT * FROM sessions ORDER BY date DESC, createdAt DESC")
    fun observeSessions(): Flow<List<Session>>
    @Query("SELECT * FROM sessions WHERE date = :date AND bodyPart = :part ORDER BY id ASC LIMIT 1")
    suspend fun getSessionByDateAndPart(date: String, part: String): Session?
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): Session?
    @Query("SELECT * FROM sessions WHERE date >= :from AND date <= :to ORDER BY date ASC")
    suspend fun getBetween(from: String, to: String): List<Session>
    @Query("SELECT * FROM sessions ORDER BY date ASC")
    suspend fun getAllSessions(): List<Session>
    @Insert suspend fun insertSession(s: Session): Long
    @Update suspend fun updateSession(s: Session)
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM exercises ORDER BY id ASC")
    fun observeAllExercises(): Flow<List<ExerciseEntry>>
    @Query("SELECT * FROM exercises WHERE sessionId = :sessionId ORDER BY orderIndex ASC, id ASC")
    suspend fun getExercisesBySession(sessionId: Long): List<ExerciseEntry>
    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getExerciseById(id: Long): ExerciseEntry?
    @Query("SELECT * FROM exercises")
    suspend fun getAllExercises(): List<ExerciseEntry>
    @Insert suspend fun insertExercise(e: ExerciseEntry): Long
    @Update suspend fun updateExercise(e: ExerciseEntry)
    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)
    @Query("UPDATE exercises SET equipmentProfileId = NULL WHERE equipmentProfileId = :profileId")
    suspend fun clearExerciseRefsToProfile(profileId: Long)
    @Query("SELECT COUNT(*) FROM exercises WHERE equipmentProfileId = :profileId")
    suspend fun countExercisesReferencingProfile(profileId: Long): Int

    @Query("SELECT * FROM sets")
    fun observeAllSets(): Flow<List<SetEntry>>
    @Query("SELECT * FROM sets WHERE exerciseId = :exerciseId ORDER BY setIndex ASC")
    suspend fun getSetsByExercise(exerciseId: Long): List<SetEntry>
    @Query("SELECT * FROM sets")
    suspend fun getAllSets(): List<SetEntry>
    @Insert suspend fun insertSets(sets: List<SetEntry>)
    @Update suspend fun updateSet(s: SetEntry)
    @Query("DELETE FROM sets WHERE id = :id")
    suspend fun deleteSet(id: Long)
    @Query("DELETE FROM sets WHERE exerciseId = :exerciseId")
    suspend fun deleteSetsByExercise(exerciseId: Long)

    @Query("SELECT * FROM pain_records")
    fun observeAllPainRecords(): Flow<List<PainRecord>>
    @Query("SELECT * FROM pain_records")
    suspend fun getAllPainRecords(): List<PainRecord>
    @Insert suspend fun insertPainRecord(p: PainRecord): Long

    @Query("SELECT * FROM equipment_profiles ORDER BY createdAt DESC")
    fun observeAllProfiles(): Flow<List<EquipmentProfile>>
    @Query("SELECT * FROM equipment_profiles ORDER BY createdAt DESC")
    suspend fun getAllProfiles(): List<EquipmentProfile>
    @Query("SELECT * FROM equipment_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): EquipmentProfile?
    @Insert suspend fun insertProfile(p: EquipmentProfile): Long
    @Update suspend fun updateProfile(p: EquipmentProfile)
    @Query("DELETE FROM equipment_profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("SELECT * FROM equipment_profile_photos")
    fun observeAllProfilePhotos(): Flow<List<EquipmentProfilePhoto>>
    @Query("SELECT * FROM equipment_profile_photos")
    suspend fun getAllProfilePhotos(): List<EquipmentProfilePhoto>
    @Query("SELECT * FROM equipment_profile_photos WHERE profileId = :profileId")
    suspend fun getPhotosByProfile(profileId: Long): List<EquipmentProfilePhoto>
    @Insert suspend fun insertProfilePhoto(p: EquipmentProfilePhoto): Long
    @Query("DELETE FROM equipment_profile_photos WHERE id = :id")
    suspend fun deleteProfilePhoto(id: Long)

    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun observeTemplates(): Flow<List<WorkoutTemplate>>
    @Query("SELECT * FROM template_exercises ORDER BY orderIndex ASC, id ASC")
    fun observeAllTemplateExercises(): Flow<List<TemplateExercise>>
    @Query("SELECT * FROM template_exercises WHERE templateId = :templateId ORDER BY orderIndex ASC, id ASC")
    suspend fun getTemplateExercises(templateId: Long): List<TemplateExercise>
    @Insert suspend fun insertTemplate(t: WorkoutTemplate): Long
    @Update suspend fun updateTemplate(t: WorkoutTemplate)
    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)
    @Insert suspend fun insertTemplateExercise(e: TemplateExercise): Long
    @Query("DELETE FROM template_exercises WHERE templateId = :templateId")
    suspend fun deleteTemplateExercises(templateId: Long)

    @Query("SELECT * FROM exercise_library WHERE bodyPart = :bodyPart ORDER BY createdAt ASC")
    fun observeLibraryByPart(bodyPart: String): Flow<List<ExerciseLibraryItem>>
    @Query("SELECT * FROM exercise_library ORDER BY bodyPart ASC, createdAt ASC")
    fun observeAllLibrary(): Flow<List<ExerciseLibraryItem>>
    @Query("SELECT * FROM exercise_library ORDER BY bodyPart ASC, createdAt ASC")
    suspend fun getAllLibrary(): List<ExerciseLibraryItem>
    @Query("SELECT * FROM exercise_library WHERE bodyPart = :bodyPart AND name = :name LIMIT 1")
    suspend fun findLibraryItem(bodyPart: String, name: String): ExerciseLibraryItem?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLibraryItem(item: ExerciseLibraryItem): Long
    @Query("DELETE FROM exercise_library WHERE id = :id")
    suspend fun deleteLibraryItem(id: Long)
    @Query("DELETE FROM exercise_library")
    suspend fun clearLibrary()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                exerciseId INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                tag TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_photos_exerciseId ON equipment_photos(exerciseId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gyms (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gyms_name ON gyms(name)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                gymId INTEGER NOT NULL,
                exerciseName TEXT NOT NULL,
                equipmentName TEXT NOT NULL,
                seat TEXT NOT NULL, backrest TEXT NOT NULL, grip TEXT NOT NULL,
                weightStack TEXT NOT NULL, pin TEXT NOT NULL, assist TEXT NOT NULL,
                pulley TEXT NOT NULL, other TEXT NOT NULL, notes TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(gymId) REFERENCES gyms(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_profiles_gymId ON equipment_profiles(gymId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_profile_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                profileId INTEGER NOT NULL,
                filePath TEXT NOT NULL, tag TEXT NOT NULL, createdAt INTEGER NOT NULL,
                FOREIGN KEY(profileId) REFERENCES equipment_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_profile_photos_profileId ON equipment_profile_photos(profileId)")
        db.execSQL("ALTER TABLE exercises ADD COLUMN isCoreAddon INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE exercises ADD COLUMN equipmentProfileId INTEGER")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN startTime INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN endTime INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE exercises ADD COLUMN restSeconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE exercises ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sets ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pain_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL, location TEXT NOT NULL, score INTEGER NOT NULL,
                note TEXT NOT NULL, createdAt INTEGER NOT NULL,
                FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pain_records_sessionId ON pain_records(sessionId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, bodyPart TEXT NOT NULL, goal TEXT NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS template_exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                templateId INTEGER NOT NULL, exerciseName TEXT NOT NULL,
                weight REAL NOT NULL, totalSets INTEGER NOT NULL, plannedReps INTEGER NOT NULL,
                restSeconds INTEGER NOT NULL, notes TEXT NOT NULL, orderIndex INTEGER NOT NULL,
                FOREIGN KEY(templateId) REFERENCES templates(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_template_exercises_templateId ON template_exercises(templateId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sets ADD COLUMN restTakenSeconds INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS exercise_library (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bodyPart TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exercise_library_bodyPart_name ON exercise_library(bodyPart, name)")
        DEFAULT_EXERCISES.forEach { (part, names) ->
            names.forEach { name ->
                db.execSQL("INSERT OR IGNORE INTO exercise_library(bodyPart, name, createdAt) VALUES(?,?,?)",
                    arrayOf(part, name, System.currentTimeMillis()))
            }
        }
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 备份 equipment_profile_photos
        db.execSQL("ALTER TABLE equipment_profile_photos RENAME TO equipment_profile_photos_old")
        db.execSQL("DROP INDEX IF EXISTS index_equipment_profile_photos_profileId")

        // 2. 重建 equipment_profiles（去掉 gymId 外键，增加 gymName、equipmentType）
        db.execSQL("""
            CREATE TABLE equipment_profiles_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                exerciseName TEXT NOT NULL,
                equipmentName TEXT NOT NULL,
                equipmentType TEXT NOT NULL,
                gymName TEXT NOT NULL,
                seat TEXT NOT NULL, backrest TEXT NOT NULL, grip TEXT NOT NULL,
                weightStack TEXT NOT NULL, pin TEXT NOT NULL, assist TEXT NOT NULL,
                pulley TEXT NOT NULL, other TEXT NOT NULL, notes TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO equipment_profiles_new (id, exerciseName, equipmentName, equipmentType, gymName, seat, backrest, grip, weightStack, pin, assist, pulley, other, notes, createdAt)
            SELECT p.id, p.exerciseName, p.equipmentName, '未填', COALESCE(g.name, ''), p.seat, p.backrest, p.grip, p.weightStack, p.pin, p.assist, p.pulley, p.other, p.notes, p.createdAt
            FROM equipment_profiles p LEFT JOIN gyms g ON p.gymId = g.id
        """.trimIndent())
        db.execSQL("DROP TABLE equipment_profiles")
        db.execSQL("ALTER TABLE equipment_profiles_new RENAME TO equipment_profiles")

        // 3. 删除 gyms
        db.execSQL("DROP TABLE gyms")

        // 4. 重建 equipment_profile_photos
        db.execSQL("""
            CREATE TABLE equipment_profile_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                profileId INTEGER NOT NULL,
                filePath TEXT NOT NULL, tag TEXT NOT NULL, createdAt INTEGER NOT NULL,
                FOREIGN KEY(profileId) REFERENCES equipment_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO equipment_profile_photos SELECT id, profileId, filePath, tag, createdAt FROM equipment_profile_photos_old")
        db.execSQL("DROP TABLE equipment_profile_photos_old")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_profile_photos_profileId ON equipment_profile_photos(profileId)")

        // 5. 重建 sets（去掉 failureReps）
        db.execSQL("""
            CREATE TABLE sets_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                exerciseId INTEGER NOT NULL,
                setIndex INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                actualReps INTEGER NOT NULL,
                durationSeconds INTEGER NOT NULL,
                restTakenSeconds INTEGER NOT NULL,
                FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO sets_new (id, exerciseId, setIndex, completed, actualReps, durationSeconds, restTakenSeconds)
            SELECT id, exerciseId, setIndex, completed, actualReps, durationSeconds, restTakenSeconds FROM sets
        """.trimIndent())
        db.execSQL("DROP TABLE sets")
        db.execSQL("ALTER TABLE sets_new RENAME TO sets")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sets_exerciseId ON sets(exerciseId)")
    }
}

private val seedCallback = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        val c = db.query("SELECT COUNT(*) FROM exercise_library")
        c.use {
            if (it.moveToFirst() && it.getInt(0) == 0) {
                DEFAULT_EXERCISES.forEach { (part, names) ->
                    names.forEach { name ->
                        db.execSQL("INSERT OR IGNORE INTO exercise_library(bodyPart, name, createdAt) VALUES(?,?,?)",
                            arrayOf(part, name, System.currentTimeMillis()))
                    }
                }
            }
        }
    }
}

@Database(
    entities = [
        Session::class, ExerciseEntry::class, SetEntry::class, LegacyEquipmentPhoto::class,
        EquipmentProfile::class, EquipmentProfilePhoto::class,
        PainRecord::class, WorkoutTemplate::class, TemplateExercise::class, ExerciseLibraryItem::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "training.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                ).addCallback(seedCallback).build().also { INSTANCE = it }
            }
    }
}

fun assembleSessions(
    sessions: List<Session>, exercises: List<ExerciseEntry>, sets: List<SetEntry>,
    profiles: List<EquipmentProfile>, profilePhotos: List<EquipmentProfilePhoto>,
    painRecords: List<PainRecord>
): List<SessionWithExercises> {
    val exBySession = exercises.groupBy { it.sessionId }
    val setsByExercise = sets.groupBy { it.exerciseId }
    val profileById = profiles.associateBy { it.id }
    val firstPhoto = profilePhotos.groupBy { it.profileId }.mapValues { it.value.firstOrNull() }
    val painsBySession = painRecords.groupBy { it.sessionId }
    return sessions.map { s ->
        SessionWithExercises(
            s,
            exBySession[s.id].orEmpty().sortedBy { it.orderIndex }.map { ex ->
                val profile = ex.equipmentProfileId?.let { profileById[it] }
                ExerciseWithSets(
                    ex,
                    setsByExercise[ex.id].orEmpty().sortedBy { it.setIndex },
                    profile,
                    profile?.let { firstPhoto[it.id] }
                )
            },
            painsBySession[s.id].orEmpty()
        )
    }
}

fun assembleEquipment(profiles: List<EquipmentProfile>, photos: List<EquipmentProfilePhoto>): List<EquipmentWithPhotos> {
    val photosByProfile = photos.groupBy { it.profileId }
    return profiles.map { p ->
        EquipmentWithPhotos(p, photosByProfile[p.id].orEmpty())
    }
}

fun assembleTemplates(templates: List<WorkoutTemplate>, exercises: List<TemplateExercise>): List<WorkoutTemplateWithExercises> {
    val byTemplate = exercises.groupBy { it.templateId }
    return templates.map { t ->
        WorkoutTemplateWithExercises(
            t,
            byTemplate[t.id].orEmpty().sortedBy { it.orderIndex }.map {
                TemplateExerciseItem(it.id, it.exerciseName, it.weight, it.totalSets, it.plannedReps, it.restSeconds, it.notes)
            }
        )
    }
}
