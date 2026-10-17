package com.xjtu.toolbox.faculty

/** 把 [FacultyApi] 包成 `:core` 的 [FacultySource] —— 三个方法一一对应（检索 / 筛选项 / 个人主页）。 */
class FacultyApiSource(private val api: FacultyApi = FacultyApi()) : FacultySource {

    override suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage =
        api.search(query = query, page = page)

    override suspend fun filters(): FacultyFilters = api.loadFilters()

    override suspend fun homepage(member: FacultyMember): HomepageResult = api.fetchHomepage(member)
}
