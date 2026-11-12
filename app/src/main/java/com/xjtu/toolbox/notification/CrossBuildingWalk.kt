package com.xjtu.toolbox.notification

import com.xjtu.toolbox.campusmap.CampusMaps

/**
 * 跨楼「该出发」提醒：上一节课与下一节课不在同一栋楼时，按校内步行时间算出出发时刻。
 * 同楼、连堂（上一节刚结束）、找不到地点、跨校区的都不提醒，保持倒计时原样。
 */
internal class WalkPlan(val leaveAt: Long, val minutes: Int, val toBuilding: String, val startAt: Long)

/** 出发前给一点余量，别刚好卡点才提醒。 */
private const val LEAVE_SLACK_MS = 3 * 60_000L

internal fun walkPlanFor(items: List<DynamicScheduleItem>, next: DynamicScheduleItem): WalkPlan? {
    val prev = items
        .filter { it !== next && it.endAt <= next.startAt }
        .maxByOrNull { it.endAt } ?: return null
    // 连堂（上一节刚下）按现状，不额外打扰
    if (prev.endAt >= next.startAt) return null
    val fromLoc = prev.course.location.takeIf { it.isNotBlank() } ?: return null
    val toLoc = next.course.location.takeIf { it.isNotBlank() } ?: return null
    val fromBuilding = CampusMaps.buildingOf(fromLoc)
    val toBuilding = CampusMaps.buildingOf(toLoc)
    if (fromBuilding == toBuilding) return null
    val (fromMap, fromPlace) = CampusMaps.find(fromLoc) ?: return null
    val (toMap, toPlace) = CampusMaps.find(toLoc) ?: return null
    if (fromMap.campus != toMap.campus) return null
    val minutes = CampusMaps.walkMinutes(fromPlace, toPlace)
    return WalkPlan(
        leaveAt = next.startAt - minutes * 60_000L - LEAVE_SLACK_MS,
        minutes = minutes,
        toBuilding = toBuilding,
        startAt = next.startAt,
    )
}

/** 到了出发时刻才给文案；返回 null 表示仍用倒计时。 */
internal fun walkHintText(walk: WalkPlan, nowMs: Long): String? {
    if (nowMs < walk.leaveAt || nowMs >= walk.startAt) return null
    val leftMin = ((walk.startAt - nowMs) / 60_000L).coerceAtLeast(0L)
    return "该去 ${walk.toBuilding} 了，步行约 ${walk.minutes} 分钟（$leftMin 分钟后上课）"
}
