package com.xjtu.toolbox.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** 桌面端的密文存储落点。 */
@Volatile
var dataRootOverride: File? = null

/** 本端数据根目录。 */
internal fun dataRoot(): File =
    dataRootOverride ?: File(System.getProperty("user.home") ?: "/tmp", ".local/share/xjtu-toolbox")

/** 数据根目录的对外形态：宿主拿它下面的子目录。 */
fun dataRootDir(): File = dataRoot()

private val stores = ConcurrentHashMap<String, KeyValueStore>()

/** 文件版键值存储：一份 `java.util.Properties` 文件，权限 `0600`。 */
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

// 桌面没有「老明文文件迁进来」那一步（[legacyFallbackName] 只对 Android 有意义），参数收下不用。
actual fun secureKeyValueStore(name: String, legacyFallbackName: String?): KeyValueStore = stores.computeIfAbsent(name) {
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
