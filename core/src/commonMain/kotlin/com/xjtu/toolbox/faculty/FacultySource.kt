package com.xjtu.toolbox.faculty

/**
 * 教师检索的取数端口。屏与 ViewModel 只认这个接口，两端各自实现：
 * - `:app` = 原来的 `FacultyApi`（okhttp + jsoup：检索 JSON、筛选项 HTML、个人主页 13 套模板）；
 * - `:web` = campus-api 的 `/api/info/faculty`（免登录，同一个上游 `advancesearch.jsp`）。
 *
 * 三个方法都是 suspend；`:app` 的实现内部自己切 IO 调度器。
 */
interface FacultySource {
    /** 检索一页。[FacultySearchQuery.proRank] 是**客户端**过滤（服务端没有这张表）。 */
    suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage

    /**
     * 四张筛选 id 表（学院/学科/招生学科/荣誉）。
     *
     * **允许「拿不到」**：返回 `FacultyFilters()` 时学院/学科两个下拉是空的，
     * 职称那一档仍可用（它由已加载结果推导，见 `FacultyFilters.proRanksFrom`）。
     * campus-api 不解析那四张表（它在 search.jsp 的 HTML 里），Web 端就是这样降级的。
     */
    suspend fun filters(): FacultyFilters

    /**
     * 老师个人主页正文（只做补充，检索 JSON 里没有的段落）。
     *
     * **允许「不解析」**：返回 `HomepageResult.NotStandard(url)` 时详情页显示
     * 「在浏览器中打开」——这正是 :app 端遇到非标准主页时的同一条退路。
     */
    suspend fun homepage(member: FacultyMember): HomepageResult
}
