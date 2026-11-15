package com.xjtu.toolbox.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Properties

/**
 * 单实例闸门（B-13）：同一个数据根（`keyValueStoreRoot()`）只让**一个进程**写。
 *
 * 为什么要有它：[JvmFileKeyValueStore] 与 `:data` 的 `FileKeyValueStore` 都是「init 时整份读进内存、
 * 每次改动整份回写」。**两个进程**同时开（桌面窗口 + `:server` 的 serve 模式，或者两个桌面窗口）就会
 * 互相覆盖：后写的把先写的那几个 key 整段抹掉（不是合并、是丢）。所以启动时先抢 `<数据根>/.lock`：
 *
 * - 抢到 ⇒ [isOwner] `true`，正常读写；
 * - 抢不到 ⇒ 这一端按**只读**跑（[StoreWriteGate] 一上，所有键值存储的 `persist()` 直接不落盘），
 *   并把「另一个实例正在运行」如实摆在用户面前（桌面画一条横幅，serve 打一行提示）。
 *
 * **僵尸锁**：进程被 `kill -9` / 断电之后锁文件会留在盘上，但那个 pid 已经不在了。所以拿到锁文件之后
 * 一定要问一句「这个 pid 还活着吗」（[isProcessAlive]）：不活了就把锁当无主的抢过来，而不是让用户
 * 永久卡在只读里（否则唯一出路是手动删 `.lock` —— 那种「自己想办法」的报错不算交代）。
 *
 * 抢锁本身是 `CREATE_NEW`（原子建），因此两个进程同时起步时只有一个能落笔；抢完之后还要再读一遍，
 * 读到的不是自己的 pid 就说明这一轮输了（[readOnly]）—— 不重试到底，绝不在没抢到的前提下当写者。
 */
class InstanceLock private constructor(
    /** 锁文件，`<数据根>/.lock`。 */
    val file: File,
    /** 锁上记着的持有者 pid（自己抢到时就是自己）。 */
    val ownerPid: Long,
    /** 锁上记着的持有者身份（`desktop` / `serve`），读不到就是 `null`。 */
    val ownerRole: String?,
    /** 持有者是什么时候抢到的（epoch ms），读不到就是 `0`。 */
    val ownerSinceMs: Long,
    /**
     * `null` ⇒ 抢到了，本进程是写者。
     * 非 `null` ⇒ 本进程**只读**，值是那句人话（「谁在上面、该怎么办」）。
     */
    val readOnlyReason: String?,
) {

    /** 本进程是否持有这把锁（持有者才允许落盘，见 [StoreWriteGate]）。 */
    val isOwner: Boolean get() = readOnlyReason == null

    /**
     * 退出时放锁。不是持有者就什么都不做（**绝不能删别人的锁**：删了第三个进程就会以为没人写）。
     *
     * 只删「锁上还是自己的 pid」的那一份 —— 万一中途被别人抢过（僵尸判定的竞态），也不替它清场。
     */
    fun release() {
        if (!isOwner) return
        if (read(file)?.pid != ownerPid) return
        runCatching { file.delete() }
    }

    override fun toString(): String =
        if (isOwner) "InstanceLock(owner pid=$ownerPid @${file.path})" else "InstanceLock(read-only: $readOnlyReason)"

    companion object {

        /** 锁文件名（放在数据根下，与那些 `*.properties` 同一个目录 —— 一眼看得见、删得掉）。 */
        const val LOCK_NAME = ".lock"

        private const val KEY_PID = "pid"
        private const val KEY_ROLE = "role"
        private const val KEY_SINCE = "sinceMs"

        /** 抢锁时最多试几次（每次之间没有等待：两次都输就是真输了，直接按只读跑）。 */
        private const val ATTEMPTS = 3

        /**
         * 抢 [root] 上的那把锁。
         *
         * @param role 本进程的身份，写进锁文件里给人看（`desktop` / `serve`）。
         * @param root 数据根，默认就是键值存储那个根（`keyValueStoreRoot()`）。
         * @param alive 判断某个 pid 还在不在（测试注入；真机上就是 `ProcessHandle`）。
         */
        fun acquire(
            role: String,
            root: File = keyValueStoreRoot(),
            selfPid: Long = ProcessHandle.current().pid(),
            alive: (Long) -> Boolean = ::isProcessAlive,
        ): InstanceLock {
            runCatching { root.mkdirs() }
            val file = File(root, LOCK_NAME)
            repeat(ATTEMPTS) {
                val existing = read(file)
                // 1) 有主且活着（**自己 pid 永远算活着**：同进程里第二次 acquire 也只能只读）⇒ 让位。
                //    这里不看 alive 谓词：pid 不可见的容器里 alive(selfPid) 可能为假，
                //    那时把自己那把锁当僵尸抢掉 = 双写（B-13 要防的正是这个）。
                if (existing != null && (existing.pid == selfPid || alive(existing.pid))) {
                    return readOnly(file, existing, role)
                }
                // 2) 没有锁文件 / 僵尸（pid 不在了）⇒ 抢过来。删掉是僵尸判定的后果，不是「覆盖别人的」。
                if (existing != null) runCatching { file.delete() }
                // 3) 原子建：赢了才是写者，输了（有人正同时起步）再看一眼谁在上面
                if (tryCreate(file, role, selfPid)) {
                    return InstanceLock(file, selfPid, role, System.currentTimeMillis(), readOnlyReason = null)
                }
            }
            val winner = read(file)
            return if (winner == null) {
                // 明明是空目录却怎么都建不出来（只读文件系统 / 权限）⇒ 仍然按只读跑，并说清是为什么
                InstanceLock(
                    file = file,
                    ownerPid = selfPid,
                    ownerRole = null,
                    ownerSinceMs = 0L,
                    readOnlyReason = "写不了 ${file.path}（只读文件系统或没有权限）—— 本进程按只读跑：改了也不会存。",
                )
            } else {
                readOnly(file, winner, role)
            }
        }

        /** 只是看一眼锁上是谁（不抢、不写）。诊断 / 测试用。 */
        fun peek(root: File = keyValueStoreRoot()): InstanceLock? =
            read(File(root, LOCK_NAME))?.let { owner ->
                InstanceLock(File(root, LOCK_NAME), owner.pid, owner.role, owner.sinceMs, "(peek)")
            }

        private fun readOnly(file: File, owner: LockOwner, role: String): InstanceLock = InstanceLock(
            file = file,
            ownerPid = owner.pid,
            ownerRole = owner.role,
            ownerSinceMs = owner.sinceMs,
            readOnlyReason = owner.describe(role),
        )

        private fun tryCreate(file: File, role: String, pid: Long): Boolean {
            val text = Properties().apply {
                setProperty(KEY_PID, pid.toString())
                setProperty(KEY_ROLE, role)
                setProperty(KEY_SINCE, System.currentTimeMillis().toString())
            }
            return try {
                // CREATE_NEW：文件已存在就抛 —— 这就是「原子抢到」，不需要额外的锁
                FileOutputStream(Files.createFile(file.toPath()).toFile()).use { text.store(it, null) }
                true
            } catch (e: FileAlreadyExistsException) {
                false
            } catch (e: java.io.IOException) {
                false
            }
        }

        private fun read(file: File): LockOwner? {
            if (!file.isFile) return null
            val props = Properties()
            runCatching { file.inputStream().use { props.load(it) } }.onFailure { return null }
            val pid = props.getProperty(KEY_PID)?.toLongOrNull() ?: return null
            return LockOwner(
                pid = pid,
                role = props.getProperty(KEY_ROLE)?.takeIf { it.isNotBlank() },
                sinceMs = props.getProperty(KEY_SINCE)?.toLongOrNull() ?: 0L,
            )
        }

        private fun LockOwner.describe(role: String): String {
            val who = buildString {
                append("pid ").append(pid)
                this@describe.role?.let { append("，").append(it) }
                if (sinceMs > 0) append("，").append(startedAt(sinceMs))
            }
            return "另一个实例正在运行（$who）：同一个数据根只允许一个写者，" +
                "所以这一端（$role）按只读跑 —— 在这里改了也不会存。" +
                "要写就先关掉那一个再启动；确认它已经不在（进程被 kill 过）而锁还在，删掉 ${LOCK_NAME} 再启动。"
        }

        private fun startedAt(epochMs: Long): String = runCatching {
            val at = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
            "启动于 " + at.toLocalDateTime().toString().replace('T', ' ')
        }.getOrDefault("启动时间未知")
    }

    private class LockOwner(val pid: Long, val role: String?, val sinceMs: Long)
}

/** `pid` 还在不在：`ProcessHandle` 在 JDK 9+ 上会顺手排掉僵尸（已退出未回收的也算不在）。 */
internal fun isProcessAlive(pid: Long): Boolean {
    if (pid <= 0L) return false
    return runCatching {
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }.getOrDefault(false)
}

/**
 * 进程级写闸：拿不到单实例锁就把 [readOnlyReason] 设上，之后**所有**键值存储的 `persist()` 都不落盘。
 *
 * 为什么是全局一格而不是给每个 store 传一个只读标志：一个进程只有一种身份（要么是写者、要么是只读
 * 观察者），而写者之间才需要区分 —— 没有「这个 store 可写、那个不可写」这种情形。放在这里还有个好处：
 * `:data` 的 `FileKeyValueStore` 与 `:shared:model` 的 `JvmFileKeyValueStore` 是两份各自独立的实现，
 * 一个全局开关才能同时管住它们（两边都会问一句 [allowsWrites]）。
 */
object StoreWriteGate {

    /** `null` = 可以写；非 `null` = 只读，值是那句人话（谁在上面）。 */
    @Volatile
    var readOnlyReason: String? = null
        private set

    /** 还能不能落盘。**每个 `persist()` 开口第一句就问它。** */
    val allowsWrites: Boolean get() = readOnlyReason == null

    /** 转只读（只该调一次，在启动抢锁失败处）。 */
    fun goReadOnly(reason: String) {
        readOnlyReason = reason
    }

    /** 仅测试用：把闸复位（否则一个测试的只读会漏给后面的测试）。 */
    internal fun resetForTest() {
        readOnlyReason = null
    }
}

