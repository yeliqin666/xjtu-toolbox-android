package com.xjtu.toolbox.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** [CampusTimeZone] 的 java.time 版本：这一层的 `LocalDate.now(CampusZoneId)` 按学校时区算「今天」。 */
val CampusZoneId: ZoneId = ZoneId.of(CAMPUS_TIME_ZONE_ID)

/** epoch 毫秒按北京时间格式化（java.time 侧的 [campusDateTimeOf]）。 */
fun formatOnCampus(epochMs: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(epochMs).atZone(CampusZoneId))
