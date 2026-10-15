package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.iclassface.CheckinRecord
import com.xjtu.toolbox.iclassface.IclassfaceSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject

/** 人脸识别签到（`iclassface.xjtu.edu.cn`，「快速考勤流水」）的 serve 版取数。 */
class CampusIclassfaceApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.6）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : IclassfaceSource {

    override suspend fun fetchRecords(date: LocalDate): List<CheckinRecord> {
        requireServe()
        val data = client.serveData(
            "查询签到记录",
            baseUrl,
            RECORDS_PATH,
            listOf(DATE_PARAM to date.toString()),
        )
        return data.arr("records").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            CheckinRecord(
                name = "",
                studentNo = "",
                // ⚠️ 上游原文的墙上时间（`2026-06-04 14:14:37`）：不 parse、不转时区，见类 KDoc
                time = row["time"].safeString(),
                location = row["location"].safeString(),
                type = row["type"].safeString(),
            )
        }
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("人脸识别签到取数", RECORDS_PATH)
    }
}

/** 某一天的签到流水（契约 §5.6 的那个 iclassface 端点）。 */
private const val RECORDS_PATH = "/api/iclassface/records"

private const val DATE_PARAM = "date"
