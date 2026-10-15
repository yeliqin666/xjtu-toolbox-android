package com.xjtu.toolbox.archive

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.data.AppDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/**
 * `:app` 侧的**导入写入缝**（S4 收尾）：`:core` 的 [ArchiveImportTarget] 在 Android 上的落点。
 *
 * 合并语义一个字都不在这里 —— §6.4 的裁决（`at` 新者胜 / 缺 `at` 本端胜 / 本端有档案没有 ⇒ 保持不变 /
 * `set` 覆盖不并集 / 坏档案明确失败）全在 `:core` 的 [StoreArchive.import] 里；这一份只做它写下来的那几笔，
 * 按接口 KDoc 的三条「实现负责」：**按声明类型落地**、**旁挂时间戳**、**原子写**。
 *
 * ## 落点逐字照端内既有形状（§8.1 / C1 红线）
 *
 * | 档案里的东西 | Android 落回哪儿 | 为什么 |
 * |---|---|---|
 * | `stores/<store>.json` 的一个键 | `getSharedPreferences(<端内名>, MODE_PRIVATE).edit()` | 同一批文件名、同一批键、同一批值类型；**不新建 archive 专属 prefs** |
 * | `files/courses`、`files/media`、`files/jiaocai` 下的行数组 | Room 三张表的既有 `insert` / `upsert` | §8.3：不加表、不加列、不加迁移 |
 * | `files/agent_sessions/…` | `filesDir/agent_sessions<suffix>/` | §2.3 / `AgentSessionStore` 的落点 |
 * | `files/schedule/…` | `cacheDir/data_cache<suffix>/<key>.json` | §2.3 / `DataCache` 的落点 |
 *
 * ## `at` 在 Android 上**不重建旁挂键**（如实记一笔）
 *
 * 桌面/web 的课表族缓存旁挂 `<key>#written_at`（§5.2 第 5 条），而 Android 侧的读取投影
 * （[AndroidPrefsBackend]）**从不产出** `writtenAt`（普通 prefs 没有 mtime）⇒ 导入侧同样不写这个键。
 * 两端一致：档案里那个 `at` 只用来做 §6.4 的新旧裁决，不在 Android 的 prefs 里留下新键名。
 *
 * ## `set` 值走 `putStringSet`（§8.4）
 *
 * 档案里的 `set` 是数组，写回 Android **必须** `putStringSet`：写成逗号串的话，`VenueFavorites`
 * 那种 `getStringSet` 的读法会直接 `ClassCastException`。
 */
class AppArchiveImportTarget(
    /** 写侧：按**端内名逐字**取已有的那个 prefs 句柄（默认装配 = [AppArchiveImportTarget.Companion.of]）。 */
    private val prefsOf: (String) -> SharedPreferences,
    /** 读侧现状（S2 的缝 A；`archiveStoreBackend()`）。 */
    private val storeBackend: ArchiveStoreBackend,
    /** `files/` 那一层的写侧（Room 行数组 + 两族真文件）。 */
    private val files: AppArchiveFileWriter?,
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = storeBackend.readStore(rawName)

    /**
     * 把一个键写进端内 store：类型逐字照 [StoreValue] 的标签（§8.6 的「按声明类型落地」）。
     *
     * 同一个键在端内本来是另一种类型时不会被拒 —— `SharedPreferences` 允许改写类型，
     * 而端内的读法一律带默认值（`getString` / `toIntOrNull` / `toBooleanStrictOrNull`），**不抛**。
     * `commit()`（而不是 `apply()`）：导入是「这一次启动就生效」的事，且失败必须当场知道（A7）。
     */
    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val editor = prefsOf(rawName).edit()
        when (value) {
            is StoreValue.S -> editor.putString(key, value.v)
            is StoreValue.B -> editor.putBoolean(key, value.v)
            is StoreValue.I -> editor.putInt(key, value.v)
            is StoreValue.L -> editor.putLong(key, value.v)
            is StoreValue.F -> editor.putFloat(key, value.v)
            is StoreValue.SetValue -> editor.putStringSet(key, LinkedHashSet(value.v))
        }
        editor.commit()
        // `at` 不落地（见类 KDoc）：Android 的 prefs 没有旁挂时间戳这一档。
    }

    override fun readFile(suffix: String, relPath: String): String? = files?.read(suffix, relPath)

    override fun putFile(suffix: String, relPath: String, content: String) {
        files?.write(suffix, relPath, content)
    }

    companion object {
        /**
         * 默认装配：`Context` 的 prefs + `:core` 的 store 投影 + [AndroidArchiveFileWriter]
         * （Room 三表现状按 [AccountStore] 的账号列表 + 档案里的账号列表枚举）。
         *
         * `accountIds` 走 [AccountStore] 的**既有读法**（`XjtuApp.attachBaseContext` 里就在
         * `SecurePrefs.prewarm` 之后，见那里的顺序），并且**失败即空表**：导入是「有档案才跑」的
         * 一次性动作，枚举不全最多让某一族的「本端有没有」判得更保守，不会因此不动端内数据。
         */
        fun of(context: Context, archiveAccountIds: List<String> = emptyList()): AppArchiveImportTarget =
            AppArchiveImportTarget(
                prefsOf = { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) },
                storeBackend = archiveStoreBackend(),
                files = AndroidArchiveFileWriter(
                    filesRoot = context.filesDir,
                    cacheRoot = context.cacheDir,
                    room = AppRoomRows(
                        db = AppDatabase.getInstance(context),
                        accountIdsOf = { localAccountIds() + archiveAccountIds },
                    ),
                ),
            )

        /** 端内的账号列表（`AccountStore` 的既有读法）；取不到就当没有（不抛）。 */
        private fun localAccountIds(): List<String> =
            runCatching { AccountStore().list().map { it.accountId } }.getOrDefault(emptyList())
    }
}

/**
 * 端内 `files/` 那一层的**写侧**（§8.8）。
 *
 * 与只读的 [ArchiveFileBackend] 并列：那边枚举/读，这边把一条写回端内。`suffix` 由调用方给
 * （来自档案路径），逐字进端内目录名 `agent_sessions<suffix>` / `data_cache<suffix>`（§2.3）。
 */
interface AppArchiveFileWriter {

    /** 本端这一个 `files/` 相对路径现在的内容；**本端没有这一族 ⇒ null**（§6.4 文件层：本端有 ⇒ 保持不变）。 */
    fun read(suffix: String, relPath: String): String?

    /** 把一个 `files/` 条目写回端内（先写临时文件再换名，§8.8）。 */
    fun write(suffix: String, relPath: String, content: String)
}

/** Room 三张表那一半的写侧（`files/courses` / `files/media` / `files/jiaocai` 三条相对路径）。 */
interface RoomRowWriter {

    /**
     * 这一个相对路径现在的**行数组**（导出用的同一批适配器 ⇒ §2.3 的排序逐字相同）；
     * **空表 ⇒ null**（= 本端没有这一族，档案里那份才写）。
     */
    fun read(relPath: String): String?

    /** 把档案里的行数组写回三张表之一（只走既有 DAO 的既有 insert/upsert，§8.3）。 */
    fun write(relPath: String, content: String)
}

/**
 * Android 的 `files/` 写侧：三条 Room 行数组相对路径走 [room]，两族真文件走文件系统。
 *
 * 路由是**纯字符串**：相对路径的前缀段（`agent_sessions/` / `schedule/`）在 §2.3 里就是这两族的定义，
 * 不猜别的（其余相对路径一律不写 —— 档案里不该有，有也不落地）。
 */
class AndroidArchiveFileWriter(
    private val filesRoot: File,
    private val cacheRoot: File?,
    private val room: RoomRowWriter,
) : AppArchiveFileWriter {

    override fun read(suffix: String, relPath: String): String? = when {
        relPath in ROOM_ROW_FAMILIES -> room.read(relPath)
        relPath.startsWith(AGENT_SESSIONS_PREFIX) -> {
            val file = File(agentSessionsDir(suffix), relPath.removePrefix(AGENT_SESSIONS_PREFIX))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        relPath.startsWith(SCHEDULE_PREFIX) -> {
            val dir = scheduleDir(suffix) ?: return null
            val file = File(dir, relPath.removePrefix(SCHEDULE_PREFIX))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        else -> null
    }

    override fun write(suffix: String, relPath: String, content: String) {
        when {
            relPath in ROOM_ROW_FAMILIES -> room.write(relPath, content)
            relPath.startsWith(AGENT_SESSIONS_PREFIX) ->
                writeAtomically(File(agentSessionsDir(suffix), relPath.removePrefix(AGENT_SESSIONS_PREFIX)), content)
            relPath.startsWith(SCHEDULE_PREFIX) ->
                scheduleDir(suffix)?.let { writeAtomically(File(it, relPath.removePrefix(SCHEDULE_PREFIX)), content) }
        }
    }

    private fun agentSessionsDir(suffix: String): File = File(filesRoot, "agent_sessions$suffix")

    private fun scheduleDir(suffix: String): File? = cacheRoot?.let { File(it, "data_cache$suffix") }

    /** 先写 `<name>.tmp` 再原子换名：任一步失败都不留下半个文件（§8.8）。 */
    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(content)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() }
            throw e
        }
    }

    private companion object {
        val ROOM_ROW_FAMILIES = setOf(
            RoomArchiveFamilies.CUSTOM_COURSES_REL,
            RoomArchiveFamilies.DOWNLOAD_TASKS_REL,
            RoomArchiveFamilies.JIAOCAI_SHELF_REL,
        )
        const val AGENT_SESSIONS_PREFIX = "agent_sessions/"
        const val SCHEDULE_PREFIX = "schedule/"
    }
}

/**
 * Room 三张表的写侧实现：读走 S4 的行适配器（同一批排序），写走既有 DAO 的既有 insert/upsert（§8.3）。
 *
 * [ArchiveImportTarget] 是**同步**接口（`:core` 的合并语义只有一份，不能为了某端的 suspend 存储改写），
 * 而 DAO 是 suspend ⇒ 这里用 `runBlocking` 把那一笔拉直（先例：`ScheduleWidgetProvider:487`）。
 * 量级是「三张表的行数组各一次」，不是逐行的一次往返。
 */
class AppRoomRows(
    private val db: AppDatabase,
    private val accountIdsOf: () -> List<String>,
) : RoomRowWriter {

    override fun read(relPath: String): String? = runBlocking {
        when (relPath) {
            RoomArchiveFamilies.CUSTOM_COURSES_REL -> {
                val rows = RoomArchiveFamilies.readCustomCourses(db.customCourseDao(), accountIdsOf().distinct())
                if (rows.isEmpty()) null else RoomArchiveFamilies.customCoursesFile(rows).content
            }
            RoomArchiveFamilies.DOWNLOAD_TASKS_REL -> {
                val rows = RoomArchiveFamilies.readDownloadTasks(db.downloadTaskDao())
                if (rows.isEmpty()) null else RoomArchiveFamilies.downloadTasksFile(rows).content
            }
            RoomArchiveFamilies.JIAOCAI_SHELF_REL -> {
                val rows = RoomArchiveFamilies.readJiaocaiShelf(db.jiaocai1ShelfDao())
                if (rows.isEmpty()) null else RoomArchiveFamilies.jiaocaiShelfFile(rows).content
            }
            else -> null
        }
    }

    override fun write(relPath: String, content: String) = runBlocking {
        when (relPath) {
            RoomArchiveFamilies.CUSTOM_COURSES_REL ->
                RoomArchiveFamilies.customCoursesFrom(content).forEach { db.customCourseDao().insert(it) }
            RoomArchiveFamilies.DOWNLOAD_TASKS_REL ->
                RoomArchiveFamilies.downloadTasksFrom(content).forEach { db.downloadTaskDao().insert(it) }
            RoomArchiveFamilies.JIAOCAI_SHELF_REL ->
                RoomArchiveFamilies.jiaocaiShelfFrom(content).forEach { db.jiaocai1ShelfDao().upsert(it) }
        }
    }
}
