package com.xjtu.toolbox.faculty

/** 教师检索的取数端口。 */
interface FacultySource {
    /** 检索一页。[FacultySearchQuery.proRank] 是**客户端**过滤（服务端没有这张表）。 */
    suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage

    /** 四张筛选 id 表（学院/学科/招生学科/荣誉）。 */
    suspend fun filters(): FacultyFilters

    /** 老师个人主页正文（只做补充，检索 JSON 里没有的段落）。 */
    suspend fun homepage(member: FacultyMember): HomepageResult
}
