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
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "exercises",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
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
    val equipmentProfileId: Long? = null
)

@Entity(
    tableName = "sets",
    foreignKeys = [ForeignKey(
        entity = ExerciseEntry::class,
        parentColumns = ["id"],
        childColumns = ["exerciseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("exerciseId")]
)
data class SetEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val setIndex: Int,
    val completed: Boolean,
    val actualReps: Int,
    val failureReps: Int
)

@Entity(
    tableName = "equipment_photos",
    foreignKeys = [ForeignKey(
        entity = ExerciseEntry::class,
        parentColumns = ["id"],
        childColumns = ["exerciseId"],
        onDelete = ForeignKey.CASCADE
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

@Entity(
    tableName = "gyms",
    indices = [Index(value = ["name"], unique = true)]
)
data class Gym(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "equipment_profiles",
    foreignKeys = [ForeignKey(
        entity = Gym::class,
        parentColumns = ["id"],
        childColumns = ["gymId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("gymId")]
)
data class EquipmentProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gymId: Long,
    val exerciseName: String,
    val equipmentName: String = "",
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
        entity = EquipmentProfile::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
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

data class ExerciseWithSets(
    val exercise: ExerciseEntry,
    val sets: List<SetEntry>,
    val equipmentProfile: EquipmentProfile? = null,
    val equipmentPhoto: EquipmentProfilePhoto? = null,
    val gymName: String = ""
)

data class SessionWithExercises(
    val session: Session,
    val exercises: List<ExerciseWithSets>
)

data class EquipmentWithPhotos(
    val profile: EquipmentProfile,
    val photos: List<EquipmentProfilePhoto>,
    val gymName: String
)

@Dao
interface TrainingDao {
    @Query("SELECT * FROM sessions ORDER BY date DESC, createdAt DESC")
    fun observeSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE date = :date AND bodyPart = :part ORDER BY id ASC LIMIT 1")
    suspend fun getSessionByDateAndPart(date: String, part: String): Session?

    @Query("SELECT * FROM sessions WHERE date = :date AND bodyPart != '核心' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestNonCoreSessionOfDate(date: String): Session?

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSessionOfDate(date: String): Session?

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

    @Query("SELECT * FROM exercises WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getExercisesBySession(sessionId: Long): List<ExerciseEntry>

    @Query("SELECT * FROM exercises")
    suspend fun getAllExercises(): List<ExerciseEntry>

    @Insert suspend fun insertExercise(e: ExerciseEntry): Long
    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)
    @Query("UPDATE exercises SET equipmentProfileId = NULL WHERE equipmentProfileId = :profileId")
    suspend fun clearExerciseRefsToProfile(profileId: Long)

    @Query("SELECT * FROM sets")
    fun observeAllSets(): Flow<List<SetEntry>>

    @Query("SELECT * FROM sets WHERE exerciseId IN (:exerciseIds)")
    suspend fun getSetsByExerciseIds(exerciseIds: List<Long>): List<SetEntry>

    @Query("SELECT * FROM sets")
    suspend fun getAllSets(): List<SetEntry>

    @Insert suspend fun insertSets(sets: List<SetEntry>)

    @Query("SELECT * FROM gyms ORDER BY createdAt ASC")
    fun observeGyms(): Flow<List<Gym>>

    @Query("SELECT * FROM gyms WHERE name = :name LIMIT 1")
    suspend fun findGymByName(name: String): Gym?

    @Query("SELECT * FROM gyms ORDER BY createdAt ASC")
    suspend fun getAllGyms(): List<Gym>

    @Insert suspend fun insertGym(g: Gym): Long
    @Query("DELETE FROM gyms WHERE id = :id")
    suspend fun deleteGym(id: Long)
    @Query("SELECT COUNT(*) FROM equipment_profiles WHERE gymId = :gymId")
    suspend fun countProfilesInGym(gymId: Long): Int

    @Query("SELECT * FROM equipment_profiles WHERE gymId = :gymId ORDER BY createdAt DESC")
    fun observeProfilesByGym(gymId: Long): Flow<List<EquipmentProfile>>

    @Query("SELECT * FROM equipment_profiles ORDER BY createdAt DESC")
    fun observeAllProfiles(): Flow<List<EquipmentProfile>>

    @Query("SELECT * FROM equipment_profiles")
    suspend fun getAllProfiles(): List<EquipmentProfile>

    @Query("SELECT * FROM equipment_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): EquipmentProfile?

    @Insert suspend fun insertProfile(p: EquipmentProfile): Long
    @Update suspend fun updateProfile(p: EquipmentProfile)
    @Query("DELETE FROM equipment_profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("SELECT * FROM equipment_profile_photos")
    fun observeAllProfilePhotos(): Flow<List<EquipmentProfilePhoto>>

    @Query("SELECT * FROM equipment_profile_photos WHERE profileId = :profileId ORDER BY createdAt ASC")
    fun observePhotosByProfile(profileId: Long): Flow<List<EquipmentProfilePhoto>>

    @Query("SELECT * FROM equipment_profile_photos WHERE profileId IN (:profileIds)")
    suspend fun getPhotosByProfileIds(profileIds: List<Long>): List<EquipmentProfilePhoto>

    @Query("SELECT * FROM equipment_profile_photos")
    suspend fun getAllProfilePhotos(): List<EquipmentProfilePhoto>

    @Query("SELECT * FROM equipment_profile_photos WHERE profileId = :profileId")
    suspend fun getPhotosByProfile(profileId: Long): List<EquipmentProfilePhoto>

    @Insert suspend fun insertProfilePhoto(p: EquipmentProfilePhoto): Long
    @Query("DELETE FROM equipment_profile_photos WHERE id = :id")
    suspend fun deleteProfilePhoto(id: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS equipment_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                exerciseId INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                tag TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_photos_exerciseId ON equipment_photos(exerciseId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS gyms (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gyms_name ON gyms(name)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                gymId INTEGER NOT NULL,
                exerciseName TEXT NOT NULL,
                equipmentName TEXT NOT NULL,
                seat TEXT NOT NULL,
                backrest TEXT NOT NULL,
                grip TEXT NOT NULL,
                weightStack TEXT NOT NULL,
                pin TEXT NOT NULL,
                assist TEXT NOT NULL,
                pulley TEXT NOT NULL,
                other TEXT NOT NULL,
                notes TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(gymId) REFERENCES gyms(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_profiles_gymId ON equipment_profiles(gymId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_profile_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                profileId INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                tag TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(profileId) REFERENCES equipment_profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_equipment_profile_photos_profileId ON equipment_profile_photos(profileId)")

        db.execSQL("ALTER TABLE exercises ADD COLUMN isCoreAddon INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE exercises ADD COLUMN equipmentProfileId INTEGER")
    }
}

@Database(
    entities = [
        Session::class, ExerciseEntry::class, SetEntry::class,
        LegacyEquipmentPhoto::class,
        Gym::class, EquipmentProfile::class, EquipmentProfilePhoto::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "training.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}

fun assembleSessions(
    sessions: List<Session>,
    exercises: List<ExerciseEntry>,
    sets: List<SetEntry>,
    profiles: List<EquipmentProfile>,
    profilePhotos: List<EquipmentProfilePhoto>,
    gyms: List<Gym>
): List<SessionWithExercises> {
    val exBySession = exercises.groupBy { it.sessionId }
    val setsByExercise = sets.groupBy { it.exerciseId }
    val profileById = profiles.associateBy { it.id }
    val gymById = gyms.associateBy { it.id }
    val firstPhotoByProfile = profilePhotos.groupBy { it.profileId }.mapValues { it.value.firstOrNull() }
    return sessions.map { s ->
        SessionWithExercises(
            s,
            exBySession[s.id].orEmpty().map { ex ->
                val profile = ex.equipmentProfileId?.let { profileById[it] }
                ExerciseWithSets(
                    ex,
                    setsByExercise[ex.id].orEmpty(),
                    profile,
                    profile?.let { firstPhotoByProfile[it.id] },
                    profile?.let { gymById[it.gymId]?.name } ?: ""
                )
            }
        )
    }
}

fun assembleEquipment(
    profiles: List<EquipmentProfile>,
    photos: List<EquipmentProfilePhoto>,
    gyms: List<Gym>
): List<EquipmentWithPhotos> {
    val photosByProfile = photos.groupBy { it.profileId }
    val gymById = gyms.associateBy { it.id }
    return profiles.map { p ->
        EquipmentWithPhotos(p, photosByProfile[p.id].orEmpty(), gymById[p.gymId]?.name ?: "")
    }
}
