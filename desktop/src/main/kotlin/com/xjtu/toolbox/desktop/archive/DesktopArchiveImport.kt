package com.xjtu.toolbox.desktop.archive

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveFileBackend
import com.xjtu.toolbox.archive.ArchiveImportMode
import com.xjtu.toolbox.archive.ArchiveImportPlan
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchivePlanOutcome
import com.xjtu.toolbox.archive.ArchiveInspection
import com.xjtu.toolbox.archive.ArchiveManifest
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.ArchiveRowFamilies
import com.xjtu.toolbox.archive.ArchiveScoreReport
import com.xjtu.toolbox.archive.ArchiveSource
import com.xjtu.toolbox.archive.ArchiveStoreBackend
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveFileBackend
import com.xjtu.toolbox.archive.archiveSourceOf
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.desktop.DesktopScheduleHost
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.io.File

/**
 * 桌面端的导入写侧：`:core` 的 [ArchiveImportTarget] 在 jvm 上的落点。
 * `files/` 里 `agent_sessions<suffix>/` 写成文件，三族行数组并进键值店（[DesktopArchiveRows]），
 * 成绩缓存那一族（[ArchiveScoreReport]）并回它自己的店（`score_report<suffix>` 的 `score_report_<学号>`），
 * 课表缓存族（`files/schedule/<key>.json`，B-9）并回 `schedule_cache<suffix>` 店（键 `<key>` + 侧挂 `#written_at`），
 * 其余族桌面没有落点 ⇒ [fileRelPath] 一律返回 null，**如实计进** `ImportResult.Ok.filesSkipped`（不再静默丢）。
 *
 * 已知问题：三族的店名按当前账号解析，启动时那次导入还没登录，带后缀的两族会落到 `<店名>default`。
 */
class DesktopArchiveImportTarget(
    /** 写侧：按端内名取那个已有句柄（默认 [secureKeyValueStore]）。 */
    private val storeOf: (String) -> KeyValueStore = { secureKeyValueStore(it) },
    /** 读侧现状（`<数据根>/<name>.properties`）。 */
    private val storeBackend: ArchiveStoreBackend = archiveStoreBackend(),
    /** `files/` 那一层的读侧现状。 */
    private val files: ArchiveFileBackend? = archiveFileBackend(),
    /** 数据根（`<数据根>/agent_sessions<suffix>/…` 的父目录）。 */
    private val dataRoot: File? = archiveDataRootOrNull(),
    /** 墙上时钟（重建旧档案里裸数组的 `at` 用）。 */
    private val now: () -> Long = { System.currentTimeMillis() },
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = storeBackend.readStore(rawName)

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val store = storeOf(rawName)
        when (value) {
            is StoreValue.S -> store.putString(key, value.v)
            is StoreValue.B -> store.putBoolean(key, value.v)
            is StoreValue.I -> store.putInt(key, value.v)
            is StoreValue.L -> store.putString(key, value.v.toString())
            is StoreValue.F -> store.putString(key, value.v.toString())
            is StoreValue.SetValue -> store.putStringSet(key, value.v.toSet())
        }
        // §5.2 第 5 条：`at` 落在旁挂键上（档案里那份时间戳就是从这里来的）
        if (at != null) store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, at.toString())
    }

    override fun readFile(suffix: String, relPath: String): String? {
        // 成绩缓存那一族在桌面端内是 KV 店（`score_report<suffix>` 的 `score_report_<学号>`）⇒ 现状从店里读：
        // §6.4 文件层那条「本端有 ⇒ 保持不变」对 KV 载体一样成立。
        scoreReportValue(suffix, relPath)?.let { return it }
        return files?.read(suffix, relPath)
    }

    /**
     * 键名归一（§6.3）：档案里那一族的名字**不含学号**，桌面端内名要它（键 `score_report_<学号>`）。
     * 档案里那一档账号先查，没有时用本机当前账号；两边都拼不出 ⇒ null（这一条跳过并计数）。
     */
    override fun fileRelPath(suffix: String, accountId: String?, relPath: String): String? {
        if (ArchiveScoreReport.isFamily(relPath)) {
            val studentId = ArchiveScoreReport.studentIdOf(suffix, accountId ?: activeAccountId()) ?: return null
            return ArchiveScoreReport.endRelPath(studentId)
        }
        // 桌面能落地的族只有三类（B-9）：`agent_sessions/`、课表缓存 `schedule/`、三族行数组。
        // 其余的（如 `:app` 将来往 `files/` 里加的新族）桌面**没有落点** ⇒ 返回 null，让 `:core` 计数，
        // 而不是进了 [putFile] 才被悄无声息地丢掉。
        return when {
            relPath.startsWith(AGENT_SESSIONS_PREFIX) -> relPath
            relPath.startsWith(SCHEDULE_PREFIX) -> relPath
            ArchiveRowFamilies.of(relPath) != null -> relPath
            else -> null
        }
    }

    /**
     * 形状归一（§6.3）：桌面端内本来就是 `{"at","grades"}`，旧档案里的裸数组用导入时刻重建 `at`。
     * 转不动 ⇒ null（跳过并计数）。
     */
    override fun fileContent(suffix: String, accountId: String?, relPath: String, content: String): String? =
        if (!ArchiveScoreReport.isFamily(relPath)) content else ArchiveScoreReport.toCanonical(content, now())

    override fun putFile(suffix: String, relPath: String, content: String) {
        // 成绩缓存那一族并回它自己的店（不归键值店 / 也不落成文件）
        if (landScoreReport(suffix, relPath, content)) return
        if (DesktopArchiveRows.land(suffix, relPath, content, storeOf = storeOf) != null) return
        // 课表缓存族（B-9）：`files/schedule/<key>.json` → `schedule_cache<suffix>` 店的 `<key>` 键，
        // 侧挂时间戳用导入时刻（这一族是缓存，年龄丢了只是「重拉一次」，不丢用户数据）。
        if (landScheduleCache(suffix, relPath, content)) return
        val root = dataRoot ?: return
        if (!relPath.startsWith(AGENT_SESSIONS_PREFIX)) return
        val target = File(File(root, "agent_sessions$suffix"), relPath.removePrefix(AGENT_SESSIONS_PREFIX))
        DesktopArchiveIo.writeFile(target, content.toByteArray(Charsets.UTF_8))
    }

    /**
     * 删键（镜像档，§6.4）：桌面店就是一份 `.properties`，[KeyValueStore.remove] 就是删掉；
     * 连着侧挂时间戳（§5.2 第 5 条）一起删，回滚时 [putKey] 会把两者一起放回去。
     *
     * 红线清单（[ArchiveRedLines]）上的店在共享层就被挡下了，走不到这里。
     */
    override fun removeKey(rawName: String, key: String): Boolean = runCatching {
        val store = storeOf(rawName)
        store.remove(key)
        store.remove(key + ArchiveCodec.WRITTEN_AT_SUFFIX)
        true
    }.getOrDefault(false)

    /**
     * 镜像档只枚举桌面端内**真当文件存**的那一族（`agent_sessions/`）。
     *
     * 其余族（行数组 / 成绩缓存 / 课表缓存）端内是键值店里的键，“多余条目”对应哪一批键不确定 ⇒
     * 一律不枚举 ⇒ 不删（保守口径：拿不准就不删）。
     */
    override fun listFiles(suffix: String, family: String): List<String> =
        if (family != AGENT_SESSIONS_FAMILY) {
            emptyList()
        } else {
            (File(dataRoot ?: return emptyList(), "agent_sessions$suffix").listFiles() ?: emptyArray())
                .filter { it.isFile }
                .map { AGENT_SESSIONS_PREFIX + it.name }
                .sorted()
        }

    override fun removeFile(suffix: String, relPath: String): Boolean = runCatching {
        if (!relPath.startsWith(AGENT_SESSIONS_PREFIX)) {
            false
        } else {
            File(File(dataRoot ?: return false, "agent_sessions$suffix"), relPath.removePrefix(AGENT_SESSIONS_PREFIX))
                .delete()
        }
    }.getOrDefault(false)

    /** 本机当前账号（没登录 / 取不到 ⇒ null）。 */
    private fun activeAccountId(): String? =
        runCatching { AccountContext.activeAccountId }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 成绩缓存那一族在桌面端内所在的店名：`score_report` + 账号后缀（`DesktopScoreReportCache.storeName(accountId)`
     * 的同一个口径；档案里的 suffix 与 `AccountContext.suffixFor` 同一种写法）。
     */
    private fun scoreReportStoreName(suffix: String): String = DesktopScoreReportCache.STORE_NAME + suffix

    /** 这一条是成绩缓存那一族时给出端内键（`score_report_<学号>`）；不是 / 名字里没有学号（规范名）⇒ null。 */
    private fun scoreReportKey(relPath: String): String? =
        if (ArchiveScoreReport.isFamily(relPath)) {
            ArchiveScoreReport.legacyStudentId(relPath)?.let { DesktopScoreReportCache.keyFor(it) }
        } else {
            null
        }

    /** 端内店里那一族的现状（§6.4：本端有 ⇒ 保持不变）。 */
    private fun scoreReportValue(suffix: String, relPath: String): String? {
        val key = scoreReportKey(relPath) ?: return null
        return runCatching { storeOf(scoreReportStoreName(suffix)).getString(key) }.getOrNull()
    }

    /** 把档案里那一族写回它自己的店；不是这一族 ⇒ false（交给别的落点）。 */
    private fun landScoreReport(suffix: String, relPath: String, content: String): Boolean {
        val key = scoreReportKey(relPath) ?: return false
        storeOf(scoreReportStoreName(suffix)).putString(key, content)
        return true
    }

    /** 课表缓存族在桌面端内所在的店名（`DesktopScheduleHost.SCHEDULE_CACHE_STORE` + 账号后缀，同一个口径）。 */
    private fun scheduleCacheStoreName(suffix: String): String = DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix

    /** 把 `files/schedule/<key>.json` 并回课表缓存店；不是这一族 ⇒ false（交给别的落点）。 */
    private fun landScheduleCache(suffix: String, relPath: String, content: String): Boolean {
        val key = scheduleCacheKey(relPath) ?: return false
        val store = storeOf(scheduleCacheStoreName(suffix))
        store.putString(key, content)
        store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, now().toString())
        return true
    }

    /** `schedule/<key>.json` → `<key>`；成绩那一族 / 不是这一族 / 名字 unsafe ⇒ null。 */
    private fun scheduleCacheKey(relPath: String): String? {
        if (ArchiveScoreReport.isFamily(relPath)) return null
        return relPath.removePrefix(SCHEDULE_PREFIX)
            .takeIf { it != relPath && it.endsWith(".json") }
            ?.removeSuffix(".json")
            ?.takeIf { it.isNotEmpty() && "/" !in it }
    }

    private companion object {
        /** 桌面 `files/` 里落成文件的族之一（会话记录）。 */
        const val AGENT_SESSIONS_PREFIX = "agent_sessions/"

        /** 课表缓存族（B-9）：`files/schedule/<key>.json` ⇄ `schedule_cache<suffix>` 店的 `<key>` 键。 */
        const val SCHEDULE_PREFIX = "schedule/"

        /** 镜像档枚举用的族名（= 相对路径里的第一段）。 */
        const val AGENT_SESSIONS_FAMILY = "agent_sessions"
}
}

/** 桌面端的一次性导入编排：自校验 → §6.4 合并 → 落「已导入」标记。 */
object DesktopArchiveImport {

    /** 标记文件名（档案目录里的一份点文件；[DesktopArchiveIo.readDirectory] 读档案时会跳过点文件）。 */
    const val MARKER_NAME = ".imported"

    private const val MARKER_SUFFIX = ".imported"
    private const val TAG = "DesktopArchive"

    /** 那一份档案的标记文件（目录形态写进目录里，zip 形态写它旁边）。 */
    fun markerFileFor(archive: File): File =
        if (archive.isDirectory) {
            File(archive, MARKER_NAME)
        } else {
            File(archive.parentFile, archive.name + MARKER_SUFFIX)
        }

    /** 档案的身份（§2.1）。 */
    fun identityOf(manifest: ArchiveManifest): String =
        "${manifest.createdAt}|${manifest.createdBy}|${manifest.version}"

    /** 标记里记着的身份（第一行）；没有标记 / 读不出来 ⇒ null。 */
    fun readMarkerIdentity(marker: File): String? =
        if (!marker.isFile) null
        else runCatching { marker.readText().lineSequence().firstOrNull() }.getOrNull()

    /** 跑一次导入（自校验 → §6.4 合并 → 标记）。 */
    fun run(
        archive: File,
        entries: Map<String, String>,
        target: ArchiveImportTarget = DesktopArchiveImportTarget(),
    ): DesktopImportOutcome = run(archive, archiveSourceOf(entries), target)

    /** 同上，但调用方已经有一个 [ArchiveSource]（[DesktopArchiveHost.checkArchive] 走的就是这一条）。 */
    fun run(
        archive: File,
        source: ArchiveSource,
        target: ArchiveImportTarget,
    ): DesktopImportOutcome {
        return when (val planned = plan(archive, source, target, ArchiveImportMode.MERGE)) {
            is DesktopArchivePlanOutcome.Planned -> apply(planned)
            is DesktopArchivePlanOutcome.AlreadyImported ->
                DesktopImportOutcome.AlreadyImported(planned.archive, planned.identity)
            is DesktopArchivePlanOutcome.Failed -> DesktopImportOutcome.Failed(planned.reason)
        }
    }

    /**
     * 第一步：算差异，**端内一个字节不动**（§6.4「显式镜像档」）。
     *
     * 与 [run] 同一条链路（自校验 → 幂等标记 → [StoreArchive.plan]），只是把“合并”换成“先看后写”：
     * 设置页拿到 [DesktopArchivePlanOutcome.Planned] 弹窗（话术见 [ArchiveImportPrompt]），
     * 用户确认后再调 [apply]。
     */
    fun plan(
        archive: File,
        source: ArchiveSource,
        target: ArchiveImportTarget,
        mode: ArchiveImportMode = ArchiveImportMode.MERGE,
    ): DesktopArchivePlanOutcome {
        val validated = StoreArchive.inspect(source)
        if (validated is ArchiveInspection.Failed) return DesktopArchivePlanOutcome.Failed(validated.reason)
        val manifest = (validated as ArchiveInspection.Ok).manifest

        val identity = identityOf(manifest)
        if (readMarkerIdentity(markerFileFor(archive)) == identity) {
            return DesktopArchivePlanOutcome.AlreadyImported(archive, identity)
        }

        return when (val planned = StoreArchive.plan(source, target, mode)) {
            is ArchivePlanOutcome.Failed -> DesktopArchivePlanOutcome.Failed(planned.reason)
            is ArchivePlanOutcome.Ok -> DesktopArchivePlanOutcome.Planned(archive, identity, planned.plan, target)
        }
    }

    /** 第二步：一次性落地 + 落标记（镜像档失败已整体回滚，这里只要把原因抬上去、不写标记）。 */
    fun apply(planned: DesktopArchivePlanOutcome.Planned): DesktopImportOutcome =
        when (val result = StoreArchive.apply(planned.plan, planned.target)) {
            is ImportResult.Failed -> DesktopImportOutcome.Failed(result.reason)
            is ImportResult.Ok -> {
                writeMarker(markerFileFor(planned.archive), planned.identity, result)
                Log.i(
                    TAG,
                    "导入 ${planned.archive.name}：:core 的 StoreArchive 写入 ${result.keysWritten} 个键 /" +
                        " 保持 ${result.keysKept} 个 / 写回 ${result.filesWritten} 个文件" +
                        (if (result.keysDeleted > 0) " / 删掉 ${result.keysDeleted} 个键" else "") +
                        (if (result.filesDeleted > 0) " / 删掉 ${result.filesDeleted} 条文件" else "") +
                        (if (result.filesSkipped > 0) " / 跳过 ${result.filesSkipped} 条" else ""),
                )
                DesktopImportOutcome.Imported(planned.archive, planned.identity, result)
            }
        }

    /** 标记：第一行是身份，其余是这一次落地的计数（给人看的，不是判据）。 */
    private fun writeMarker(marker: File, identity: String, result: ImportResult.Ok) {
        marker.parentFile?.mkdirs()
        marker.writeText(
            buildString {
                appendLine(identity)
                appendLine("keysWritten=${result.keysWritten}")
                appendLine("keysKept=${result.keysKept}")
                appendLine("filesWritten=${result.filesWritten}")
                appendLine("filesSkipped=${result.filesSkipped}")
                if (result.keysDeleted > 0) appendLine("keysDeleted=${result.keysDeleted}")
                if (result.filesDeleted > 0) appendLine("filesDeleted=${result.filesDeleted}")
            },
        )
    }
}

/** 一次导入的结论。 */
sealed interface DesktopImportOutcome {

    /** 真的导了：写了几笔见 [result]。 */
    data class Imported(val archive: File, val identity: String, val result: ImportResult.Ok) : DesktopImportOutcome

    /** 这份档案的身份与标记一致 ⇒ 什么都不做（§6.1 第 1 条：导入幂等）。 */
    data class AlreadyImported(val archive: File, val identity: String) : DesktopImportOutcome

    /** 明确失败，端内零改动。 */
    data class Failed(val reason: String) : DesktopImportOutcome
}

/** [DesktopArchiveImport.plan] 的结论（第一步：端内零改动）。 */
sealed interface DesktopArchivePlanOutcome {

    /** 差异算好了：弹窗用 [plan]，确认后交给 [DesktopArchiveImport.apply]。 */
    data class Planned(
        val archive: File,
        val identity: String,
        val plan: ArchiveImportPlan,
        /** 算差异时用的那一份写入缝（落地也用它）。 */
        val target: ArchiveImportTarget,
    ) : DesktopArchivePlanOutcome

    /** 这份档案已经导过（标记与它一致）⇒ 不必再问用户选哪一档。 */
    data class AlreadyImported(val archive: File, val identity: String) : DesktopArchivePlanOutcome

    /** 档案本身不成立 / 自校验不过；端内零改动。 */
    data class Failed(val reason: String) : DesktopArchivePlanOutcome
}

/** 数据根（这一端恒非 null；包一层 runCatching 只是为了不在测试环境里炸）。 */
private fun archiveDataRootOrNull(): File? = runCatching { File(DesktopArchiveHost.dataRoot().path) }.getOrNull()
