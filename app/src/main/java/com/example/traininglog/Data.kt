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
    val notes: String = ""
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
data class EquipmentPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val filePath: String,
    val tag: String = "其他",
    val createdAt: Long = System.currentTimeMillis()
)

data class ExerciseWithSets(
    val exercise: ExerciseEntry,
    val sets: List<SetEntry>,
    val photos: List<EquipmentPhoto> = emptyList()
)

data class SessionWithExercises(
    val session: Session,
    val exercises: List<ExerciseWithSets>
)

@Dao
interface TrainingDao {
    @Query("SELECT * FROM sessions ORDER BY date DESC, createdAt DESC")
    fun observeSessions(): Flow<List<Session>>

    @Query("SELECT * FROM exercises")
    fun observeAllExercises(): Flow<List<ExerciseEntry>>

    @Query("SELECT * FROM sets")
    fun observeAllSets(): Flow<List<SetEntry>>

    @Query("SELECT * FROM equipment_photos")
    fun observeAllPhotos(): Flow<List<EquipmentPhoto>>

    @Query("SELECT * FROM sessions WHERE date = :date AND bodyPart = :part ORDER BY id ASC LIMIT 1")
    suspend fun getSessionByDateAndPart(date: String, part: String): Session?

    @Query("SELECT * FROM exercises WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getExercisesBySession(sessionId: Long): List<ExerciseEntry>

    @Query("SELECT * FROM sets WHERE exerciseId IN (:exerciseIds)")
    suspend fun getSetsByExerciseIds(exerciseIds: List<Long>): List<SetEntry>

    @Query("SELECT * FROM equipment_photos WHERE exerciseId IN (:exerciseIds)")
    suspend fun getPhotosByExerciseIds(exerciseIds: List<Long>): List<EquipmentPhoto>

    @Query("SELECT * FROM sessions WHERE date >= :from AND date <= :to ORDER BY date ASC")
    suspend fun getBetween(from: String, to: String): List<Session>

    @Query("SELECT * FROM sessions ORDER BY date ASC")
    suspend fun getAllSessions(): List<Session>

    @Query("SELECT * FROM exercises")
    suspend fun getAllExercises(): List<ExerciseEntry>

    @Query("SELECT * FROM sets")
    suspend fun getAllSets(): List<SetEntry>

    @Query("SELECT * FROM equipment_photos")
    suspend fun getAllPhotos(): List<EquipmentPhoto>

    @Insert suspend fun insertSession(s: Session): Long
    @Insert suspend fun insertExercise(e: ExerciseEntry): Long
    @Insert suspend fun insertSets(sets: List<SetEntry>)
    @Insert suspend fun insertPhoto(p: EquipmentPhoto): Long
    @Update suspend fun updateSession(s: Session)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("SELECT * FROM equipment_photos WHERE exerciseId = :exerciseId")
    suspend fun getPhotosByExercise(exerciseId: Long): List<EquipmentPhoto>

    @Query("DELETE FROM equipment_photos WHERE id = :id")
    suspend fun deletePhoto(id: Long)
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

@Database(
    entities = [Session::class, ExerciseEntry::class, SetEntry::class, EquipmentPhoto::class],
    version = 2,
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
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}

fun assemble(
    sessions: List<Session>,
    exercises: List<ExerciseEntry>,
    sets: List<SetEntry>,
    photos: List<EquipmentPhoto>
): List<SessionWithExercises> {
    val exBySession = exercises.groupBy { it.sessionId }
    val setsByExercise = sets.groupBy { it.exerciseId }
    val photosByExercise = photos.groupBy { it.exerciseId }
    return sessions.map { s ->
        SessionWithExercises(
            s,
            exBySession[s.id].orEmpty().map { ex ->
                ExerciseWithSets(
                    ex,
                    setsByExercise[ex.id].orEmpty(),
                    photosByExercise[ex.id].orEmpty()
                )
            }
        )
    }
}
