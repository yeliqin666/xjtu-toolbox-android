package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.SiteSession

/**
 * :core 的 [SchoolCourseSource] 的实现 —— 包住 [SchoolCourseApi]。
 *
 * 与 [SchoolCourseApi] 一起从 `:app` 搬进 `:data`（同一个包名、同一个类名，`:app` 的
 * `AppNavHost` 与 `ScheduleApi` 一行未改）。名字里的 `App` 是历史：它现在两端共用。
 *
 * 上游解析一行未改（`querySetting` 数组、`qxfbkccx.do` 的 POST、`/jwapp/code/` 下的院系表），
 * 这里只做「端口方法 → 原方法」的适配，包括「空串 / 0 表示不限 → null」那一步
 * （`SchoolCourseApiJvmTest` 对着请求原文有断言）。
 */
class AppSchoolCourseSource(private val api: SchoolCourseApi) : SchoolCourseSource {

    constructor(site: SiteSession) : this(SchoolCourseApi(site))

    override suspend fun terms(): List<TermOption> = api.getTermList()

    override suspend fun currentTerm(): String = api.getCurrentTerm()

    override suspend fun departments(): List<DepartmentOption> = api.getDepartments()

    override suspend fun query(query: SchoolCourseQuery, page: Int, pageSize: Int): SchoolCourseResult =
        api.queryCourses(
            termCode = query.termCode,
            courseName = query.courseName.ifBlank { null },
            courseCode = query.courseCode.ifBlank { null },
            teacher = query.teacher.ifBlank { null },
            departmentCode = query.departmentCode.ifBlank { null },
            className = query.className.ifBlank { null },
            campusCode = query.campusCode.ifBlank { null },
            isPublicElective = query.isPublicElective,
            electiveCategoryCode = query.electiveCategoryCode.ifBlank { null },
            weekday = query.weekday.takeIf { it > 0 },
            startSection = query.startSection.takeIf { it > 0 },
            endSection = query.endSection.takeIf { it > 0 },
            pageSize = pageSize,
            pageNumber = page,
        )
}
