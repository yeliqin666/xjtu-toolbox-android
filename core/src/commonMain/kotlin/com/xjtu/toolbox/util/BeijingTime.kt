package com.xjtu.toolbox.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/** 校园上游给的 `"2026-10-07 20:00:02"`（东八区、不带偏移量）→ epoch 毫秒；认不出来返回 0。 */
fun beijingEpochMs(text: String?): Long {
    val t = text?.take(19)?.takeIf { it.length >= 19 } ?: return 0L
    return runCatching {
        LocalDateTime.parse(t.replace(' ', 'T')).toInstant(UtcOffset(hours = 8, minutes = 0)).toEpochMilliseconds()
    }.getOrDefault(0L)
}
