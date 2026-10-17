package com.xjtu.toolbox.calendar

import com.sun.net.httpserver.HttpExchange

/** 假的校历门户（`workflow.xjtu.edu.cn`）—— 学校那个选人 widget 接口的两个消费方共用同一份响应。 */
class SchoolCalendarFakeUpstream {

    companion object {
        /** 与生产代码里 `SchoolCalendarApi.CALENDAR_URL` 的 host 一致（URL 不重写，只走代理）。 */
        const val HOST = "workflow.xjtu.edu.cn"

        /** 上游路径，`SchoolCalendarApi` 里硬编码的那一条（路径漂了夹具会 404）。 */
        const val PATH = "/selectpage/site/calendar/getData"

        /** 消费者要传给 `SchoolCalendarApi(url)` 的完整地址（含路径）。 */
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
