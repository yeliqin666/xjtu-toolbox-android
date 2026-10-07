package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportCache
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Web 端的成绩 SWR 缓存：`localStorage` + 与 `:app` **同一个** [AppJson] 编码。
 *
 * 与 `:app` 的 `DataCache` 是同一个语义（先给旧值秒显、后台再刷、拉到了写回去），
 * 差别只在落点：那边是按账号隔离的 JSON 文件，这边没有账号也没有文件系统，就是浏览器的一个键。
 * TTL 也由这个实现自己定 —— 屏幕不管过期，读到什么就先显示（读不到就当真拉）。
 */
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
