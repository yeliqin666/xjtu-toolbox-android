package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.iclassface.AppIclassfaceSource
import com.xjtu.toolbox.iclassface.CheckinRecord
import com.xjtu.toolbox.util.todayOnCampus
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** `/api/iclassface*`。 */
internal fun Route.iclassfaceRoutes(session: ServeSession) {
    route(ICLASSFACE_SEGMENT) {

        // ── 某一天的签到流水 ──────────────────────────────────────────
        get(ICLASSFACE_RECORDS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val raw = call.request.queryParameters[ICLASSFACE_PARAM_DATE]?.trim().orEmpty()
            val date = if (raw.isEmpty()) {
                todayOnCampus()
            } else {
                runCatching { LocalDate.parse(raw) }.getOrNull()
                    ?: return@get call.respondBadRequestMessage(
                        "$ICLASSFACE_PARAM_DATE 需要形如 2026-09-14（省略 = 今天）",
                    )
            }
            try {
                val records = session.iclassfaceSource().fetchRecords(date)
                call.respond(
                    ApiEnvelope.ok(
                        IclassfaceRecordsData(date = date.toString(), count = records.size, records = records.map { it.toDto() }),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "查询签到记录")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/iclassface/records` 的 `data`。 */
@Serializable
internal data class IclassfaceRecordsData(
    /** 定下来的那一天（`YYYY-MM-DD`）：省略 `?date=` 时就是学校时区（Asia/Shanghai）的今天。 */
    val date: String,
    val count: Int,
    val records: List<IclassfaceRecordDto>,
)

/** 一条签到记录（`:core` 的 [CheckinRecord] 的线上形状，去掉姓名与学号 —— 见文件头 KDoc）。 */
@Serializable
internal data class IclassfaceRecordDto(
    /** 上游原文的 `2026-06-04 14:14:37`（服务端时区的墙上时间；本契约唯一一处不转 ISO-8601 的时间）。 */
    val time: String,
    val location: String,
    val type: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun CheckinRecord.toDto() = IclassfaceRecordDto(time = time, location = location, type = type)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 人脸识别签到站点会话（key = `iclassface`，`:data` 的 `IclassfaceSession`）。 */
internal fun ServeSession.iclassfaceSite(): SiteSession = sessionManager.getSite(ServeSession.ICLASSFACE_SITE_KEY)

internal fun ServeSession.iclassfaceSource(): AppIclassfaceSource = AppIclassfaceSource(iclassfaceSite())

internal const val ICLASSFACE_SEGMENT = "iclassface"
internal const val ICLASSFACE_RECORDS = "records"
internal const val ICLASSFACE_PARAM_DATE = "date"
