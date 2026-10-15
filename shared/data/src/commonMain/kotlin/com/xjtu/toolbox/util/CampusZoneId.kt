package com.xjtu.toolbox.util

import java.time.ZoneId

/** [CampusTimeZone] 的 java.time 版本：这一层的 `LocalDate.now(CampusZoneId)` 按学校时区算「今天」。 */
val CampusZoneId: ZoneId = ZoneId.of(CAMPUS_TIME_ZONE_ID)
