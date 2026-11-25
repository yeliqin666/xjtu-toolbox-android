package com.xjtu.toolbox.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

@Volatile
var keyValueStoreRootOverride: File? = null

/** 本端普通键值存储的根目录。 */
fun keyValueStoreRoot(): File =
    keyValueStoreRootOverride
        ?: System.getProperty("xjtu.toolbox.keyValueRoot")?.let { File(it) }
        ?: File(System.getProperty("user.home") ?: "/tmp", ".local/share/xjtu-dzhz")

private val stores = ConcurrentHashMap<File, JvmFileKeyValueStore>()

/** 普通和安全存储工厂共用同一个文件实例；以实际路径为键，而非工厂名。 */
fun jvmFileKeyValueStore(file: File): KeyValueStore = storeFor(file)

private fun storeFor(file: File): JvmFileKeyValueStore {
    val canonical = file.canonicalFile
    return stores.computeIfAbsent(canonical) { JvmFileKeyValueStore(it) }
}

/** 保留现有句柄的同一把锁和内存状态，避免删除后重新打开产生第二个快照。 */
fun wipeJvmFileKeyValueStore(file: File) = storeFor(file).wipe()

/** Properties 文件格式不变。凭据保护由上层包装器负责。 */
internal class JvmFileKeyValueStore(private val file: File) : KeyValueStore {
    private val lock = Any()
    private var props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    private fun persist(next: Properties) {
        if (!StoreWriteGate.allowsWrites) return
        val target = file.toPath().toAbsolutePath()
        val dir = target.parent
        Files.createDirectories(dir)
        val temp = if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            Files.createTempFile(dir, ".store-", ".tmp", PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
        } else {
            Files.createTempFile(dir, ".store-", ".tmp")
        }
        try {
            FileOutputStream(temp.toFile()).use {
                next.store(it, null)
                it.fd.sync()
            }
            // 原子替换不可用时直接失败，不回退到截断旧文件。
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun mutate(block: (Properties) -> Unit) = synchronized(lock) {
        val next = Properties().apply { putAll(props) }
        block(next)
        persist(next)
        // 写入失败不发布新快照；只读模式维持原来的内存编辑语义。
        props = next
    }

    override fun getString(key: String): String? = synchronized(lock) { props.getProperty(key) }

    override fun putString(key: String, value: String?) = mutate {
        if (value == null) it.remove(key) else it.setProperty(key, value)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    override fun getInt(key: String, default: Int): Int =
        getString(key)?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    override fun getStringSet(key: String): Set<String>? =
        getString(key)?.split('\u0000')?.filter { it.isNotEmpty() }?.toSet()

    override fun putStringSet(key: String, value: Set<String>) =
        putString(key, value.joinToString("\u0000"))

    override fun remove(key: String) = mutate { it.remove(key) }

    override fun contains(key: String): Boolean = synchronized(lock) { props.containsKey(key) }

    override fun keys(): Set<String> = synchronized(lock) { props.stringPropertyNames().toSet() }

    override fun clear() = mutate { it.clear() }

    internal fun wipe() = synchronized(lock) {
        if (StoreWriteGate.allowsWrites) Files.deleteIfExists(file.toPath())
        props.clear()
    }
}

actual fun keyValueStore(name: String): KeyValueStore =
    jvmFileKeyValueStore(File(keyValueStoreRoot(), "$name.properties"))

/** 仅测试用：直接从磁盘读取，不经过生产实例缓存。 */
internal fun newJvmFileKeyValueStore(name: String): KeyValueStore =
    JvmFileKeyValueStore(File(keyValueStoreRoot(), "$name.properties"))
