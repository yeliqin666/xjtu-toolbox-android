package com.xjtu.toolbox.notification

import com.xjtu.toolbox.campusmap.CampusMaps
import com.xjtu.toolbox.campusmap.PlaceKind

/** 空档至少这么长才值得给建议（分钟换算见 [freeGapHint]）。 */
private const val FREE_GAP_MIN_MS = 90 * 60_000L

/**
 * 两节课之间的空档 ≥ 90 分钟时，给一句去处建议：离上一节（没有则下一节）最近的图书馆，带步行时间。
 * 正在上课、没有下一节、空档不够长、找不到地点都返回 null。纯本地计算。
 */
internal fun freeGapHint(items: List<DynamicScheduleItem>, nowMs: Long): String? {
    if (items.any { nowMs >= it.startAt && nowMs < it.endAt }) return null
    val next = items.filter { it.startAt > nowMs }.minByOrNull { it.startAt } ?: return null
    val prev = items.filter { it.endAt <= nowMs }.maxByOrNull { it.endAt }
    val gapStart = prev?.endAt ?: nowMs
    if (next.startAt - gapStart < FREE_GAP_MIN_MS) return null
    val anchor = listOfNotNull(prev, next)
        .firstNotNullOfOrNull { it.course.location.takeIf { loc -> loc.isNotBlank() } } ?: return null
    val (map, place) = CampusMaps.find(anchor) ?: return null
    val library = CampusMaps.nearest(place, map, PlaceKind.LIBRARY, 1).firstOrNull() ?: return null
    val gapMin = (next.startAt - gapStart) / 60_000L
    return "空堂 $gapMin 分钟 · 最近图书馆 ${library.first.name}，步行约 ${library.second} 分钟"
}
