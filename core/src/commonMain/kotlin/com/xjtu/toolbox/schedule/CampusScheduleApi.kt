package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.CampusApi
import com.xjtu.toolbox.core.net.TermStartData
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate

/**
 * 课表的共享加载器：把 campus-api 的原始 47 列变成 [CourseTable]。
 *
 * 这是「消灭两个仓库各算一遍」的落点 —— Android 与 Web 用**同一份**取数与周次展开。
 * Android 端现在还在用自己的 okhttp 版 `ScheduleApi`；等它切到 Ktor（探针已证明可行）就换成这个。
 *
 * @param baseUrl 留空 ⇒ 走相对路径，即**同源**。Web 端必须这样：浏览器直连学校/直连
 *   `127.0.0.1:3099` 都拿不到 CORS 头（campus-api 刻意不给零鉴权端点发）。
 */
class CampusScheduleApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) {
    /**
     * 学期起始（`/api/jwxt/term-start`）。单独暴露是因为自检屏要分别探它，
     * 而不是把它藏在 [load] 里只能看一个总的成败。
     */
    suspend fun termStart(): TermStartData = CampusApi(client, baseUrl).termStart()

    /** 当前学期号（`/api/jwxt/term`）。同上：自检屏要单独探。 */
    suspend fun term(): String = CampusApi(client, baseUrl).term()


    suspend fun load(): CourseTable {
        val api = CampusApi(client, baseUrl)
        val term = api.term()
        val start = api.termStart()
        val startDate = start.startDate?.takeIf { it.isNotBlank() }
            ?: error("term-start 缺 startDate（term=${start.term}）")
        return CourseTable(
            term = term,
            termStart = LocalDate.parse(startDate),
            totalWeeks = start.totalWeeks,
            slots = api.schedule(term).rows.map { it.toCourseSlot() },
        )
    }
}
