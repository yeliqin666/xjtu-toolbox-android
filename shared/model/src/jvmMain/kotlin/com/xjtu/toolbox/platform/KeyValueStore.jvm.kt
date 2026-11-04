package com.xjtu.toolbox.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** 桌面端的普通键值存储落点（与 `:data` 里 `SecureStore.jvm.kt` 那份文件实现同一套形状）。 */
@Volatile
var keyValueStoreRootOverride: File? = null

/** 本端普通键值存储的根目录。解析顺序：显式 override ?: 系统属性 ?: 默认字面量（与密文存储同根）。 */
fun keyValueStoreRoot(): File =
    keyValueStoreRootOverride
        ?: System.getProperty("xjtu.toolbox.keyValueRoot")?.let { File(it) }
        ?: File(System.getProperty("user.home") ?: "/tmp", ".local/share/xjtu-toolbox")

private val stores = ConcurrentHashMap<String, KeyValueStore>()

/** 文件版键值存储：一份 `java.util.Properties` 文件，权限 `0600`。 */
internal class JvmFileKeyValueStore(private val file: File) : KeyValueStore {

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

    /** 集合档：`\u0000` join（与密文存储同一套落地形状）。 */
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

    override fun keys(): Set<String> = synchronized(lock) { props.stringPropertyNames().toSet() }

    override fun clear() = synchronized(lock) {
        props.clear()
        persist()
    }
}

/** 建一个具名存储。同一 `name` 多次调用返回同一份实例（进程内 memo），同一根目录下同一份文件。 */
actual fun keyValueStore(name: String): KeyValueStore = stores.computeIfAbsent(name) {
    JvmFileKeyValueStore(File(keyValueStoreRoot(), "$name.properties"))
}

/** 不查 memo 直接开一个文件实例（测试「第二实例从磁盘读回」用）。 */
internal fun newJvmFileKeyValueStore(name: String): KeyValueStore =
    JvmFileKeyValueStore(File(keyValueStoreRoot(), "$name.properties"))
