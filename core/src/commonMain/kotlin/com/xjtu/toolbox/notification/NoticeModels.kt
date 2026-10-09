package com.xjtu.toolbox.notification

import kotlinx.datetime.LocalDate
import com.xjtu.toolbox.util.todayInSystemZone

/**
 * 通知/公告的**数据层**（模型 + 来源枚举 + 取数端口）。
 *
 * 从 :app 的 `NotificationApi.kt` 里拆出来的一半：那 1137 行里，
 * 「29 个来源是谁」（本文件）与「怎么把某个站的 HTML 抠成条目」（:app 的爬虫类，用 jsoup）
 * 是两件事。前者两端要共用（屏按它分组、筛来源），后者只有 Android 需要
 * —— Web 端的解析在 campus-api 里（它覆盖同样 29 个源）。
 *
 * 唯一的类型替换：`date` 从 `java.time.LocalDate` 换成 `kotlinx.datetime.LocalDate`
 * （commonMain 没有 java.time）。:app 侧在三个构造点用已有的 `java.time.LocalDate.toKx()` 转换。
 */

// ==================== 数据类 ====================

data class Notification(
    val title: String,
    val link: String,
    val source: NotificationSource,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val date: LocalDate = todayInSystemZone(),
)

/** 某一页的抓取结果。[hasMore] 表示站点分页里还有下一页，不是「这一页是不是空的」。 */
data class NotificationPage(
    val items: List<Notification>,
    val hasMore: Boolean,
)

data class MergedNotificationPage(
    val items: List<Notification>,
    val skipped: Set<NotificationSource>,
    val hasMore: Boolean,
)

// ==================== 来源分类 ====================

enum class SourceCategory(val displayName: String) {
    GENERAL("综合"),
    ENGINEERING("工学"),
    SCIENCE("理学"),
    HUMANITIES("人文经管");
}

// ==================== 通知来源 ====================

enum class NotificationSource(
    val displayName: String,
    val baseUrl: String,
    val category: SourceCategory
) {
    // ── 综合（校级部门） ──
    JWC("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm", SourceCategory.GENERAL),
    GS("研究生院", "https://gs.xjtu.edu.cn/tzgg.htm", SourceCategory.GENERAL),
    QXS("钱学森书院", "https://bjb.xjtu.edu.cn/xydt/tzgg.htm", SourceCategory.GENERAL),
    CY("仲英书院", "https://cy.xjtu.edu.cn/xwdt/tzgg.htm", SourceCategory.GENERAL),
    PEC("实践教学中心", "https://pec.xjtu.edu.cn/xxgg/tzgg.htm", SourceCategory.GENERAL),
    FTI("未来技术学院", "https://wljsxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.GENERAL),
    XSC("学生处", "https://xsc.xjtu.edu.cn/xgdt/tzgg.htm", SourceCategory.GENERAL),
    OA("OA 通知", "https://oa.xjtu.edu.cn/zxgg_index.jsp", SourceCategory.GENERAL),

    // ── 工学 ──
    // 电信学部「更多」指向的 tzgg.htm 是停更栏目；首页通知条才是仍在更新的 1005 栏。
    EIEUG("电信学部", "https://eieug.xjtu.edu.cn/", SourceCategory.ENGINEERING),
    ME("机械学院", "https://mec.xjtu.edu.cn/index/tzgg/bks.htm", SourceCategory.ENGINEERING),
    EE("电气学院", "https://ee.xjtu.edu.cn/jzxx/bks.htm", SourceCategory.ENGINEERING),
    EPE("能动学院", "https://epe.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    AERO("航天学院", "https://sae.xjtu.edu.cn/index/tzgg.htm", SourceCategory.ENGINEERING),
    MSE("材料学院", "https://mse.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),
    CLET("化工学院", "https://clet.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.ENGINEERING),
    HSCE("人居学院", "https://hsce.xjtu.edu.cn/xwgg/tzgg1.htm", SourceCategory.ENGINEERING),
    SE("软件学院", "https://se.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.ENGINEERING),

    // ── 理学 ──
    MATH("数学学院", "https://math.xjtu.edu.cn/index/jxjw1.htm", SourceCategory.SCIENCE),
    PHY("物理学院", "https://phy.xjtu.edu.cn/glfw/tzgg.htm", SourceCategory.SCIENCE),
    CHEM("化学学院", "https://chem.xjtu.edu.cn/tzgg.htm", SourceCategory.SCIENCE),
    SLST("生命学院", "https://slst.xjtu.edu.cn/ggl/tzgg.htm", SourceCategory.SCIENCE),

    // ── 人文经管 ──
    SOM("管理学院", "https://som.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES),
    RWXY("人文学院", "https://rwxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SFS("外国语学院", "https://sfs.xjtu.edu.cn/glfw/jxjw.htm", SourceCategory.HUMANITIES),
    LAW("法学院", "https://fxy.xjtu.edu.cn/index/tzgg.htm", SourceCategory.HUMANITIES),
    SEF("经金学院", "https://sef.xjtu.edu.cn/rcpy/bks/jxtz1.htm", SourceCategory.HUMANITIES),
    SPPA("公管学院", "https://sppa.xjtu.edu.cn/xwxx/bksjw.htm", SourceCategory.HUMANITIES),
    MARX("马克思主义学院", "https://marx.xjtu.edu.cn/xwgg1/tzgg.htm", SourceCategory.HUMANITIES),
    XMTXY("新媒体学院", "https://xmtxy.xjtu.edu.cn/xwgg/tzgg.htm", SourceCategory.HUMANITIES);

    companion object {
        fun byCategory(cat: SourceCategory): List<NotificationSource> =
            entries.filter { it.category == cat }

        /**
         * 学院 / 书院全称（学籍档案、校园卡、一网通办里的写法，如「电子与信息学部」「公共政策与管理学院」）
         * 对应到通知来源；对不上返回 null。
         *
         * 顺序有讲究：「公共政策与管理学院」含「管理学院」、「化学工程与技术学院」含「化学」，
         * 长的、特指的放前面先匹配。
         */
        fun forOrg(name: String?): NotificationSource? {
            val n = name?.trim().orEmpty()
            if (n.isEmpty()) return null
            return ORG_ALIASES.firstOrNull { (_, keys) -> keys.any { n.contains(it) } }?.first
        }

        private val ORG_ALIASES: List<Pair<NotificationSource, List<String>>> = listOf(
            QXS to listOf("钱学森"),
            CY to listOf("仲英"),
            FTI to listOf("未来技术"),
            EIEUG to listOf("电子与信息", "电信"),
            ME to listOf("机械"),
            EE to listOf("电气"),
            EPE to listOf("能源与动力", "能动"),
            AERO to listOf("航天"),
            MSE to listOf("材料"),
            CLET to listOf("化学工程", "化工"),
            HSCE to listOf("人居"),
            SE to listOf("软件"),
            MATH to listOf("数学"),
            PHY to listOf("物理"),
            CHEM to listOf("化学"),
            SLST to listOf("生命"),
            SPPA to listOf("公共政策", "公管"),
            SOM to listOf("管理学院"),
            RWXY to listOf("人文"),
            SFS to listOf("外国语"),
            LAW to listOf("法学"),
            SEF to listOf("经济与金融", "经金"),
            MARX to listOf("马克思"),
            XMTXY to listOf("新媒体"),
        )
    }
}

// ==================== 取数端口 ====================

/**
 * 通知的取数端口。屏只认这个接口，两端各自实现：
 * - `:app` = 原来的 `NotificationApi`（okhttp + jsoup 爬 29 个站，含反爬挑战）；
 * - `:web` = campus-api 的 `/api/notification/list`（它已经把这 29 个站爬好并归一了）。
 *
 * 三个方法都是 suspend：`:app` 的实现内部自己 `withContext(Dispatchers.IO)`。
 */
interface NoticeSource {
    /** 单个来源的一页。 */
    suspend fun page(source: NotificationSource, page: Int): NotificationPage

    /** 多来源合并；[MergedNotificationPage.skipped] 是这次没拉到的源（域名级失败）。 */
    suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage

    /**
     * 站内检索。**允许「做不到」**：返回 `MergedNotificationPage(emptyList(), emptySet(), false)`
     * 时界面只用已加载列表里的匹配顶着（campus-api 没有检索端点，Web 就是这么降级的）。
     */
    suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage
}
