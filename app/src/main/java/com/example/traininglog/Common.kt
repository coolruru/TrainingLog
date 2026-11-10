package com.example.traininglog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

val EXERCISES = mapOf(
    "胸" to listOf("杠铃卧推","哑铃卧推","上斜卧推","下斜推胸","固定上斜卧推","双杠臂屈伸","坐姿飞鸟","绳索夹胸"),
    "背" to listOf("引体向上","助力引体","杠铃划船","高位下拉","超级高位下拉","坐姿划船","悍马划船","直臂下压","硬拉"),
    "肩" to listOf("哑铃推举","固定肩推","悍马肩推","侧平举","绳索侧平举","面拉","后束飞鸟"),
    "腿" to listOf("深蹲","硬拉","腿举","罗马尼亚硬拉"),
    "核心" to listOf("悬垂举腿","器械卷腹")
)
val BODY_PARTS = listOf("胸", "背", "肩", "腿")
val TRAINING_GOALS = listOf("力量", "增肌", "容量", "减载")
val PAIN_LOCATIONS = listOf("肩前", "肩后", "肘", "下背", "膝", "髋", "其他")
val PHOTO_TAGS = listOf("座位", "靠背", "握把", "配重", "插销", "助力", "滑轮", "其他")

data class PhotoInput(val filePath: String, val tag: String)

data class ExercisePlan(
    val exerciseName: String,
    val weight: Double,
    val plannedReps: Int,
    val totalSets: Int,
    val restSeconds: Int,
    val notes: String,
    val isCoreAddon: Boolean = false,
    val equipmentProfileId: Long? = null
)

data class WorkoutPlan(
    val date: String,
    val bodyPart: String,
    val goal: String,
    val sessionNotes: String,
    val exercises: List<ExercisePlan>
)

fun fileForPath(ctx: Context, relPath: String): File =
    File(if (relPath.startsWith("/")) relPath else File(ctx.filesDir, relPath).path)

fun displayLoad(weight: Double): String = when {
    weight < 0 -> "助力${fmtW(-weight)}kg"
    weight == 0.0 -> "自重"
    else -> "${fmtW(weight)}kg"
}

fun fmtW(w: Double): String =
    if (w == w.toLong().toDouble()) w.toLong().toString()
    else String.format(Locale.getDefault(), "%.1f", w)

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

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
