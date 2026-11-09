package com.example.traininglog

object MarkdownExporter {

    fun displayExerciseName(ex: ExerciseWithSets): String {
        val base = ex.exercise.exerciseName
        return if (ex.exercise.weight < 0) "助力$base" else base
    }

    fun loadDisplay(weight: Double): String = when {
        weight < 0 -> "助力${fmt(-weight)}kg"
        weight == 0.0 -> "自重"
        else -> "${fmt(weight)}kg"
    }

    private fun fmt(w: Double): String =
        if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()

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

            val all = list.flatMap { it.exercises }
            val main = all.filter { !it.exercise.isCoreAddon }
            val core = all.filter { it.exercise.isCoreAddon }

            main.forEach { render(sb, it, level = "###") }

            if (core.isNotEmpty()) {
                sb.appendLine("### 训练后核心附加")
                sb.appendLine()
                core.forEach { render(sb, it, level = "####") }
            }
        }
        return sb.toString()
    }

    private fun render(sb: StringBuilder, ex: ExerciseWithSets, level: String) {
        sb.appendLine("$level ${displayExerciseName(ex)}")
        sb.appendLine("- 重量：${loadDisplay(ex.exercise.weight)} × ${ex.exercise.plannedReps}次 × ${ex.exercise.totalSets}组")
        val done = ex.sets.count { it.completed }
        sb.appendLine("- 完成：$done/${ex.exercise.totalSets} 组")
        if (ex.exercise.notes.isNotBlank())
            sb.appendLine("- 备注：${ex.exercise.notes}")
        ex.equipmentProfile?.let { p ->
            val settings = buildList {
                if (p.seat.isNotBlank()) add("座位${p.seat}")
                if (p.backrest.isNotBlank()) add("靠背${p.backrest}")
                if (p.grip.isNotBlank()) add("握把${p.grip}")
                if (p.weightStack.isNotBlank()) add("配重${p.weightStack}")
                if (p.pin.isNotBlank()) add("插销${p.pin}")
                if (p.assist.isNotBlank()) add("助力${p.assist}")
                if (p.pulley.isNotBlank()) add("滑轮${p.pulley}")
                if (p.other.isNotBlank()) add(p.other)
            }
            val str = if (settings.isEmpty()) "" else "｜${settings.joinToString("、")}"
            sb.appendLine("- 器械：${p.equipmentName.ifBlank { p.exerciseName }}（${ex.gymName}）$str")
            if (ex.equipmentPhoto != null) sb.appendLine("- 器械照片：有")
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
