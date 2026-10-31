package com.xjtu.toolbox.archive

import java.io.File
import java.util.Properties

/** 桌面（jvm）侧的 `files` 层投影（只读）。 */
actual fun archiveFileBackend(): ArchiveFileBackend? =
    archiveDataRoot()?.let { JvmFileBackend(File(it)) }

actual fun archiveCacheRoot(): String? = null

/**
 * 桌面文件投影：`files/agent_sessions/…` ⇄ `<数据根>/agent_sessions<suffix>/…`。
 *
 * **课表缓存族（`files/schedule/…`，B-9）**：桌面没有 `cacheDir`（所以 [archiveCacheRoot] 恒为 null），
 * 这一族的真身是键值店 `<数据根>/schedule_cache<suffix>.properties` —— 与
 * `DesktopScheduleHost.cache()` 建 `KeyValueScheduleCacheStore(secureKeyValueStore(…))` 落在
 * **同一个文件**上（桌面所谓「密文店」也就是一份 `0600` 的 `Properties`，见 `SecureStore.jvm.kt`）。
 *
 * 这里**绕开 `KeyValueStore` 直接读那份文件**，和同一目录下的 [JvmStoreBackend] 一个做法，理由是两条：
 * ① `:shared:data` 的 `secureKeyValueStore` 不在 `:feature:settings` 的依赖里（只需要 `:shared:model`）；
 * ② 导出全程只读，直接读盘拿到的永远是**当下**这份文件，不会撞上「进程里那个单例句柄是早先 init 的旧值」。
 *
 * 映射：店里的键 `<key>` → `files/schedule/<key>.json`，侧挂的 `<key>#written_at`（毫秒 epoch）
 * 当这一条的 mtime（`KeyValueScheduleCacheStore` 的同一套键名）。它是缓存族（`ArchiveRegistry` 里
 * `schedule_cache` 标着 `rebuildable`），年龄丢了也只是「重拉一次」，不会丢用户数据。
 *
 * **单一表示（B-9 已收口）**：`schedule_cache` 这一族在档案里只有 `files/schedule/…` 这一份 ——
 * `DesktopArchiveHost.exportArchive` 会像成绩那一族一样把这份店从 `stores/` 里剔掉
 * ⇒ 桌面上不存在 `stores/schedule_cache.json` 这条路径。代价：`files/` 那一层没有逐键 `at`，
 * 导入时按导入时刻补上（`DesktopArchiveImport`）—— 这一族是 `rebuildable` 缓存，年龄丢了只是「重拉一次」。
 * 这里仍要算出 [ArchiveFileBackend.mtimeMs]，是给「同族其它条目」与守卫测试用的。
 */
class JvmFileBackend(private val dataRoot: File) : ArchiveFileBackend {

    /** 每个账号档那份 `Properties` 只读一次（导出一份档案会问它 many 次）。 */
    private val properties = HashMap<String, Properties?>()

    override fun list(suffix: String, prefix: String): List<String> = when (prefix) {
        // 只导出 .json：原子写盘残留的 .tmp 不进备份（与 dev 那条口径一致）
        AGENT_SESSIONS_DIR -> agentSessionsDir(suffix)
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { "$AGENT_SESSIONS_DIR/${it.name}" }
            ?.sorted()
            ?: emptyList()
        SCHEDULE_DIR -> scheduleProperties(suffix)
            ?.stringPropertyNames()
            ?.filterNot { it.endsWith(ArchiveCodec.WRITTEN_AT_SUFFIX) }
            ?.filter { "/" !in it }
            ?.map { "$SCHEDULE_DIR/$it.json" }
            ?.sorted()
            ?: emptyList()
        else -> emptyList()
    }

    override fun read(suffix: String, relPath: String): String? = when {
        relPath.startsWith("$AGENT_SESSIONS_DIR/") -> {
            val file = File(agentSessionsDir(suffix), relPath.removePrefix("$AGENT_SESSIONS_DIR/"))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        relPath.startsWith("$SCHEDULE_DIR/") ->
            scheduleKeyOf(relPath)?.let { scheduleProperties(suffix)?.getProperty(it) }
        else -> null
    }

    /** §6.3「mtime → `at`」：课表缓存族的年龄就是 `#written_at` 那个侧挂键的值。 */
    override fun mtimeMs(suffix: String, relPath: String): Long? {
        if (!relPath.startsWith("$SCHEDULE_DIR/")) return null
        val key = scheduleKeyOf(relPath) ?: return null
        return scheduleProperties(suffix)?.getProperty(key + ArchiveCodec.WRITTEN_AT_SUFFIX)?.toLongOrNull()
    }

    private fun agentSessionsDir(suffix: String): File = File(dataRoot, "agent_sessions$suffix")

    /** `files/schedule/<key>.json` → `<key>`；不在这一族 / 名字不安全 ⇒ null。 */
    private fun scheduleKeyOf(relPath: String): String? =
        relPath.removePrefix("$SCHEDULE_DIR/")
            .takeIf { it != relPath && it.endsWith(".json") }
            ?.removeSuffix(".json")
            ?.takeIf { it.isNotEmpty() && "/" !in it }

    private fun scheduleProperties(suffix: String): Properties? = properties.getOrPut(suffix) {
        val file = File(dataRoot, "$SCHEDULE_STORE_PREFIX$suffix.properties")
        if (!file.isFile) return@getOrPut null
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }.getOrNull() ?: return@getOrPut null
        props
    }

    private companion object {
        const val AGENT_SESSIONS_DIR = "agent_sessions"

        /** 档案里这一族的目录名（与 `:app` 的 `cache/data_cache<suffix>/` ⇄ `files/schedule/` 同一行，§6.3）。 */
        const val SCHEDULE_DIR = "schedule"

        /**
         * 桌面这份键值店的名字（`DesktopScheduleHost.SCHEDULE_CACHE_STORE`）：`:desktop` 依赖
         * `:feature:settings`，反过来不行，所以这里只能写字面量 —— 两处同值由
         * `ArchiveFileBackendJvmTest` 与 `DesktopScheduleJvmTest` 各钉一边。
         */
        const val SCHEDULE_STORE_PREFIX = "schedule_cache"
    }
}
