package com.xjtu.toolbox.faculty

/**
 * 教师主页（faculty.xjtu.edu.cn / gr.xjtu.edu.cn）的数据模型。
 *
 * 数据来源分两层，字段不要混：
 * - [FacultyMember]：来自 advancesearch.jsp 的 JSON，**全校 4173 人都有**，是主数据。
 * - [FacultyProfile]：来自老师个人主页 HTML，只用于补充 JSON 里没有的正文段落。
 *
 * 全部无需登录。
 */

// ==================== 教师条目（JSON 主数据） ====================

/**
 * 一位教师。字段名对齐 advancesearch.jsp 的 JSON key，方便对照排查。
 *
 * 覆盖率实测（全量 4173 人，2026-08-17）：
 * picUrl/homepageUrl 100%、collegeName 98%、proRank 92%、discipline 62%、
 * graduatedUniversity 49%、email 26%、officeLocation 18%、profile 11%、
 * contact 10%、researchDirections 5%、job 4%。
 *
 * `academician` 全库为 0、`honor` 仅 25 条——这两个字段服务端基本没在维护，
 * 已刻意不收进本模型，别在 UI 上给它们留位置。
 */
data class FacultyMember(
    val teacherId: Long,
    val name: String,
    /** 英文名，仅 6% 有值 */
    val englishName: String = "",
    /** 姓名拼音，服务端大小写不统一（"Zhong Yuan" / "chen qian" 都有），展示前自行规范 */
    val pinyin: String = "",
    /** 个人主页地址。100% 有值，但约 1% 打不开或指向站外，见 [HomepageResult] */
    val homepageUrl: String = "",
    val collegeName: String = "",
    /** 职称，如 教授 / 副教授 / 助理教授。92% 有值 */
    val proRank: String = "",
    /** 职务，如 "XX 实验室副主任"。仅 4% 有值 */
    val job: String = "",
    val discipline: String = "",
    val degree: String = "",
    val education: String = "",
    val graduatedUniversity: String = "",
    val isDoctoralTutor: Boolean = false,
    val isMasterTutor: Boolean = false,
    /** 简介全文；请求时由 profilelen 参数控制截断长度 */
    val profile: String = "",
    val researchDirections: List<String> = emptyList(),
    /** 头像相对路径，用 [FacultyApi.absoluteUrl] 补全 */
    val picUrl: String = "",
    val email: String = "",
    /** 服务端把办公电话、手机都往这个字段塞，语义不固定 */
    val contact: String = "",
    val phone: String = "",
    val mobilePhone: String = "",
    val officeLocation: String = "",
    val address: String = "",
    val entryTime: String = "",
    val lastUpdate: String = "",
    val clickTimes: Long = 0,
) {
    /** 导师身份的展示文案，两者都不是时为空 */
    val tutorLabel: String
        get() = listOfNotNull(
            "博导".takeIf { isDoctoralTutor },
            "硕导".takeIf { isMasterTutor },
        ).joinToString(" · ")

    /** 主页 URL 是否是可解析的标准个人主页（排除站外链接与畸形值） */
    val hasStandardHomepage: Boolean
        get() = homepageUrl.startsWith("https://gr.xjtu.edu.cn/") &&
            homepageUrl.endsWith("/zh_CN/index.htm")

    /** 主页路径里的教师标识（如 `caoyx`），用于拼接栏目 URL；非标准主页返回 null */
    val siteId: String?
        get() = if (!hasStandardHomepage) null else homepageUrl
            .removePrefix("https://gr.xjtu.edu.cn/")
            .removeSuffix("/zh_CN/index.htm")
            .takeIf { it.isNotBlank() && "/" !in it }
}

// ==================== 分页结果 ====================

/**
 * 一页查询结果。
 *
 * ⚠️ [total] 不能当作「精确匹配数」用：`teacherName` 是模糊匹配，
 * 实测 `刘`→255、`刘进军`→234，加字反而变少，服务端匹配规则不可推断。
 * 精确匹配的老师会排在前面，需要精确结果请在客户端再筛一次。
 */
data class FacultySearchPage(
    val total: Int,
    val totalPage: Int,
    val pageIndex: Int,
    val members: List<FacultyMember>,
)

// ==================== 筛选项（运行时动态拉取） ====================

/**
 * 一个筛选项。[depth] 来自服务端用 `|--` 前缀编码的层级：
 * 0=顶级分组，越大越深。用于在 UI 上做缩进，不参与查询。
 */
data class FacultyOption(
    val id: Int,
    val name: String,
    val depth: Int,
) {
    val isUnlimited: Boolean get() = id == 0
}

/**
 * 四张筛选表，全部在运行时从 search.jsp 解析，**不在代码里写死任何 id**。
 *
 * 实测规模：学院 137、学科 2293、招生学科 75、荣誉 15。
 *
 * 缺 [FacultySearchQuery.proRank] 对应的表是有意的：学校把职称下拉框放在 JS 里
 * 动态生成，页面 HTML 中没有 id 表可解析。因此职称改为客户端按
 * [FacultyMember.proRank] 字符串过滤，取值范围由 [proRanksFrom] 从当前结果集推导——
 * 同样不写死。
 */
data class FacultyFilters(
    val colleges: List<FacultyOption> = emptyList(),
    val disciplines: List<FacultyOption> = emptyList(),
    val enrollDisciplines: List<FacultyOption> = emptyList(),
    val honors: List<FacultyOption> = emptyList(),
) {
    val isEmpty: Boolean get() = colleges.isEmpty() && disciplines.isEmpty()

    companion object {
        /** 从一批教师里推导出实际出现过的职称，按人数降序。用于职称筛选的候选项。 */
        fun proRanksFrom(members: List<FacultyMember>): List<String> =
            members.asSequence()
                .map { it.proRank.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
    }
}

// ==================== 查询条件 ====================

/**
 * 查询条件。全部可选，留默认值即「不限」。
 *
 * [proRank] 不参与网络请求（服务端 rankid 表不可发现），
 * 由 [FacultyApi.search] 在拿到结果后本地过滤。
 */
data class FacultySearchQuery(
    val name: String = "",
    val collegeId: Int = 0,
    val disciplineId: Int = 0,
    val enrollDisciplineId: Int = 0,
    val honorId: Int = 0,
    /** 研究方向关键词 */
    val researchDirection: String = "",
    /** 姓名拼音首字母 */
    val pinyin: String = "",
    /** 仅博导 / 仅硕导；null = 不限 */
    val tutorOnly: TutorFilter? = null,
    /** 客户端过滤，见类注释 */
    val proRank: String = "",
) {
    enum class TutorFilter { DOCTORAL, MASTER }
}

// ==================== 个人主页解析结果 ====================

/**
 * 主页抓取结果。
 *
 * 约 1% 的老师主页不可用，必须显式降级而不是抛异常——实测样本里出现过：
 * 563 字节的 `<title>error</title>` 占位页、0 字节响应、
 * `url` 指向学院自建师资页甚至 WebVPN 链接。
 */
sealed class HomepageResult {
    data class Success(val profile: FacultyProfile) : HomepageResult()

    /** 主页地址不是标准个人主页（站外 / 畸形），只能外链跳转 */
    data class NotStandard(val url: String) : HomepageResult()

    /** 主页返回了占位错误页或空响应，老师尚未启用主页 */
    data object Unavailable : HomepageResult()

    data class Error(val message: String) : HomepageResult()
}

/**
 * 从个人主页解析出的补充信息。
 *
 * 全校 13 套模板（cn01–cn10、yyz、zwmblan、zwmbhong）的 DOM 结构互不相同，
 * 但**字段标签完全一致**，所以这里的字段全部由标签驱动提取，不依赖任何 CSS 选择器。
 * 学校再加第 14 套模板也不影响，只要标签不变。
 *
 * 注意：主页 HTML 里的电子邮箱是加密十六进制串（需调 tsitesencrypt.jsp 解密），
 * 本解析器一律丢弃 —— 用 [FacultyMember.email] 的明文即可。
 */
data class FacultyProfile(
    /** 页面模板标识，如 `cn01` / `yyz`。仅用于排查解析问题，不要用于业务判断 */
    val template: String = "",
    /** 从 `<title>` 解析出的姓名，可用于校验抓到的是不是同一个人 */
    val titleName: String = "",
    /** 系统字段块，key 为标签原文（性别 / 职称 / 学历 / …），已剔除加密值 */
    val fields: Map<String, String> = emptyMap(),
    /** 主页栏目导航，URL 语法跨全部模板一致 */
    val columns: List<FacultyColumn> = emptyList(),
) {
    fun field(label: String): String = fields[label].orEmpty()
}

/**
 * 主页的一个栏目。
 *
 * URL 语法在全部 13 套模板下一致：
 * `/{siteId}/zh_CN/{type}/{columnId}/list/index.htm`
 *
 * [type] 是固定语义词表（见 [FacultyColumnType]），[title] 由老师自定义、可能为空。
 */
data class FacultyColumn(
    val type: String,
    val columnId: Long,
    val url: String,
    val title: String = "",
    /**
     * 导航层级，由 `<ul>` 嵌套深度归一化得到：0 = 一级栏目，1 = 其下的二级页面。
     *
     * 必须保留层级，否则拍平后会出现「重复条目」——`zhym` 一级栏目和它同名的
     * `zdylm` 子页是两条不同的记录（王建学的「基本信息」就同时是父和子）。
     */
    val depth: Int = 0,
    /** 所属一级栏目的 columnId；自身即一级时为 null */
    val parentId: Long? = null,
) {
    /** 已知类型的中文名；未知类型返回 null，UI 应回退到 [title] */
    val typeName: String? get() = FacultyColumnType.NAMES[type]

    /**
     * UI 上该显示的名字。
     *
     * [FacultyColumnType.USER_NAMED] 里的类型是「容器」，名字由老师自己起
     * （同为 zhym，有人叫「科学研究」有人叫「主页 Home」），必须优先用 [title]；
     * 其余类型语义固定，用 [typeName] 更整齐。
     */
    val displayName: String
        get() = if (type in FacultyColumnType.USER_NAMED) {
            title.ifBlank { typeName ?: type }
        } else {
            typeName ?: title.ifBlank { type }
        }
}

/**
 * 栏目类型词表。
 *
 * 这里写死是安全的：它是服务端 URL 里的**语义常量**，不是 id。
 * 未收录的 type 会原样透出（[FacultyColumn.typeName] 返回 null），不会丢数据。
 */
object FacultyColumnType {
    const val NEWS = "article"
    const val RESEARCH_PROJECT = "kyxm"
    const val PAPER = "lwcg"
    const val PATENT = "zlcg"
    const val BOOK = "zzcg"
    const val AWARD = "hjxx"
    const val ENROLLMENT = "zsxx"
    const val RESEARCH_FIELD = "yjgk"
    const val TEACHING_ACHIEVEMENT = "jxcg"
    const val TEACHING_RESOURCE = "jxzy"
    const val COURSE = "skxx"
    const val STUDENT = "xsxx"
    const val ALBUM = "img"
    const val HOME = "index"
    const val GENERAL = "zhym"
    const val CUSTOM = "zdylm"

    /**
     * 名称取自学校自己的导航文案，不是猜的。
     * （`article` 是「我的新闻」而非「文章」，`yjgk` 是「研究领域」而非「研究概况」。）
     */
    val NAMES: Map<String, String> = mapOf(
        NEWS to "我的新闻",
        RESEARCH_PROJECT to "科研项目",
        PAPER to "论文成果",
        PATENT to "专利成果",
        BOOK to "著作成果",
        AWARD to "获奖信息",
        ENROLLMENT to "招生信息",
        RESEARCH_FIELD to "研究领域",
        TEACHING_ACHIEVEMENT to "教学成果",
        TEACHING_RESOURCE to "教学资源",
        COURSE to "授课信息",
        STUDENT to "学生信息",
        ALBUM to "我的相册",
        HOME to "首页",
        GENERAL to "综合页面",
        CUSTOM to "自定义栏目",
    )

    /** 名字由老师自定义的容器类栏目，展示时应优先用链接文本，见 [FacultyColumn.displayName] */
    val USER_NAMED: Set<String> = setOf(GENERAL, CUSTOM)
}

/**
 * 一个一级栏目及其子页面，供 UI 分组渲染。
 */
data class FacultyColumnGroup(
    val section: FacultyColumn,
    val children: List<FacultyColumn>,
)

/**
 * 把扁平的栏目列表还原成两级分组。
 *
 * 同时去掉一种视觉重复：老师建一级栏目时系统常自动生成一个同名子页
 * （「基本信息 > 基本信息」「人才培养 > 人才培养」），
 * 两者指向同一个落地页，列表里出现两遍纯属噪音，这里只保留一级。
 */
fun List<FacultyColumn>.groupBySection(): List<FacultyColumnGroup> {
    val sections = filter { it.depth == 0 }
    val childrenOf = filter { it.depth > 0 }.groupBy { it.parentId }
    val grouped = sections.map { section ->
        val kids = childrenOf[section.columnId].orEmpty()
            .filterNot { it.displayName == section.displayName }
        FacultyColumnGroup(section, kids)
    }
    // 找不到父节点的孤儿（模板异常时可能出现）单独兜底，避免整条数据丢失
    val claimed = grouped.flatMap { it.children }.mapTo(mutableSetOf()) { it.columnId }
    val orphans = filter { it.depth > 0 && it.columnId !in claimed && it.parentId == null }
    return grouped + orphans.map { FacultyColumnGroup(it, emptyList()) }
}
