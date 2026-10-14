package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.iclassface.AppIclassfaceSource
import com.xjtu.toolbox.iclassface.CheckinRecord
import com.xjtu.toolbox.util.todayInSystemZone
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * `/api/iclassface*` —— 人脸识别签到（「快速考勤流水」）的**一个只读端点**（`docs/api-contract.md` §5.6），
 * 走 `:data` 的 [AppIclassfaceSource]（它包住搬进 `:data` 的 `IclassfaceApi`；HTML 表格的解析一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/iclassface/records` | `IclassfaceSource.fetchRecords(date)` |
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `CheckinRecord`）
 *
 * ⚠️ **两格刻意不投影**：`:core` 的 [CheckinRecord] 有五格
 * （`name` / `studentNo` / `time` / `location` / `type`），但上游那张表的一行是
 * `<序号, 姓名, 学号, 时间, 地点, 类型>` —— `name` 与 `studentNo` 是**姓名与学号**
 * （契约 §5.2 红线；例外清单里没有这一族）⇒ **不端**。端出去的三格：
 *
 * - `time`：**上游原文**的 `2026-06-04 14:14:37`（`:data` 原样留的字符串，**不是** ISO-8601）。
 *   ⚠️ 这是本契约里唯一一处**不**按 §4 转 ISO-8601 的时间：它本来就是给人看的刷卡时刻，
 *   而「那天几点刷的」要按**服务端时区**读（上游就是这么写的、没有时区标记）——
 *   服务端硬转一次只会多一处可能错的口径。消费方按「服务端时区的墙上时间」显示，
 *   不要再 `parse` 成 UTC 再本地化（那会整体平移）；
 * - `location`：地点（`中3-2306` 这种，上游原文）；
 * - `type`：凭证类型（`电子班牌刷卡` / `电子班牌人脸识别`，上游原文）。
 *
 * 屏上那张卡片的三个用法（`record.time` / `record.location` / `record.type`）**都在**，
 * 所以 `:core` 的 `IclassfaceScreen` 在 serve 模式下能整屏复用
 * （它本来就不画姓名/学号那两格）。**照片也没有**：这一族的上游根本没有照片字段
 * （`IclassfaceApi` 只解析上面那张表），契约 §5.6 如实记一条。
 *
 * ## 请求参数与错误码
 *
 * - `?date=`：`YYYY-MM-DD`；**省略 = 今天**（`:core` 的 [todayInSystemZone]，
 *   服务端系统时区 —— 与 `:core` 屏上默认选中的那天同一行式子）；给了但不成形 ⇒ 400。
 *   ⚠️ **没有分页**：上游就是「一天一枪 POST，返回整张表」，没有页参数（`:data` 的
 *   `IclassfaceApi.fetchRecords` 一次给全）—— 屏上那条日期条 + 一天一张卡就是这个形状；
 * - 响应回 `date`（定下来的那一天）与 `count` + `records`；空数组 = 那天确实没有记录
 *   （含「还没刷上卡」），**不是**错误（`:data` 的口径，`:core` 屏据此画「当天没有签到/刷卡记录」）；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句，含会话失效那档 ——
 *   `:data` 把上游返「登录失效」那一页认成 `SessionExpiredException`，本层按 502 报）。
 *
 * ⚠️ 与 `/api/attendance/{terms,records}`（§5.5）不是同一件事：那条是**本科考勤业务站**的
 * 课程级流水（有课程名 / 节次 / 状态），这一条是**人脸识别签到平台**的「今天刷没刷上卡」
 * （只有时刻 / 地点 / 类型）。两屏在 `:app` 与桌面上本来就并存、互不替代。
 *
 * @param session 会话装配；人脸识别签到站点（key = `iclassface`，`:data` 的 `IclassfaceSession`）
 *   从它那里取。
 */
internal fun Route.iclassfaceRoutes(session: ServeSession) {
    route(ICLASSFACE_SEGMENT) {

        // ── 某一天的签到流水 ──────────────────────────────────────────
        get(ICLASSFACE_RECORDS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val raw = call.request.queryParameters[ICLASSFACE_PARAM_DATE]?.trim().orEmpty()
            val date = if (raw.isEmpty()) {
                todayInSystemZone()
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
    /** 定下来的那一天（`YYYY-MM-DD`）：省略 `?date=` 时就是服务端系统时区的今天。 */
    val date: String,
    val count: Int,
    val records: List<IclassfaceRecordDto>,
)

/**
 * 一条签到记录（`:core` 的 [CheckinRecord] 的线上形状，**去掉姓名与学号** —— 见文件头 KDoc）。
 */
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
