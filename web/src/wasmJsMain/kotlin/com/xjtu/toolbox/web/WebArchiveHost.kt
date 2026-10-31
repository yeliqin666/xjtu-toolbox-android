package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveScoreReport
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ArchiveImportMode
import com.xjtu.toolbox.archive.ArchiveImportPlan
import com.xjtu.toolbox.archive.ArchivePlanOutcome
import com.xjtu.toolbox.archive.ArchiveImportPrompt
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveStoreBackend
import kotlin.time.Clock

/** Web 这一端的档案宿主装配；没有文件系统，`files/` 里只有自建日程（[WebArchiveRows]）与成绩缓存那一族（[ArchiveScoreReport]）。 */
internal object WebArchiveHost {

    /** §2.1 `createdBy`：本端固定写 `web`。 */
    const val CREATED_BY = "web"

    /** §2.1 `appVersion`：与 `WebSettingsHost.versionText` 同一个字面。 */
    const val APP_VERSION = "Web 构建（wasm）"

    /** 读端内 store 投影（`localStorage` 的 `xjtu-toolbox.` 前缀）。 */
    fun readStores(backend: ArchiveStoreBackend = archiveStoreBackend()): List<StoreSnapshot> =
        backend.storeNames().mapNotNull { backend.readStore(it) }

    /** 纯组装：已投影的 stores 与行数组 → [StoreArchive.export] 的档案字节映射（本端没有账号，全在 `global/`）。 */
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
    fun exportArchive(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Map<String, String> {
        val stores = readStores()
        // §6.3 逐族迁移（「成绩缓存」那一族）：端内是店、档案里是文件 ⇒ 这一族的店**不进 `stores/`**
        // （否则同一份缓存会在档案里出现两份；Android 那边还会把它当 prefs 店落下来）。
        return buildArchive(
            stores = stores.filterNot { it.rawName == WebScoreReportCache.STORE_NAME },
            files = WebArchiveRows.filesOf() + listOfNotNull(scoreFileOf(stores)),
            now = now,
        )
    }

    /**
     * §6.3 逐族迁移（「成绩缓存」那一族）：端内是店 `score_report_cache` 的 `all` 键（[WebScoreReportCache]），
     * 档案里是**文件** `schedule/score_report.json`，值是 `{"at":<ms>,"grades":[…]}`，名字里不带学号。
     *
     * 本端没有账号维度（全在 `global/`）。端内值已经带着 `at`；旧形状（裸数组）用导出时刻补上。
     * 没这份缓存 / 转不动 ⇒ null（不出这一条）。
     */
    private fun scoreFileOf(
        stores: List<StoreSnapshot>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): ArchiveFile? {
        val raw = stores.firstOrNull { it.rawName == WebScoreReportCache.STORE_NAME }
            ?.keys?.get(WebScoreReportCache.KEY) as? StoreValue.S ?: return null
        val canonical = ArchiveScoreReport.toCanonical(raw.v, now()) ?: return null
        return ArchiveFile("", ArchiveScoreReport.CANONICAL_REL, canonical)
    }

    /** 导出：组装 + 交给浏览器下载，返回结论文本。 */
    fun exportToDownload(now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): String {
        val archive = exportArchive(now)
        WebArchiveIo.download(archive)
        return "已导出 ${archive.size} 个条目（${archive.keys.count { it.contains("/stores/") }} 个 store），" +
            "浏览器把它存到默认下载位置"
    }

    /** 自校验一份已解出来的档案（§6.2 第 3 条的前半，纯读） */
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

    /** 真导 + 结论文本。 */
    fun importArchive(
        entries: Map<String, String>,
        target: ArchiveImportTarget = WebArchiveImportTarget(),
    ): ImportResult = StoreArchive.import(archiveSourceOf(withoutUnlandedFiles(entries)), target)

    /**
     * 第一步：解出来的档案只算差异，`localStorage` 一个字节不动（§6.4「显式镜像档」）。
     *
     * 屏上弹窗的话术全用 [com.xjtu.toolbox.archive.ArchiveImportPrompt]；差异那一行用
     * `ArchiveImportPrompt.diffSummary(mode, plan.diff)`（换档位重算一句就行，不用重新读 zip）。
     */
    fun planImport(
        entries: Map<String, String>,
        mode: ArchiveImportMode = ArchiveImportMode.MERGE,
        target: ArchiveImportTarget = WebArchiveImportTarget(),
    ): WebArchivePlanOutcome = when (
        val planned = StoreArchive.plan(archiveSourceOf(withoutUnlandedFiles(entries)), target, mode)
    ) {
        is ArchivePlanOutcome.Failed -> WebArchivePlanOutcome.Failed(planned.reason)
        is ArchivePlanOutcome.Ok -> WebArchivePlanOutcome.Planned(planned.plan, target)
    }

    /** 第二步：用户确认后落地（镜像档中途失败已在共享层整体回滚）。 */
    fun applyImport(planned: WebArchivePlanOutcome.Planned): ImportResult =
        StoreArchive.apply(planned.plan, planned.target)

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
                    append("；自建日程落了 ${result.filesWritten} 条")
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
        /** `files/` 那一层里的文件数（本端导出时最多 1 条：自建日程）。 */
        val files: Int,
        /** `manifest.redacted` 的条目数（凭据族没跟过来的那些，UI 只说数量）。 */
        val redacted: Int,
    ) : WebArchiveCheck

    /** 明确失败。 */
    data class Failed(val reason: String) : WebArchiveCheck
}

/** [WebArchiveHost.planImport] 的结论（第一步：`localStorage` 零改动）。 */
internal sealed interface WebArchivePlanOutcome {

    /** 差异算好了：弹窗用 [plan]，确认后交给 [WebArchiveHost.applyImport]。 */
    data class Planned(
        val plan: ArchiveImportPlan,
        /** 算差异时用的那一份写入缝（落地也用它）。 */
        val target: ArchiveImportTarget,
    ) : WebArchivePlanOutcome

    /** 档案本身不成立；本机零改动。 */
    data class Failed(val reason: String) : WebArchivePlanOutcome
}

/** 设置页那两个归档入口动作 + 它的一行结果文本。 */
internal class WebArchiveActions {

    /** 上一次动作的结论（`null` = 还没点过）。 */
    var status: String? by mutableStateOf(null)
        private set

    /**
     * 选完 zip 之后的两个档位方案（`null` 元素 = 那一档算不出来）。非空 ⇒ 屏上该弹“导入方式”单选。
     *
     * 两份都是 [WebArchiveHost.planImport] 算的（`localStorage` 一个字节没动），用户在单选之间切只换着看那句话，
     * 点确认才落地（[confirm]）。
     */
    var mergePlan: WebArchivePlanOutcome.Planned? by mutableStateOf(null)
        private set
    var mirrorPlan: WebArchivePlanOutcome.Planned? by mutableStateOf(null)
        private set

    /** 弹窗里选中的档位（默认 = 那一档，与共享层一致）。 */
    var mode: ArchiveImportMode by mutableStateOf(ArchiveImportMode.MERGE)

    /** 还有待确认的导入吗（屏上决定弹不弹窗）。 */
    val pending: Boolean get() = planFor(mode) != null

    /** 当前选中档位的差异那一行（话术来自共享层 [ArchiveImportPrompt]）。 */
    val diffText: String? get() = planFor(mode)?.plan?.summary

    /** 当前选中档位的额外提醒（镜像档会删东西时非空）；屏上把它倒红字。 */
    val warningText: String? get() = planFor(mode)?.let { ArchiveImportPrompt.warning(mode, it.plan.diff) }

    private fun planFor(mode: ArchiveImportMode): WebArchivePlanOutcome.Planned? =
        if (mode == ArchiveImportMode.MIRROR) mirrorPlan else mergePlan
    /** 导出：组装（`localStorage` 投影 → 档案）→ 浏览器下载（同步，落在手势里）。 */
    fun export() {
        status = runCatching { WebArchiveHost.exportToDownload() }
            .getOrElse { "导出失败：${it.message ?: it::class.simpleName}" }
    }

    /** 导入：选 zip → 内存里解 → 自校验 → `StoreArchive.import`（§6.4）→ 结论文本。 */
    fun import() {
        WebArchiveIo.pick { picked -> status = WebArchiveHost.importStatusText(picked) }
    }

    /**
     * 第一步（带档位选择的那一条）：选 zip → 解 → 把两个档位的差异都算出来（`localStorage` 零改动）。
     *
     * 屏上拿到非空的 [mergePlan] / [mirrorPlan] 就弹窗：标题用 [ArchiveImportPrompt.TITLE]，
     * 两个选项的文字用 [ArchiveImportPrompt.label] / [ArchiveImportPrompt.detail]，差异行用 [diffText]，
     * 提醒用 [warningText]，按钮用 [ArchiveImportPrompt.CONFIRM] / [ArchiveImportPrompt.CANCEL]。
     */
    fun choose() {
        WebArchiveIo.pick { picked ->
            mergePlan = null
            mirrorPlan = null
            when (picked) {
                is PickedArchive.Failed -> status = "导入失败：${picked.reason}"
                is PickedArchive.Entries -> {
                    mergePlan = WebArchiveHost.planImport(picked.entries, ArchiveImportMode.MERGE)
                        .plannedOrNull(picked.fileName)?.also { status = null }
                    if (mergePlan == null) return@pick
                    mirrorPlan = WebArchiveHost.planImport(picked.entries, ArchiveImportMode.MIRROR).plannedOrNull(picked.fileName)
                }
            }
        }
    }

    /** 第二步：真的落地当前选中的那一档（镜像档中途失败已在共享层整体回滚）。 */
    fun confirm() {
        val planned = planFor(mode) ?: return
        mergePlan = null
        mirrorPlan = null
        status = when (val result = WebArchiveHost.applyImport(planned)) {
            is ImportResult.Failed -> "导入没做成：${result.reason}（本机数据未改动）"
            is ImportResult.Ok -> buildString {
                append(if (mode == ArchiveImportMode.MIRROR) "已按“以档案为准”导入：" else "已按默认档导入：")
                append("写入 ${result.keysWritten} 个键 / 保持 ${result.keysKept} 个")
                if (result.keysDeleted > 0 || result.filesDeleted > 0) {
                    append("；删掉 ${result.keysDeleted + result.filesDeleted} 条（红线清单上的不动）")
                }
            }
        }
    }

    /** 取消：本机零改动。 */
    fun cancel() {
        mergePlan = null
        mirrorPlan = null
        mode = ArchiveImportMode.MERGE
    }

    /** 算不出来的那一档把原因留在 [status] 上，给屏上一句明确的失败。 */
    private fun WebArchivePlanOutcome.plannedOrNull(fileName: String): WebArchivePlanOutcome.Planned? =
        when (this) {
            is WebArchivePlanOutcome.Planned -> this
            is WebArchivePlanOutcome.Failed -> {
                status = "「$fileName」不可用：$reason（本机数据未改动）"
                null
            }
        }
}
