package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusApi
import com.xjtu.toolbox.core.net.TermStartData
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate

/**
 * 课表的共享加载器：把课表端点的原始行变成 [CourseTable]。
 *
 * 这是「消灭两个仓库各算一遍」的落点 —— Android 与 Web 用**同一份**取数与周次展开。
 * Android 端现在还在用自己的 okhttp 版 `ScheduleApi`；等它切到 Ktor（探针已证明可行）就换成这个。
 *
 * 两个后端的行数不同、形状相同（campus-api 给上游 47 列；serve 模式给 [CampusApi.schedule]
 * 那 8 列；两者都由 `ScheduleRow` 解码），所以只有**信封**要分两条读法 —— 那是 [CampusApi] 的事。
 *
 * @param baseUrl 留空 ⇒ 走相对路径，即**同源**。Web 端必须这样：浏览器直连学校/直连
 *   `127.0.0.1:3099` 都拿不到 CORS 头（campus-api 刻意不给零鉴权端点发）。
 * @param mode 见 [ApiMode]：默认 campus-api（旧行为一字不改），`serve` 模式的 `:web` 装配传
 *   [ApiMode.SERVE]（课表那两条端点 2026-10-11 落地，契约 §5.3）。
 */
class CampusScheduleApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {
    /**
     * 学期起始（`/api/jwxt/term-start`）。单独暴露是因为自检屏要分别探它，
     * 而不是把它藏在 [load] 里只能看一个总的成败。
     */
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
