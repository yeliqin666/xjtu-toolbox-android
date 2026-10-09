package com.xjtu.toolbox.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面端的密文存储落点。
 *
 * 放哪儿：`~/.local/share/xjtu-toolbox/`（XDG 的口径；Windows 上会落到用户主目录下同名目录 ——
 * 真正按平台分目录是打包形态定下来之后的事，见 `docs/desktop-port-plan.md` §7）。
 *
 * 测试与多实例场景可以改 [dataRootOverride]（`LibraryLoginSessionJvmTest` 就是这么隔离的）。
 */
@Volatile
var dataRootOverride: File? = null

/** 本端数据根目录。 */
internal fun dataRoot(): File =
    dataRootOverride ?: File(System.getProperty("user.home") ?: "/tmp", ".local/share/xjtu-toolbox")

private val stores = ConcurrentHashMap<String, KeyValueStore>()

/**
 * 文件版键值存储：一份 `java.util.Properties` 文件，权限 `0600`。
 *
 * 为什么是 Properties 而不是自己拼 JSON：cookies 与站点快照的值是任意字符串（引号、换行、
 * `=`、非 ASCII 都有），`Properties.store/load` 自带转义，是唯一不用手写转义规则的选择。
 *
 * 写入是**同步**的（`putString` 返回时已经落盘）。`SiteSnapshots` 与 `PersistentCookieJar`
 * 都自带「排在专用线程上」的调度，同步写只是把它们的后台线程变成阻塞点，不影响任何调用点
 * 的语义；反而让「清凭据 / 删账号」这类操作不再有「写完就退出、防抖没来得及落盘」的窗口。
 */
private class FileKeyValueStore(private val file: File) : KeyValueStore {

    private val lock = Any()
    private val props = Properties()

    init {
        synchronized(lock) {
            if (file.exists()) {
                runCatching { file.inputStream().use { props.load(it) } }
            }
        }
    }

    private fun persist() {
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        FileOutputStream(file).use { props.store(it, null) }
        // 与「同一批文件的口径」对齐：凭据 / cookie / 快照都只给本用户读。
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    override fun getString(key: String): String? = synchronized(lock) { props.getProperty(key) }

    override fun putString(key: String, value: String?) = synchronized(lock) {
        // 与 SharedPreferences 语义一致：写 null 等于删键
        if (value == null) props.remove(key) else props.setProperty(key, value)
        persist()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { props.getProperty(key) }?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = synchronized(lock) {
        props.setProperty(key, value.toString())
        persist()
    }

    override fun getInt(key: String, default: Int): Int =
        synchronized(lock) { props.getProperty(key) }?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) = synchronized(lock) {
        props.setProperty(key, value.toString())
        persist()
    }

    /** 集合档：JSON 数组形状（只为自洽往返，会话内核本身不用集合）。 */
    override fun getStringSet(key: String): Set<String>? =
        synchronized(lock) { props.getProperty(key) }?.let { raw ->
            raw.split('\u0000').filter { it.isNotEmpty() }.toSet()
        }

    override fun putStringSet(key: String, value: Set<String>) = synchronized(lock) {
        props.setProperty(key, value.joinToString("\u0000"))
        persist()
    }

    override fun remove(key: String) = synchronized(lock) {
        props.remove(key)
        persist()
    }

    override fun contains(key: String): Boolean = synchronized(lock) { props.containsKey(key) }

    override fun clear() = synchronized(lock) {
        props.clear()
        persist()
    }
}

actual fun secureKeyValueStore(name: String): KeyValueStore = stores.computeIfAbsent(name) {
    FileKeyValueStore(File(dataRoot(), "$name.properties"))
}

actual fun wipeSecureStore(name: String) {
    stores.remove(name)
    File(dataRoot(), "$name.properties").delete()
}

actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000

/** 桌面：用操作系统名 + 架构 + 当前用户拼一个「同机同值」的稳定种子。 */
actual fun stableDeviceFingerprintFields(): List<String> = listOf(
    "desktop",
    System.getProperty("os.name") ?: "",
    System.getProperty("os.version") ?: "",
    System.getProperty("os.arch") ?: "",
    System.getProperty("user.name") ?: "",
)
