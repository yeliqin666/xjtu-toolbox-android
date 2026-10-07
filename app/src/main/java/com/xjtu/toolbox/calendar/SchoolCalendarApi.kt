package com.xjtu.toolbox.calendar

import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val CALENDAR_URL = "https://workflow.xjtu.edu.cn/selectpage/site/calendar/getData"

/**
 * 校历取数（`:app` 端）：直连 `workflow.xjtu.edu.cn` 的工作流门户首页小组件接口。
 *
 * 搬迁后的分工 —— 这个文件**只剩 IO**：
 *  - 模型（[SchoolTerm] / [CalendarEvent]）、解析（[parseUpstreamSchoolCalendar]）、
 *    屏幕（[SchoolCalendarScreen]）全在 `:core`；
 *  - 这里只负责「用 App 的 okhttp 客户端把响应体拿回来」，以及把阻塞调用放进
 *    [Dispatchers.IO]（客户端是 okhttp，`HttpClients.base` 与搬迁前是同一个实例）。
 *
 * ⚠️ 接口免登录，所以这里**不带任何凭据**：原来那套走 EIP 门户（`one2020.xjtu.edu.cn`）的方案，
 * 实测证明登录态没法 SSO 过去，逼用户重登完全没用（详见这个文件的历史）。
 */
class SchoolCalendarApi : SchoolCalendarSource {

    /** 默认配置即可，直接用共享基础客户端，见 [HttpClients]。 */
    private val client: OkHttpClient = HttpClients.base

    override suspend fun terms(): List<SchoolTerm> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CALENDAR_URL).get().build()
        val body = client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: throw RuntimeException("校历接口无响应")
        }
        parseUpstreamSchoolCalendar(body)
    }
}
