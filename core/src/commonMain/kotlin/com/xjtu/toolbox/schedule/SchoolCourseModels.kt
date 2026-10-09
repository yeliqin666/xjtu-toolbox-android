package com.xjtu.toolbox.schedule

/**
 * 全校课表（开课任务级）的**模型 + 取数端口**。
 *
 * 从 :app 的 `SchoolCourseApi.kt` 里切出来的：上游解析（`querySetting` 那个 JSON 数组、
 * okhttp POST）留在 :app，模型与端口两端共用。
 *
 * ## 哪些字段是「可选」的，为什么
 *
 * :app 直连 `jwxt.xjtu.edu.cn` 的 `qxfbkccx.do`，行里 97 个键什么都有；
 * Web 端只能走 campus-api 的 `/api/jwxt/school-courses`，而它对**人数类字段刻意不投影**
 * （`KRL` 课容量 / `XKZRS` 选课人数 / `SKXS` 授课学时：语义未经证实，按「不猜」原则不对外，
 * 见 campus-api 手册 §全校课表的两条口径）。
 *
 * 所以这几个字段在模型里是**可空**的：`null` = 这一端不知道，屏上不画那一块，
 * 而不是拿 0 冒充（0/0 的容量条、`总学时 0` 都是在骗人）。`:app` 端上游一直有值 ⇒ 行为逐字不变。
 *
 * 另外 campus-api 给的是 `weekday`/`fromSection`/`toSection`/`weekText`/`roomCode`（上游原始列），
 * 而 :app 用的是上游预拼好的 `YPSJDD`（[scheduleLocation]）——两者形状不同，**不互相伪造**：
 * Web 端的 [scheduleLocation] 是空串，屏上那一行自然不出现。
 */

/** 学期选项 */
data class TermOption(
    val code: String,   // e.g. "2025-2026-2"
    val name: String    // e.g. "2025-2026学年 第二学期"
)

/** 开课单位（院系）选项 */
data class DepartmentOption(
    val code: String,   // e.g. "13028000"
    val name: String    // e.g. "物理学院"
)

/** 校区选项 */
data class CampusOption(
    val code: String,   // e.g. "1"
    val name: String    // e.g. "兴庆校区"
)

/** 校公选课类别选项 */
data class ElectiveCategoryOption(
    val code: String,   // e.g. "06"
    val name: String    // e.g. "基础通识类选修课"
)

/** 两端共用的校区表（数据稳定，:app 原本就硬编码在这一层）。 */
val SCHOOL_COURSE_CAMPUSES: List<CampusOption> = listOf(
    CampusOption("1", "兴庆校区"),
    CampusOption("2", "雁塔校区"),
    CampusOption("3", "曲江校区"),
    CampusOption("4", "苏州校区"),
    CampusOption("5", "创新港校区"),
)

/** 两端共用的校公选课类别表（同上）。 */
val SCHOOL_COURSE_ELECTIVE_CATEGORIES: List<ElectiveCategoryOption> = listOf(
    ElectiveCategoryOption("06", "基础通识类选修课"),
    ElectiveCategoryOption("07", "基础通识类核心课"),
    ElectiveCategoryOption("08", "钱学森学院特色课"),
)

/** 全校课程查询结果 */
data class SchoolCourse(
    val courseCode: String,          // KCH - 课程号
    val courseName: String,          // KCM - 课程名
    val sectionNumber: String,       // KXH - 课序号
    val teacher: String,             // SKJS - 上课教师
    val department: String,          // KKDWDM_DISPLAY - 开课单位
    val credit: Double,              // XF - 学分
    /** XS - 总学时。campus-api 不给 ⇒ Web 端为 null */
    val totalHours: Double? = null,
    /** SKXS - 授课学时。同上 */
    val lectureHours: Double? = null,
    /** SYXS - 实验学时。同上 */
    val labHours: Double? = null,
    /** SJXS - 实践学时。同上 */
    val practiceHours: Double? = null,
    /** XKZRS - 选课人数。campus-api 不给（人数类字段刻意不投影） */
    val enrollCount: Int? = null,
    /** KRL - 课容量。同上 */
    val capacity: Int? = null,
    val className: String = "",      // SKBJ - 上课班级
    /** YPSJDD - 已排时间地点（上游预拼好的一串）。campus-api 给的是原始列，两端形状不同 ⇒ Web 端为空 */
    val scheduleLocation: String = "",
    val campus: String = "",         // XXXQDM_DISPLAY - 校区
    val isPublicElective: Boolean = false, // SFXGXK - 是否校公选课
    /** XGXKLBDM_DISPLAY - 校公选课类别。campus-api 给的是 `courseKind`（同一个语义的另一列） */
    val electiveCategory: String = "",
    /** KNZXS - 周学时。campus-api 不给 */
    val weeklyHours: Double? = null,
    /** NSXKRS - 男生选课人数。campus-api 不给 */
    val maleEnrollCount: Int? = null,
    /** NVSXKRS - 女生选课人数。campus-api 不给 */
    val femaleEnrollCount: Int? = null,
    val teachingClassId: String = "", // JXBID - 教学班ID
    val termCode: String = ""        // XNXQDM - 学年学期
) {
    /** 剩余容量；两端有一端不知道容量就是 null（屏据此不画容量条）。 */
    val remaining: Int? get() = if (capacity != null && enrollCount != null) capacity - enrollCount else null

    /** 容量比例 (0.0 ~ 1.0)；不知道容量时为 null。 */
    val fillRatio: Float?
        get() {
            val cap = capacity ?: return null
            val enrolled = enrollCount ?: return null
            return if (cap > 0) (enrolled.toFloat() / cap).coerceIn(0f, 1f) else 0f
        }
}

/** 查询分页结果 */
data class SchoolCourseResult(
    val totalSize: Int,
    val pageNumber: Int,
    val pageSize: Int,
    val courses: List<SchoolCourse>
) {
    val totalPages: Int get() = if (pageSize > 0) (totalSize + pageSize - 1) / pageSize else 0
}

/** 全校课程的查询条件；空串 / 0 / null 表示不限。 */
data class SchoolCourseQuery(
    val termCode: String,
    val courseName: String = "",
    val courseCode: String = "",
    val teacher: String = "",
    val departmentCode: String = "",
    val className: String = "",
    val campusCode: String = "",
    val isPublicElective: Boolean? = null,
    val electiveCategoryCode: String = "",
    val weekday: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
)

/**
 * 全校课表的取数端口。
 *
 * - `:app` = 原来的 `SchoolCourseApi`（okhttp POST `qxfbkccx.do`，自己拼 `querySetting`）；
 * - `:web` = campus-api 的 `/api/jwxt/school-courses`。
 *
 * [departments] 与 [query] 的**筛选项能力不同**（campus-api 不传开课单位、公选类别：
 * 前者要那张 id 表、后者是 `m_value_equal` 条件），见各自实现的 KDoc。
 */
interface SchoolCourseSource {
    /**
     * 能不能按**开课单位**筛。
     *
     * false 时屏上**不出现**那一档下拉 —— 比「出现了却筛不动」诚实。
     * campus-api 不提供那张 id 表 ⇒ Web 端 false；:app 默认 true。
     */
    val supportsDepartmentFilter: Boolean get() = true

    /**
     * 能不能按**是否公选课 / 公选类别**筛。
     *
     * 同上：campus-api 的 `buildQuerySetting` 不放行这两个条件 ⇒ Web 端 false。
     */
    val supportsElectiveFilter: Boolean get() = true

    /** 学期列表（按学期倒序）。 */
    suspend fun terms(): List<TermOption>

    /** 当前学期号；拿不到返回空串。 */
    suspend fun currentTerm(): String

    /** 开课单位选项。**允许「拿不到」**：返回空列表时那一档下拉是空的（Web 端就是这样）。 */
    suspend fun departments(): List<DepartmentOption>

    /** 查询一页。 */
    suspend fun query(query: SchoolCourseQuery, page: Int, pageSize: Int): SchoolCourseResult
}
