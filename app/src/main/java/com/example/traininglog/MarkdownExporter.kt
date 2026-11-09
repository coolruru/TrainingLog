package com.example.traininglog

object MarkdownExporter {

    fun build(
        sessions: List<SessionWithExercises>,
        rangeDesc: String,
        partDesc: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# 训练记录")
        sb.appendLine()
        sb.appendLine("- 日期范围：$rangeDesc")
        sb.appendLine("- 部位：$partDesc")
        sb.appendLine("- 记录数：${sessions.size} 条")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        val grouped = sessions
            .groupBy { it.session.date to it.session.bodyPart }
            .toSortedMap(compareBy({ it.first }, { it.second }))

        for ((key, list) in grouped) {
            val (date, part) = key
            sb.appendLine("## $date｜$part")
            sb.appendLine()

            val sessionNotes = list.mapNotNull { it.session.notes.ifBlank { null } }.distinct()
            val pains = list.map { it.session.painScore to it.session.painLocation }
                .filter { it.first > 0 }
                .distinct()
            if (sessionNotes.isNotEmpty())
                sb.appendLine("> 训练备注：${sessionNotes.joinToString("；")}")
            if (pains.isNotEmpty())
                sb.appendLine("> 疼痛：${pains.joinToString("；") { "${it.second} ${it.first}/10" }}")
            if (sessionNotes.isNotEmpty() || pains.isNotEmpty()) sb.appendLine()

            val allExercises = list.flatMap { it.exercises }
            for (ex in allExercises) {
                sb.appendLine("### ${ex.exercise.exerciseName}")
                sb.appendLine("- 重量：${ex.exercise.weight}kg × ${ex.exercise.plannedReps}次 × ${ex.exercise.totalSets}组")
                val done = ex.sets.count { it.completed }
                sb.appendLine("- 完成：$done/${ex.exercise.totalSets} 组")
                if (ex.exercise.notes.isNotBlank())
                    sb.appendLine("- 备注：${ex.exercise.notes}")
                if (ex.photos.isNotEmpty()) {
                    val tagCount = ex.photos.groupingBy { it.tag }.eachCount()
                        .entries.joinToString("、") { "${it.key}${it.value}张" }
                    sb.appendLine("- 器械照片：${ex.photos.size} 张（$tagCount）")
                }
                sb.appendLine("- 组明细：")
                ex.sets.sortedBy { it.setIndex }.forEach { set ->
                    val mark = if (set.completed) "✓完成" else "✗未完成"
                    val extra = if (!set.completed) "，力竭${set.failureReps}次" else ""
                    sb.appendLine("  - 第${set.setIndex}组：$mark，实际${set.actualReps}次$extra")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }
}
