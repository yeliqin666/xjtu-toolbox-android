package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportCache
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.builtins.ListSerializer

/** Web 端的成绩 SWR 缓存：`localStorage` + 与 `:app` 同一个 [AppJson] 编码。 */
class WebScoreReportCache : ScoreReportCache {

    private val store = keyValueStore(STORE_NAME)

    override fun read(): List<ReportedGrade>? {
        val raw = store.getString(KEY) ?: return null
        return runCatching {
            AppJson.decodeFromString(ListSerializer(ReportedGrade.serializer()), raw)
        }.getOrNull()
    }

    override fun write(grades: List<ReportedGrade>) {
        runCatching {
            store.putString(KEY, AppJson.encodeToString(ListSerializer(ReportedGrade.serializer()), grades))
        }
    }

    private companion object {
        const val STORE_NAME = "score_report_cache"
        const val KEY = "all"
    }
}
