package com.xjtu.toolbox.calendar

import com.sun.net.httpserver.HttpExchange

/**
 * 假的**校历门户**（`workflow.xjtu.edu.cn`）—— 学校那个选人 widget 接口的两个消费方共用同一份响应。
 *
 * ## 为什么它和 `LibraryFakeUpstream` 放在同一层
 *
 * 两者面对的是**同一个会话内核**：校历接口虽然免登录，但它走的是同一个 `HttpClients.base`
 * （同一个 okhttp 实例、同一套 cookie jar 与拦截器）。桌面端的离屏证据要一次跑通两条链路
 * （图书馆要登录、校历不要），所以两个假上游由 `:testkit` 的 [com.xjtu.toolbox.FakeCampusProxy]
 * 按 **host** 分派到这一个本地服务器上。
 *
 * ## 响应形状
 *
 * 逐字段对着 [parseUpstreamSchoolCalendar]（`：core`）写：
 * 外层 `{e, m, d}`（`e == 0` 才算成功），`d.semesters[]` 每项一个学期，学期里
 * `start_date` 是开学日，结束日按 `exam_end` → `term_end_date` → `end_date` 的次序取第一个合法的，
 * `holidays[]` 是带起止日期的假期，`specialEvents[]` 是按标题对应的说明文字。
 *
 * 日期刻意跨过「今天」（2026-10-09 落在第一学期第 5 周），好让离屏截图里的进度条、周次、
 * 「今天所在的事件」都不是空状态 —— 空状态的图证明不了那些组件。
 */
class SchoolCalendarFakeUpstream {

    companion object {
        /** 与生产代码里 `SchoolCalendarApi.CALENDAR_URL` 的 host 一致（URL 不重写，只走代理）。 */
        const val HOST = "workflow.xjtu.edu.cn"

        /** 上游路径，`SchoolCalendarApi` 里硬编码的那一条（路径漂了夹具会 404）。 */
        const val PATH = "/selectpage/site/calendar/getData"

        /**
         * 消费者要传给 `SchoolCalendarApi(url)` 的**完整**地址（含路径）。
         *
         * **是 http**：真机上的 `workflow.xjtu.edu.cn` 是 https，而本地假代理是纯 HTTP 代理，
         * 给不了 CONNECT+TLS 隧道 ⇒ 离屏证据与 `:desktop:test` 把基址指到这里。
         * 校历解析不看 URL（只看 JSON 形状），所以这一改不影响「测的是不是真解析链路」。
         */
        const val URL = "http://$HOST$PATH"
    }

    /** 两个学期 + 几个假期：够画学期切换、周次、进度与事件列表。 */
    val calendarJson: String = """
        {"e":0,"m":"","d":{"semesters":[
          {"id":"2026-2027-1","year":"2026-2027","name":"第一学期",
           "start_date":"2026-09-07","term_end_date":"2027-01-08","exam_end":"2027-01-15",
           "holidays":[
             {"title":"中秋节","start_date":"2026-09-25","end_date":"2026-09-27"},
             {"title":"国庆节","start_date":"2026-10-01","end_date":"2026-10-07"},
             {"title":"元旦","start_date":"2027-01-01","end_date":"2027-01-03"}
           ],
           "specialEvents":[
             {"title":"国庆节","content":"放假 7 天；调休与补课安排以教务处通知为准。"}
           ]},
          {"id":"2026-2027-2","year":"2026-2027","name":"第二学期",
           "start_date":"2027-02-22","term_end_date":"2027-06-25","exam_end":"2027-07-02",
           "holidays":[
             {"title":"劳动节","start_date":"2027-05-01","end_date":"2027-05-05"}
           ],
           "specialEvents":[]}
        ]}}
    """.trimIndent()

    fun handle(exchange: HttpExchange) {
        val body = calendarJson.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}
