package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.ArchiveScoreReport
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import kotlin.time.Clock

/**
 * Web 这一端的导入写侧：`:core` 的 [ArchiveImportTarget] 在 wasm 上的落点。
 * `files/` 里只有自建日程与成绩缓存那一族（[ArchiveScoreReport]）能落地；其余条目由 [withoutUnlandedFiles]
 * 在导入前滤掉，`filesWritten` 才是真数。
 */
internal class WebArchiveImportTarget(
    /** 写侧：按端内名取那个键值缝。 */
    private val storeOf: (String) -> KeyValueStore = { keyValueStore(it) },
    /** 墙上时钟（旧档案里裸数组重建 `at` 用）。 */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = archiveStoreBackend().readStore(rawName)

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
        if (at != null) store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, at.toString())
    }

    /**
     * 删键（镜像档，§6.4）：wasm 的店就是 `localStorage` 的一面，[KeyValueStore.remove] 就是删掉；
     * 侧挂时间戳一起删（回滚时 [putKey] 会把两者一起放回去）。
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
     * 本端没有文件系统 ⇒ 非迁移族恒为 null（自建日程每次都走 [putFile] 的合并，幂等）；
     * 成绩缓存那一族端内其实有（`score_report_cache` 的 `all` 键）⇒ 按它读，§6.4「本端有 ⇒ 保持不变」照样成立。
     */
    override fun readFile(suffix: String, relPath: String): String? =
        if (!ArchiveScoreReport.isFamily(relPath)) null
        else storeOf(WebScoreReportCache.STORE_NAME).getString(WebScoreReportCache.KEY)

    /** 自建日程并进端内键值店；其余条目导入前已滤掉。 */
    override fun putFile(suffix: String, relPath: String, content: String) {
        // 成绩缓存那一族写回它自己的店（`score_report_cache` 的 `all` 键）
        if (ArchiveScoreReport.isFamily(relPath)) {
            storeOf(WebScoreReportCache.STORE_NAME).putString(WebScoreReportCache.KEY, content)
            return
        }
        WebArchiveRows.land(suffix, relPath, content, storeOf = storeOf)
    }

    /**
     * 形状归一（§6.3）：web 端内是 `{"at","grades"}`，旧档案里的裸数组用导入时刻重建 `at`。
     * 转不动 ⇒ null（跳过并计数）。
     */
    override fun fileContent(suffix: String, accountId: String?, relPath: String, content: String): String? =
        if (!ArchiveScoreReport.isFamily(relPath)) content else ArchiveScoreReport.toCanonical(content, now())
}

/** 滤掉本端没有落点的 `files/` 条目（保留 `manifest.json`、`stores/` 与自建日程）。 */
internal fun withoutUnlandedFiles(entries: Map<String, String>): Map<String, String> =
    entries.filterKeys { path ->
        val parsed = ArchivePaths.parseFilePath(path) ?: return@filterKeys true
        lands(parsed.first, parsed.second)
    }

/** 本端没有落点的 `files/` 条目数（导入结果里报出来）。 */
internal fun unlandedFilesCount(entries: Map<String, String>): Int =
    entries.keys.count { path ->
        val parsed = ArchivePaths.parseFilePath(path) ?: return@count false
        !lands(parsed.first, parsed.second)
    }

/** 这条 `files/` 路径在本端有没有落点：自建日程（限 `global/`）与成绩缓存那一族。 */
private fun lands(relPath: String, suffix: String): Boolean =
    ArchiveScoreReport.isFamily(relPath) || (suffix.isEmpty() && WebArchiveRows.of(relPath) != null)
