package com.xjtu.toolbox.archive

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.data.AppDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/** `:app` 侧的导入写入缝：`:core` 的 [ArchiveImportTarget] 在 Android 上的落点。 */
class AppArchiveImportTarget(
    /** 写侧：按端内名取已有的那个 prefs 句柄。 */
    private val prefsOf: (String) -> SharedPreferences,
    /** 读侧现状（`archiveStoreBackend()`）。 */
    private val storeBackend: ArchiveStoreBackend,
    /** `files/` 那一层的写侧（Room 行数组 + 两族真文件）。 */
    private val files: AppArchiveFileWriter?,
) : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = storeBackend.readStore(rawName)

    /** 把一个键写进端内 store：类型照 [StoreValue] 的标签（§8.6）。 */
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
        /** 默认装配：`Context` 的 prefs + `:core` 的 store 投影 + [AndroidArchiveFileWriter] （Room 三表现状按 [AccountStore] 的账号列表 + 档案里的账号列表枚举）。 */
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

/** 端内 `files/` 那一层的写侧（§8.8）。 */
interface AppArchiveFileWriter {

    /** 本端这一个 `files/` 相对路径现在的内容；**本端没有这一族 ⇒ null**（§6.4 文件层：本端有 ⇒ 保持不变）。 */
    fun read(suffix: String, relPath: String): String?

    /** 把一个 `files/` 条目写回端内（先写临时文件再换名，§8.8）。 */
    fun write(suffix: String, relPath: String, content: String)
}

/** Room 三张表那一半的写侧（`files/courses` / `files/media` / `files/jiaocai` 三条相对路径）。 */
interface RoomRowWriter {

    /** 这一个相对路径现在的行数组；空表 ⇒ null（本端没有这一族）。 */
    fun read(relPath: String): String?

    /** 把档案里的行数组写回三张表之一（只走既有 DAO 的既有 insert/upsert，§8.3）。 */
    fun write(relPath: String, content: String)
}

/** Android 的 `files/` 写侧：三条 Room 行数组相对路径走 [room]，两族真文件走文件系统。 */
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

/** Room 三张表的写侧实现：读走行适配器，写走既有 DAO 的 insert/upsert（§8.3）。 */
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
