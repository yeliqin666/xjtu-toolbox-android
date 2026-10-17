package com.xjtu.toolbox.calendar

import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val CALENDAR_URL = "https://workflow.xjtu.edu.cn/selectpage/site/calendar/getData"

/** 校历取数：直连 `workflow.xjtu.edu.cn` 的工作流门户首页小组件接口。 */
class SchoolCalendarApi(
    /** 上游地址。 */
    private val url: String = CALENDAR_URL,
) : SchoolCalendarSource {

    /** 默认配置即可，直接用共享基础客户端，见 [HttpClients]。 */
    private val client: OkHttpClient = HttpClients.base

    override suspend fun terms(): List<SchoolTerm> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val body = client.newCall(request).execute().use { resp ->
            resp.body?.string() ?: throw RuntimeException("校历接口无响应")
        }
        parseUpstreamSchoolCalendar(body)
    }
}
