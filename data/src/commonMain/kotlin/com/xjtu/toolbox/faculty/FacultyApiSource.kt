package com.xjtu.toolbox.faculty

/**
 * 把 [FacultyApi] 包成 `:core` 的 [FacultySource] —— 三个方法一一对应（检索 / 筛选项 / 个人主页）。
 *
 * ## 为什么它在 `:data`（Stage A 收尾）
 *
 * 它原来是 `:app` 的 `AppFacultySource`。`FacultyApi` 搬进 `:data` 之后，这个 3 行转发的适配器
 * 没有理由留在 Android 侧：**桌面端也要用它**（`:app` 与 `:desktop` 各写一份就是两份同语义代码）。
 * 于是它跟着取数一起搬，名字从 `AppFacultySource` 改成 `FacultyApiSource`（旧名字在共享层会撒谎），
 * `:app` 侧只有 `AppNavHost` 一个调用点改名 —— 这正是「搬走声明 + 引用改名」那条口径允许的。
 *
 * `:app` 的 `FacultyApiTest` / `FacultySectionTest` 也一并跟着代码搬进了 `:data:jvmTest`。
 */
class FacultyApiSource(private val api: FacultyApi = FacultyApi()) : FacultySource {

    override suspend fun search(query: FacultySearchQuery, page: Int): FacultySearchPage =
        api.search(query = query, page = page)

    override suspend fun filters(): FacultyFilters = api.loadFilters()

    override suspend fun homepage(member: FacultyMember): HomepageResult = api.fetchHomepage(member)
}
