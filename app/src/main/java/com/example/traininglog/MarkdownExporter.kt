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

        val byDate = sessions.groupBy { it.session.date }.toSortedMap()
        for ((date, list) in byDate) {
            val byPart = list.groupBy { it.session.bodyPart }
            for ((part, partSessions) in byPart) {
                sb.appendLine("## $date｜$part")
                sb.appendLine()
                for (s in partSessions) {
                    val pain = if (s.session.painScore > 0)
                        "，疼痛：${s.session.painLocation} ${s.session.painScore}/10" else ""
                    if (s.session.notes.isNotBlank())
                        sb.appendLine("> 训练备注：${s.session.notes}$pain")
                    sb.appendLine()
                    for (ex in s.exercises) {
                        sb.appendLine("### ${ex.exercise.exerciseName}")
                        sb.appendLine("- 重量：${ex.exercise.weight}kg × ${ex.exercise.plannedReps}次 × ${ex.exercise.totalSets}组")
                        val done = ex.sets.count { it.completed }
                        sb.appendLine("- 完成：$done/${ex.exercise.totalSets} 组")
                        if (ex.exercise.notes.isNotBlank())
                            sb.appendLine("- 备注：${ex.exercise.notes}")
                        sb.appendLine("- 组明细：")
                        ex.sets.sortedBy { it.setIndex }.forEach { set ->
                            val mark = if (set.completed) "✓完成" else "✗未完成"
                            val extra = if (!set.completed) "，力竭${set.failureReps}次" else ""
                            sb.appendLine("  - 第${set.setIndex}组：$mark，实际${set.actualReps}次$extra")
                        }
                        sb.appendLine()
                    }
                }
            }
        }
        return sb.toString()
    }
}
