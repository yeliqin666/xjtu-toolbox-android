package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.iclassface.CheckinRecord
import com.xjtu.toolbox.iclassface.IclassfaceSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * 人脸识别签到（`iclassface.xjtu.edu.cn`，「快速考勤流水」）的 **serve 版取数**：给 `:web` 用，
 * 实现 [IclassfaceSource]。这一族**整屏可复用**。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/iclassface/records?date=` | [fetchRecords] |
 *
 * 契约 §5.6；`:server` 那一侧是 `IclassfaceRoutes.kt`（薄包装 `:data` 的 `AppIclassfaceSource`）。
 *
 * ## 三格都在，姓名与学号不投影（如实缺口，不是 bug）
 *
 * 端出来的是 `time` / `location` / `type` 三格 —— `:core` 的 `IclassfaceScreen` 画的正是这三格
 * （它本来就不画姓名与学号），所以屏一行未改。上游那张表一行是
 * `<序号, 姓名, 学号, 时间, 地点, 类型>`，前两格是**姓名与学号**（§5.2 红线，例外清单里没有
 * 这一族）⇒ 本类给 [CheckinRecord.name] / [CheckinRecord.studentNo] 留**空串**（模型自己要这两个
 * 参数，但屏上不画、也不编一个假名字出来）。**照片也没有**：这一族的上游根本没有照片字段。
 *
 * ## ⚠️ `time` 是**服务端时区的墙上时间**，本端**不动它**
 *
 * `time` 是上游原文的 `2026-06-04 14:14:37`（**不是** ISO-8601 —— 这是契约里唯一一处不按 §4
 * 转 ISO-8601 的时间）。它本来就是给人看的刷卡时刻，上游没给时区标记 ⇒ 本类**原样放进模型、
 * 不 `parse`、也不本地化**（转一次只会整体平移几个钟头，比不转更错）。屏上直接显示这个字符串。
 *
 * ## 日期与空表
 *
 * [fetchRecords] 把屏上选中的那一天按 `?date=YYYY-MM-DD` 原样发出去（`:core` 的屏默认选中
 * 「服务端系统时区的今天」，与端点省略 `?date=` 时的默认值同一条式子）。响应里的空数组 =
 * **那天确实没有记录**（含「还没刷上卡」），**不是**错误 —— 本类如实回空表，屏上那句「当天没有
 * 签到/刷卡记录」就落在这里。没有分页：上游就是「一天一枪、返回整张表」。
 *
 * ## 默认形态（campus-api）
 *
 * 人脸识别签到在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支
 * 一律 [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
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
                // 见类 KDoc：姓名与学号不投影（红线）⇒ 空串；屏上本来也不画那两格
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
