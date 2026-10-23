package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportCache
import com.xjtu.toolbox.util.AppJson
import kotlin.time.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Web 端的成绩 SWR 缓存：`localStorage` + 与 `:app` 同一个 [AppJson] 编码。
 *
 * 载荷形状与 TTL 与桌面那份（`desktop/src/main/kotlin/com/xjtu/toolbox/desktop/score/DesktopScoreReportCache.kt`；
 * 那个模块本端看不见，故不写 KDoc 链接）对齐：`{"at":<ms>,"grades":[...]}` + 7 天。
 * 升级前写进去的是裸数组（没有 `at`）⇒ 年龄不可知，按过期处理（[read] 返回 null，上层重取）。
 */
class WebScoreReportCache(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ScoreReportCache {

    private val store = keyValueStore(STORE_NAME)

    override fun read(): List<ReportedGrade>? {
        val raw = store.getString(KEY) ?: return null
        // 裸数组（旧形状）在这里就落空 ⇒ null，不抛
        val obj = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return null
        if (now() - at > TTL_MS) return null
        val grades = obj["grades"]?.jsonArray ?: return null
        return runCatching {
            AppJson.decodeFromJsonElement(ListSerializer(ReportedGrade.serializer()), grades)
        }.getOrNull()
    }

    override fun write(grades: List<ReportedGrade>) {
        val payload = buildJsonObject {
            put("at", now())
            put("grades", AppJson.encodeToJsonElement(ListSerializer(ReportedGrade.serializer()), grades))
        }
        runCatching { store.putString(KEY, payload.toString()) }
    }

    /** `internal`：档案导入写回同一张店同一个键（§6.3 逐族迁移：成绩缓存那一族）。 */
    internal companion object {
        const val STORE_NAME = "score_report_cache"
        const val KEY = "all"

        /** 与桌面 / `:app` 的 `DataCache.DEFAULT_TTL_MS` 同一句话（7 天）。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
