package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusApi
import com.xjtu.toolbox.core.net.TermStartData
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate

/** 课表的共享加载器：把课表端点的原始行变成 [CourseTable]。 */
class CampusScheduleApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {
    /** 学期起始（`/api/jwxt/term-start`）。 */
    suspend fun termStart(): TermStartData = CampusApi(client, baseUrl, mode).termStart()

    /** 当前学期号（`/api/jwxt/term`）。同上：自检屏要单独探。 */
    suspend fun term(): String = CampusApi(client, baseUrl, mode).term()


    suspend fun load(): CourseTable {
        val api = CampusApi(client, baseUrl, mode)
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
