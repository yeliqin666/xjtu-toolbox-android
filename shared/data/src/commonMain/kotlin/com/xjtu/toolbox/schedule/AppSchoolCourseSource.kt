package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.SiteSession

/** :core 的 [SchoolCourseSource] 的实现 —— 包住 [SchoolCourseApi]。 */
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
