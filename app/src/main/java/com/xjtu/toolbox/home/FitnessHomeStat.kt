package com.xjtu.toolbox.home

import com.xjtu.toolbox.fitness.FitnessScore
import com.xjtu.toolbox.fitness.hasUsableTotal

internal fun fitnessHomeStat(year: String, score: FitnessScore): HomeStat = HomeStat(
    value = if (score.hasUsableTotal()) score.totalScore else "部分已出",
    detail = listOf(year, score.totalGrade).filter { it.isNotBlank() && it != "未测" }.joinToString(" · "),
    lines = score.items.filter { it.value.isNotBlank() && it.value !in listOf("--", "未测") && it.grade != "缺项" }
        .map { HomeLine(it.name, detail = listOf(it.value, it.grade).filter(String::isNotBlank).joinToString(" · ")) },
)
