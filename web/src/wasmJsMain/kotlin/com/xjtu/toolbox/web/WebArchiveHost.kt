package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveStoreBackend
import kotlin.time.Clock

/**
 * Web 这一端的**档案宿主装配**（S6；`docs/storage-layout-plan.md` 的 S6 行）。
 *
 * 与桌面（S5）同一分工：它只**读**端内已有的东西（`localStorage` 的 store 投影 + 三族行数组里有落点的那一族），
 * 把端内数据交给 `:core` 的纯函数 [StoreArchive.export] 组装成档案，再用 [WebArchiveIo] 那两条浏览器通道
 * 下载 / 上传。这一端没有文件系统（`archiveFileBackend()` 在 wasm 上返回 `null`），也没有 Room 表——
 * `files/` 那一层里只有行数组一族有落点，见 [WebArchiveRows]。
 *
 * ## 只写 `global/` 且 `manifest.noAccount = true`（规格 §9 未决 #4）
 *
 * D9 之前这一端**没有账号**（凭据在 campus-api / serve 进程里，见 `WebScheduleHost` 的 KDoc）
 * ⇒ 组装时 `accounts` 传空表 ⇒ 每条路径都落在 `global/`、`manifest.noAccount` 由 `:core` 置 true。
 * D9（S7a）之后 suffix 才谈得上「取 serve 当前的账号」，那时这里改成传账号表即可（`StoreArchive.export`
 * 的 `accounts` 参数就是那个开关）。
 *
 * ## 一次性导入的触发点：这一端**没有**「页面加载时扫一遍」那一步（如实记一笔）
 *
 * 规格 §6.2 给 web 写的是「页面加载时检查有没有待导入的档案；D9 之前 = 人工（上传），D9 之后 = serve 侧」。
 * 浏览器没有任何可扫的位置（没有文件系统，`localStorage` 里也没有"待导入档案"这种东西）⇒ D9 之前
 * 这一端的触发点**就是**用户在设置页点一次「导入数据存档」（浏览器本来也要求文件选择由用户手势发起）；
 * 「页面加载时检查」那一档要等 D9 之后由 serve 侧给（S7a），本工单不假装它存在。
 *
 * ## 合并导入：写侧真接上了（§6.4）
 *
 * §6.4 的逐 store 合并是 `:core` 的 [StoreArchive.import]（S1b）+ 写侧窄口 `ArchiveImportTarget`；
 * 本工单把这一端的写侧（[WebArchiveImportTarget]：逐键写回同一批 `localStorage` 键）接上，
 * 所以用户点一次「导入数据存档」现在是**真导**：自校验 → §6.4 合并 → 如实报结果。
 * 坏档案在写第一笔之前就明确失败、**本机零改动**（A7）。
 *
 * **为什么这一端没有「已导入」标记**：这一端的导入是用户**主动点一次**的动作（没有「页面加载时
 * 扫一遍」那一步），不需要认「这份档案导过没有」；而且 `localStorage` 里也没有一个「档案落地目录」
 * 可挂标记（§8.7 的禁止项里连 `data_cache*` 都不该写）。用户想再导一次，就再点一次。
 */
internal object WebArchiveHost {

    /** §2.1 `createdBy`：本端固定写 `web`。 */
    const val CREATED_BY = "web"

    /** §2.1 `appVersion`：与 `WebSettingsHost.versionText` 逐字同一个字面。 */
    const val APP_VERSION = "Web 构建（wasm）"

    /** 读端内 store 投影（S2 缝 A 的 wasm actual：`localStorage` 的 `xjtu-toolbox.` 前缀）。 */
    fun readStores(backend: ArchiveStoreBackend = archiveStoreBackend()): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /**
     * 纯组装：已投影的 stores（+ 本端有落点的行数组） → [StoreArchive.export] 的档案字节映射。
     * `accounts` 传空表 = 「这一端没有账号」⇒ 全部落在 `global/`、`noAccount=true`（§9 未决 #4）；
     * `files` 默认取 [WebArchiveRows.filesOf]（**只有自建日程那一族**：另两族本端没有落点，见那段 KDoc）。
     */
    fun buildArchive(
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile> = WebArchiveRows.filesOf(),
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Map<String, String> =
        StoreArchive.export(
            accounts = emptyList(),
            createdBy = CREATED_BY,
            appVersion = APP_VERSION,
            stores = stores,
            files = files,
            options = ExportOptions(now = now),
        )

    /** 端内 → 完整档案字节映射（只读端内，不写任何 store）。 */
    fun exportArchive(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Map<String, String> =
        buildArchive(readStores(), now = now)

    /**
     * 导出动作：组装 + 交给浏览器下载（[WebArchiveIo.download]），返回一句如实的结论文本。
     * 跑在设置页那一行的 `onClick` 里（同步：浏览器要求下载由用户手势发起）。
     */
    fun exportToDownload(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): String {
        val archive = exportArchive(now)
        WebArchiveIo.download(archive)
        return "已导出 ${archive.size} 个条目（${archive.keys.count { it.contains("/stores/") }} 个 store），" +
            "浏览器把它存到默认下载位置"
    }

    /**
     * 自校验一份**已解出来**的档案（§6.2 第 3 条的前半，纯读）：`manifest.json` 在不在、
     * `format` 对不对、`version` 认不认识（不认识就明确失败，A7）、每个 `stores/` 下的 `.json`
     * 解不解得开、账号后缀有没有碰撞（§4 / A9）。
     *
     * S1b：判定已经收到 `:core`（`StoreArchive.inspect`）—— 这里只剩「把字节映射交进去」与
     * 本端结果类型（[WebArchiveCheck]）的映射。
     */
    fun checkArchive(entries: Map<String, String>): WebArchiveCheck {
        return when (val inspection = StoreArchive.inspect(archiveSourceOf(entries))) {
            is ArchiveInspection.Failed -> WebArchiveCheck.Failed(inspection.reason)
            is ArchiveInspection.Ok -> WebArchiveCheck.Ok(
                version = inspection.manifest.version,
                stores = inspection.stores,
                files = inspection.files,
                redacted = inspection.manifest.redacted.size,
            )
        }
    }

    /** 导入那一行的结论文本（纯函数，供屏与「手工点一次」共用同一份措辞）。 */
    fun importStatusText(picked: PickedArchive): String = when (picked) {
        is PickedArchive.Failed -> "导入失败：${picked.reason}"
        is PickedArchive.Entries -> importAndReport(picked)
    }

    /**
     * 真导 + 一句如实的结论。
     *
     * 链路逐字是 §6.2 第 3 条那一串：自校验（`StoreArchive.import` 内部先做）→ §6.4 合并 → 报告。
     * 坏档案在写第一笔之前就返回 [ImportResult.Failed]，**本机零改动**（A7）。
     *
     * ⚠️ 本端**没有**「已导入」标记：导入是用户主动点一次的动作（见类 KDoc 那段）。
     */
    fun importArchive(
        entries: Map<String, String>,
        target: ArchiveImportTarget = WebArchiveImportTarget(),
    ): ImportResult = StoreArchive.import(archiveSourceOf(withoutUnlandedFiles(entries)), target)

    private fun importAndReport(picked: PickedArchive.Entries): String {
        val unlandedFiles = unlandedFilesCount(picked.entries)
        return when (val result = importArchive(picked.entries)) {
            is ImportResult.Failed -> "「${picked.fileName}」不可用：${result.reason}（本机数据未改动）"
            is ImportResult.Ok -> buildString {
                append("「${picked.fileName}」已导入：:core 的 StoreArchive.import 按 §6.4 写入 ")
                append(result.keysWritten)
                append(" 个键 / 保持 ")
                append(result.keysKept)
                append(" 个")
                if (result.filesWritten > 0) {
                    append("；三族行数组里有落点的那一族落了 ${result.filesWritten} 条")
                }
                if (unlandedFiles > 0) {
                    append("；本端没有文件系统，$unlandedFiles 个其他 files/ 条目未落地")
                }
            }
        }
    }
}

/** 一份档案自校验的结果。 */
internal sealed interface WebArchiveCheck {

    /** 通过。 */
    data class Ok(
        val version: Int,
        /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
        val stores: List<String>,
        /** `files/` 那一层里的文件数（本端导出时最多 1 条 —— 只有自建日程那一族有落点）。 */
        val files: Int,
        /** `manifest.redacted` 的条目数（凭据族没跟过来的那些，UI 只说数量）。 */
        val redacted: Int,
    ) : WebArchiveCheck

    /** 明确失败。 */
    data class Failed(val reason: String) : WebArchiveCheck
}

/**
 * 设置页那两个归档入口动作 + 它的一行结果文本（S6）。
 *
 * 为什么状态在外壳这一层、而不是屏里：导出要开浏览器下载锚点、导入要开 `<input type=file>` 选一份
 * zip，两者都是**页面级**的动作（与 `:app` 把动作放 `AppSettings` 的 `DataPrivacySection`、
 * 桌面把动作放 `DesktopSettingsHost` 同一条分工）。外壳建一份、两处设置屏（`WebRoutes.PROFILE`
 * 与 `AppRoute.Settings`）共用，切走再回来还能看到上一次的结果。
 */
internal class WebArchiveActions {

    /** 上一次动作的结论（`null` = 还没点过）。 */
    var status: String? by mutableStateOf(null)
        private set

    /** 导出：组装（`localStorage` 投影 → 档案）→ 浏览器下载（同步，落在手势里）。 */
    fun export() {
        status = runCatching { WebArchiveHost.exportToDownload() }
            .getOrElse { "导出失败：${it.message ?: it::class.simpleName}" }
    }

    /** 导入：选一份 zip → 内存里解 → 自校验 → `:core` 的 `StoreArchive.import` 按 §6.4 合并 → 如实报一句。 */
    fun import() {
        WebArchiveIo.pick { picked -> status = WebArchiveHost.importStatusText(picked) }
    }
}
