package com.xjtu.toolbox.faculty

/**
 * :core 的 [FacultySource] 在 Android 侧的实现 —— 包住原来的 [FacultyApi]。
 *
 * 三个方法一一对应（检索 / 筛选项 / 个人主页），**实现一行未改**：
 * okhttp + jsoup + 13 套主页模板的解析全留在 :app，这里只做接口适配。
 */
class AppFacultySource(private val api: FacultyApi = FacultyApi()) : FacultySource {

    override suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage =
        api.search(query = query, page = page)

    override suspend fun filters(): FacultyFilters = api.loadFilters()

    override suspend fun homepage(member: FacultyMember): HomepageResult = api.fetchHomepage(member)
}
