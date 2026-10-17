package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveRowFamilies
import com.xjtu.toolbox.archive.MergeOutcome
import com.xjtu.toolbox.desktop.DesktopScheduleHost
import com.xjtu.toolbox.desktop.accountScopedStoreName
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore

/**
 * 三族行数组在桌面这一端的**落点映射**（§2.3 / §6.3 的 `custom_courses` KV 那一条）。
 *
 * 桌面没有 Room：那三族是三个键值店，且**端内名各不相同**（S7b 之后其中两族带账号后缀）——
 *
 * | 族（档案里的相对路径） | 端内名 | 键 | 谁在用 |
 * |---|---|---|---|
 * | `courses/custom_courses.json` | `custom_courses`（无后缀） | `custom_courses` | `DesktopScheduleHost.customCourses`（`KeyValueCustomCourseStore`） |
 * | `media/download_tasks.json` | `download_tasks<账号后缀>` | `rows` | `DesktopDownloadTaskStore` |
 * | `jiaocai/jiaocai1_shelf.json` | `jiaocai1_shelf<账号后缀>` | `rows` | `DesktopJiaocai1ShelfStore` |
 *
 * 三处端内名逐字取自那三个 Store 自己的常量（`storeName()` / `DesktopScheduleHost.CUSTOM_COURSE_STORE`），
 * 不在这里另起一份字面量 —— 改名只会改一处。
 *
 * ## 「旧名只读」也照抄一遍
 *
 * 下载队列与教材书架在 S7b 之前用的是**无后缀**的端内名，两个 Store 的构造器都留了「新名里没有行时
 * 读一次旧名」的兜底（§6.1 第 2 条：只读，不往回写）。导出与导入的**合并基**走同一条兜底 ——
 * 导出出去的和「业务读法读到的」才是同一批行；不兜的话，一次导入会把旧名里那些行变成「新名里只剩
 * 追加的那几行」。`custom_courses` 一直没有后缀，所以它没有旧名。
 *
 * ## 行的合并规则在 `:core`
 *
 * 「本端已有主键的行一律保留、只追加端内没有的主键」这一条**只**有 [ArchiveRowFamilies.merge] 一份，
 * 这里只做键值店的读 / 写（`getString` / `putString`）与端内名解析。
 */
internal object DesktopArchiveRows {

    /** 族 → 端内名（`custom_courses` 无后缀，另两族带当前账号后缀）。 */
    fun storeName(
        family: ArchiveRowFamilies.Family,
        accountId: String? = AccountContext.activeAccountId,
    ): String = when (family) {
        ArchiveRowFamilies.Family.CUSTOM_COURSES -> DesktopScheduleHost.CUSTOM_COURSE_STORE
        ArchiveRowFamilies.Family.DOWNLOAD_TASKS ->
            accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, accountId)
        ArchiveRowFamilies.Family.JIAOCAI_SHELF ->
            accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, accountId)
    }

    /** S7b 之前那份无后缀的端内名（只读兜底）；`custom_courses` 从来没有后缀 ⇒ null。 */
    fun legacyStoreName(family: ArchiveRowFamilies.Family): String? = when (family) {
        ArchiveRowFamilies.Family.CUSTOM_COURSES -> null
        ArchiveRowFamilies.Family.DOWNLOAD_TASKS -> DesktopDownloadTaskStore.STORE_NAME
        ArchiveRowFamilies.Family.JIAOCAI_SHELF -> DesktopJiaocai1ShelfStore.STORE_NAME
    }

    /** 端内这一族现在的行数组原文（新名 → 旧名只读兜底）；端上没有这一族 ⇒ null。 */
    fun rowsOf(
        family: ArchiveRowFamilies.Family,
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): String? {
        storeOf(storeName(family, accountId)).getString(family.key)?.let { return it }
        val legacy = legacyStoreName(family) ?: return null
        return storeOf(legacy).getString(family.key)
    }

    /** 导出侧：三族里**有行**的那几族 → `files/` 条目（§2.3 的排序由 `:core` 做）。 */
    fun filesOf(
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): List<ArchiveFile> = ArchiveRowFamilies.Family.entries.mapNotNull { family ->
        val content = ArchiveRowFamilies.rowsForArchive(family, rowsOf(family, storeOf, accountId)) ?: return@mapNotNull null
        ArchiveFile(suffix = "", relPath = family.relPath, content = content)
    }

    /**
     * 导入侧：档案里那一族合并进端内键值店（§6.4 的「本端胜」在行这一级）。
     *
     * 不是这三族 / `suffix` 非空（这三族在档案里恒为 `global/`，Android 与桌面都只写那一条路径）
     * ⇒ null = 「这一笔不归这里管」，交由调用方按 `agent_sessions` 那一族处理。
     */
    fun land(
        suffix: String,
        relPath: String,
        content: String,
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): MergeOutcome? {
        if (suffix.isNotEmpty()) return null
        val family = ArchiveRowFamilies.of(relPath) ?: return null
        val store = storeOf(storeName(family, accountId))
        val outcome = ArchiveRowFamilies.merge(family, rowsOf(family, storeOf, accountId), content)
        store.putString(family.key, outcome.content)
        return outcome
    }
}
