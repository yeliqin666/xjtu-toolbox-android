package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveRowFamilies
import com.xjtu.toolbox.archive.MergeOutcome
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore

/**
 * 三族行数组在 web 这一端的**落点映射**（与桌面的 `DesktopArchiveRows` 对称；§2.3 / §6.3）。
 *
 * ## 本端只有一族有落点，另两族如实不接
 *
 * | 族（档案里的相对路径） | 端内名 | 键 | 谁在用 |
 * |---|---|---|---|
 * | `courses/custom_courses.json` | `custom_courses` | `custom_courses` | `WebScheduleHost.customCourses`（`KeyValueCustomCourseStore`） |
 * | `media/download_tasks.json` | —— **本端没有这一族的业务** | —— | 无 |
 * | `jiaocai/jiaocai1_shelf.json` | —— **本端没有这一族的业务** | —— | 无 |
 *
 * 桌面那两族是 `DesktopDownloadTaskStore` 与 `DesktopJiaocai1ShelfStore` 两个键值店；web 上**没有**
 * 对应的业务 —— 浏览器这端没有下载队列（录课下载是桌面 / 手机的事），教材只做到「取到书单看一眼」
 * （`WebJiaocaiHost` 是个取数端口，端上没有「我的书架」这张表）。所以那两族在档案里确实可能带着行
 * （Android / 桌面导出来的），但本端**没有可写的地方** ⇒ 导入时如实不落地、由状态文本报「几条未落地」，
 * 而不是塞进一个没人读的键里假装导过了（§8.7 的禁止项同一条精神：不写没人读的东西）。
 *
 * ## 行的搬运与合并只有 `:core` 一份
 *
 * 端内那段 JSON 本身就是行数组 ⇒ 「拆」= 按 §2.3 重排 + 原样搬运（[ArchiveRowFamilies.rowsForArchive]）；
 * 「并」= 本端已有的主键整行保留、只追加新主键（[ArchiveRowFamilies.merge]）。这里只做 `localStorage`
 * 键值店的读 / 写（`getString` / `putString`）与端内名解析，不另写一份规则。
 */
internal object WebArchiveRows {

    /**
     * 本端**有落点**的族 → 端内店名。店名逐字取自那一族的业务常量（`WebScheduleHost` 的
     * `CUSTOM_COURSE_STORE`），不在这里另起一份字面量；键名走 [ArchiveRowFamilies.Family.key]。
     */
    private val LANDED_STORES: Map<ArchiveRowFamilies.Family, String> = mapOf(
        ArchiveRowFamilies.Family.CUSTOM_COURSES to WebScheduleHost.CUSTOM_COURSE_STORE,
    )

    /** 本端有落点的族（另两族见类 KDoc：本端没有对应的业务）。 */
    val landed: List<ArchiveRowFamilies.Family> = LANDED_STORES.keys.toList()

    /** 相对路径 → 本端有落点的族；不是这三条之一、或这一端没有落点 ⇒ null。 */
    fun of(relPath: String): ArchiveRowFamilies.Family? =
        ArchiveRowFamilies.of(relPath)?.takeIf { family -> family in LANDED_STORES }

    /** 端内这一族现在的行数组原文；端上没有这一段 ⇒ null。 */
    fun rowsOf(
        family: ArchiveRowFamilies.Family,
        storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
    ): String? = storeOf(LANDED_STORES.getValue(family)).getString(family.key)

    /** 导出侧：本端**有行**的那几族 → `files/` 条目（§2.3 的排序由 `:core` 做，不在这里另写一份）。 */
    fun filesOf(
        storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
    ): List<ArchiveFile> = landed.mapNotNull { family ->
        val content = ArchiveRowFamilies.rowsForArchive(family, rowsOf(family, storeOf)) ?: return@mapNotNull null
        ArchiveFile(suffix = "", relPath = family.relPath, content = content)
    }

    /**
     * 导入侧：档案里那一族行数组合并进端内键值店（§6.4 的「本端胜」在行这一级）。
     *
     * 不是本端有落点的族、或 `suffix` 非空（这三族在档案里恒在 `global/`：Android 与桌面都只写那一条
     * 路径）⇒ null = 「这一笔不归这里管」，导入侧把它当「本端没有落点」如实不写。
     */
    fun land(
        suffix: String,
        relPath: String,
        content: String,
        storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
    ): MergeOutcome? {
        if (suffix.isNotEmpty()) return null
        val family = of(relPath) ?: return null
        val outcome = ArchiveRowFamilies.merge(family, rowsOf(family, storeOf), content)
        storeOf(LANDED_STORES.getValue(family)).putString(family.key, outcome.content)
        return outcome
    }
}
