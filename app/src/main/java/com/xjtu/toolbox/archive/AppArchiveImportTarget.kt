package com.xjtu.toolbox.archive

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.account.AccountContext
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
    /**
     * `accounts/<suffix>` → 学号（= 账号 id，`AppLoginState` 的 `activeUsername`）：
     * 键名归一要用它把端内原名拼回来（[ArchiveScoreReport]）。
     */
    private val studentIdOf: (String, String?) -> String? = { suffix, accountId -> defaultStudentIdOf(suffix, accountId) },
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

    /**
     * 键名归一（§6.3）：档案里那一族的名字**不含学号**，端内原名（`score_report_<学号>.json`）在这里拼回去。
     *
     * 档案里那一档账号先查（[ArchiveImportTarget.fileRelPath] 的 `accountId`）；档案里没有（`global/` /
     * `default` / `_`）时用本机当前登录的账号。两边都拼不出 ⇒ null（这一条跳过并计数，绝不抛）。
     */
    override fun fileRelPath(suffix: String, accountId: String?, relPath: String): String? {
        if (!ArchiveScoreReport.isFamily(relPath)) return relPath
        val studentId = studentIdOf(suffix, accountId) ?: return null
        return ArchiveScoreReport.endRelPath(studentId)
    }

    /**
     * 形状归一（§6.3）：档案里是 `{"at","grades"}`，`:app` 端内是**裸数组**（年龄交给文件 mtime）。
     * 转不动 ⇒ null（跳过并计数；端内一个字节不改）。
     */
    override fun fileContent(suffix: String, accountId: String?, relPath: String, content: String): String? =
        if (!ArchiveScoreReport.isFamily(relPath)) content else ArchiveScoreReport.toBareGrades(content)

    /**
     * 删键（镜像档，§6.4）：Android 的 store 就是一份 SharedPreferences，`remove` + `commit` 就是删掉。
     *
     * 红线清单（[ArchiveRedLines]）上的店在共享层就被挡下了，走不到这里。
     */
    override fun removeKey(rawName: String, key: String): Boolean =
        runCatching { prefsOf(rawName).edit().remove(key).commit() }.getOrDefault(false)

    /** 镜像档：只枚举真文件的那两族（`agent_sessions/` / `schedule/`），Room 三族保守不枚举。 */
    override fun listFiles(suffix: String, family: String): List<String> =
        files?.list(suffix, family) ?: emptyList()

    override fun removeFile(suffix: String, relPath: String): Boolean = files?.delete(suffix, relPath) ?: false

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

        /** 档案里的账号 → 学号；档案里没有那一档时用本机当前账号（本机也没登录 ⇒ null）。 */
        private fun defaultStudentIdOf(suffix: String, accountId: String?): String? =
            ArchiveScoreReport.studentIdOf(suffix, accountId)
                ?: runCatching { AccountContext.activeAccountId }.getOrNull()?.takeIf { it.isNotBlank() }


        /** 端内的账号列表（`AccountStore` 的既有读法）；取不到就当没有（不抛）。 */
        private fun localAccountIds(): List<String> =
            runCatching { AccountStore().list().map { it.accountId } }.getOrDefault(emptyList())
    }
}

/** 端内 `files/` 那一层的写侧（§8.8）。 */
interface AppArchiveFileWriter {
    /**
     * 镜像档（§6.4）：这一族在本端**真有的**相对路径。默认空 = 这一端不可枚举 ⇒ 这一族一个都不删
     * （保守口径：枚举不出来就是“拿不准”，拿不准就不删）。
     */
    fun list(suffix: String, family: String): List<String> = emptyList()

    /** 镜像档（§6.4）：删掉一条。默认 `false` = 这一端不删。 */
    fun delete(suffix: String, relPath: String): Boolean = false
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

    /**
     * 镜像档只能枚举**真文件**的两族：`agent_sessions/` 与课表缓存 `schedule/`。
     *
     * Room 三族的「多余条目」等价于删库里的行（还要区分别人的账号），口径没定死前一律不枚举 ⇒ 那三族不删。
     * 子目录一律不返回：少列一条只是少删一条，拿不准就不删。
     */
    override fun list(suffix: String, family: String): List<String> = when (family) {
        AGENT_SESSIONS_FAMILY -> entriesOf(agentSessionsDir(suffix), AGENT_SESSIONS_PREFIX)
        SCHEDULE_FAMILY -> scheduleDir(suffix)?.let { entriesOf(it, SCHEDULE_PREFIX) } ?: emptyList()
        else -> emptyList()
    }

    override fun delete(suffix: String, relPath: String): Boolean = runCatching {
        when {
            relPath.startsWith(AGENT_SESSIONS_PREFIX) ->
                File(agentSessionsDir(suffix), relPath.removePrefix(AGENT_SESSIONS_PREFIX)).delete()
            relPath.startsWith(SCHEDULE_PREFIX) ->
                scheduleDir(suffix)
                    ?.let { File(it, relPath.removePrefix(SCHEDULE_PREFIX)).delete() }
                    ?: false
            else -> false
        }
    }.getOrDefault(false)

    /** 一层目录里的真文件 → 端内相对路径（子目录跳过）。 */
    private fun entriesOf(dir: File, prefix: String): List<String> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .map { prefix + it.name }
            .sorted()

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

        /** 镜像档枚举用的族名（= 相对路径里的第一段）。 */
        const val AGENT_SESSIONS_FAMILY = "agent_sessions"
        const val SCHEDULE_FAMILY = "schedule"
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
