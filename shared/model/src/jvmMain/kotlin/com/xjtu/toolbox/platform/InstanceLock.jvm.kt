package com.xjtu.toolbox.platform

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * 数据根目录的进程级写锁。操作系统负责互斥及进程退出后的释放。
 * .lock.guard 是永久锁文件，不能删除或替换；.lock 只保存诊断信息。
 */
class InstanceLock private constructor(
    val file: File,
    val ownerPid: Long,
    val ownerRole: String?,
    val ownerSinceMs: Long,
    val readOnlyReason: String?,
    private val channel: FileChannel? = null,
    private val fileLock: FileLock? = null,
) {
    val isOwner: Boolean get() = fileLock?.isValid == true

    fun release() = releaseOwner(this)

    companion object {
        const val LOCK_NAME = ".lock"
        private const val GUARD_NAME = ".lock.guard"
        private val owners = mutableMapOf<File, InstanceLock>()

        @Synchronized
        fun acquire(role: String, root: File = keyValueStoreRoot()): InstanceLock {
            val pid = ProcessHandle.current().pid()
            val file = try {
                File(root.canonicalFile, LOCK_NAME)
            } catch (e: IOException) {
                return unavailable(File(root, LOCK_NAME), role)
            }

            // 同 JVM 内不再打开第二个 guard 句柄：某些系统关闭任一
            // 同文件描述符都会释放该进程的文件锁。
            owners[file]?.let { owner ->
                return readOnly(file, LockOwner(owner.ownerPid, owner.ownerRole, owner.ownerSinceMs, true), role)
            }

            val guard = File(file.parentFile, GUARD_NAME)
            val channel = try {
                Files.createDirectories(file.parentFile.toPath())
                FileChannel.open(guard.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                return unavailable(file, role)
            }
            var acquired: FileLock? = null
            var keep = false
            try {
                acquired = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (acquired == null) return readOnly(file, read(file), role)

                // 兼容升级时尚未退出的旧版本。新协议不以 PID 判断锁归属。
                val previous = read(file)
                if (previous != null && !previous.kernelLock && isProcessAlive(previous.pid)) {
                    return readOnly(file, previous, role)
                }

                val since = System.currentTimeMillis()
                Properties().apply {
                    setProperty("pid", pid.toString())
                    setProperty("role", role)
                    setProperty("sinceMs", since.toString())
                    setProperty("protocol", "2")
                }.let { props -> file.outputStream().use { props.store(it, null) } }

                val owner = InstanceLock(file, pid, role, since, null, channel, acquired)
                owners[file] = owner
                keep = true
                return owner
            } catch (e: IOException) {
                return unavailable(file, role)
            } finally {
                if (!keep) {
                    try {
                        acquired?.release()
                    } finally {
                        channel.close()
                    }
                }
            }
        }

        @Synchronized
        private fun releaseOwner(owner: InstanceLock) {
            if (owners[owner.file] !== owner) return
            try {
                // 只在持有操作系统锁时清除诊断信息。
                runCatching { Files.deleteIfExists(owner.file.toPath()) }
            } finally {
                try {
                    owner.fileLock?.release()
                } finally {
                    try {
                        owner.channel?.close()
                    } finally {
                        owners.remove(owner.file)
                    }
                }
            }
        }

        /** 读取诊断信息，不判断实际锁归属；异常退出后信息可能过期。 */
        fun peek(root: File = keyValueStoreRoot()): InstanceLock? =
            read(File(root, LOCK_NAME))?.let { owner ->
                InstanceLock(File(root, LOCK_NAME), owner.pid, owner.role, owner.sinceMs, "(peek)")
            }

        private fun readOnly(file: File, owner: LockOwner?, role: String): InstanceLock {
            val who = owner?.let { "（pid ${it.pid}${it.role?.let { r -> "，$r" }.orEmpty()}）" }.orEmpty()
            return InstanceLock(
                file, owner?.pid ?: -1L, owner?.role, owner?.sinceMs ?: 0L,
                "另一个实例正在运行$who：当前 $role 实例按只读运行。关闭其他实例后重新启动即可写入。",
            )
        }

        private fun unavailable(file: File, role: String) = InstanceLock(
            file, -1L, null, 0L,
            "无法取得 ${file.path} 的写锁：当前 $role 实例按只读运行。",
        )

        private fun read(file: File): LockOwner? = runCatching {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            LockOwner(
                props.getProperty("pid")?.toLongOrNull() ?: return null,
                props.getProperty("role"),
                props.getProperty("sinceMs")?.toLongOrNull() ?: 0L,
                props.getProperty("protocol") == "2",
            )
        }.getOrNull()

        private data class LockOwner(val pid: Long, val role: String?, val sinceMs: Long, val kernelLock: Boolean)
    }
}

internal fun isProcessAlive(pid: Long): Boolean =
    pid > 0 && runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)

/** 进程级只读状态。所有 JVM 键值存储写入均受此限制。 */
object StoreWriteGate {
    @Volatile
    var readOnlyReason: String? = null
        private set

    val allowsWrites: Boolean get() = readOnlyReason == null

    fun goReadOnly(reason: String) {
        readOnlyReason = reason
    }

    internal fun resetForTest() {
        readOnlyReason = null
    }
}
