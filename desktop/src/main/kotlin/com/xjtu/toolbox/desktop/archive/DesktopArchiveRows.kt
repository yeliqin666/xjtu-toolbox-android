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
 * 三族行数组在桌面的落点：各是一个键值店。自建日程店名不带账号后缀；下载队列和教材书架带，
 * 归属不明的旧名文件保留，但不作为当前账号导入或导出的数据。
 */
internal object DesktopArchiveRows {

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

    fun rowsOf(
        family: ArchiveRowFamilies.Family,
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): String? = storeOf(storeName(family, accountId)).getString(family.key)

    /** 导出：有行的族 → `files/` 条目。 */
    fun filesOf(
        storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
        accountId: String? = AccountContext.activeAccountId,
    ): List<ArchiveFile> = ArchiveRowFamilies.Family.entries.mapNotNull { family ->
        val content = ArchiveRowFamilies.rowsForArchive(family, rowsOf(family, storeOf, accountId))
            ?: return@mapNotNull null
        ArchiveFile(suffix = "", relPath = family.relPath, content = content)
    }

    /** 导入：合并后整段写回。不是这三族（或带账号后缀，这三族只在 `global/`）返回 null，交给调用方。 */
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
