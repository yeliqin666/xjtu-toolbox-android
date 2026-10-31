package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 单实例闸门（B-13）的守卫：抢、让位、**僵尸锁**、以及只读时真的一个字都不落盘。
 *
 * 「另一个进程」在这里用 `alive` 注入模拟（真起第二个 JVM 太慢、也没法在单测里保证平台一致）——
 * 判僵尸的唯一依据就是「锁上那个 pid 还活着吗」，所以注入这一个函数就够把两条岔路都走到。
 */
class InstanceLockJvmTest {

    private fun tempRoot(): File = Files.createTempDirectory("xjtu-lock-root").toFile()

    private fun withGateReset(block: () -> Unit) {
        StoreWriteGate.resetForTest()
        try {
            block()
        } finally {
            StoreWriteGate.resetForTest()
        }
    }

    @Test
    fun `空目录上抢得到，锁里写着自己的 pid 和身份，release 之后文件消失`() {
        val root = tempRoot()
        val lock = InstanceLock.acquire("desktop", root = root, selfPid = 4242L)

        assertTrue(lock.isOwner)
        assertNull(lock.readOnlyReason)
        val file = File(root, InstanceLock.LOCK_NAME)
        assertTrue(file.isFile, "锁文件该落在数据根下：${file.path}")

        val seen = InstanceLock.peek(root)
        assertNotNull(seen)
        assertEquals(4242L, seen.ownerPid)
        assertEquals("desktop", seen.ownerRole)

        lock.release()
        assertFalse(file.exists(), "release 之后锁文件该没了（否则下一次启动会以为有人在写）")
    }

    @Test
    fun `持有者还活着 ⇒ 第二个实例只读，而且能把谁在上面说清楚`() {
        val root = tempRoot()
        val first = InstanceLock.acquire("desktop", root = root, selfPid = 4242L)
        assertTrue(first.isOwner)

        // 第二个实例：pid 不同，4242「还活着」
        val second = InstanceLock.acquire("serve", root = root, selfPid = 5151L, alive = { it == 4242L })
        assertFalse(second.isOwner, "锁被别人拿着时绝不能当写者")
        assertEquals(4242L, second.ownerPid)
        val reason = second.readOnlyReason
        assertNotNull(reason)
        assertTrue(reason.contains("另一个实例正在运行"), reason)
        assertTrue(reason.contains("pid 4242"), "得说清是谁在上面：$reason")
        assertTrue(reason.contains("desktop"), "得说清那一份是什么身份：$reason")

        // 只读那一份 release 不能删别人的锁
        second.release()
        assertTrue(File(root, InstanceLock.LOCK_NAME).isFile, "只读方不许把持有者的锁删掉")

        first.release()
        assertFalse(File(root, InstanceLock.LOCK_NAME).exists())
    }

    @Test
    fun `僵尸锁（pid 已经不在了）要抢过来，而不是让人永久只读`() {
        val root = tempRoot()
        val stale = InstanceLock.acquire("desktop", root = root, selfPid = 999_999L)
        assertTrue(stale.isOwner)

        // 模拟「那个进程被 kill -9 了」：pid 999999 不再活着
        val next = InstanceLock.acquire("serve", root = root, selfPid = 4242L, alive = { it != 999_999L })

        assertTrue(next.isOwner, "pid 不在了就是无主的锁，必须抢过来（否则唯一出路是手动删 .lock）")
        assertEquals(4242L, InstanceLock.peek(root)?.ownerPid)
        assertEquals("serve", InstanceLock.peek(root)?.ownerRole)
    }

    @Test
    fun `同进程再抢一次也只读 —— 一个进程只有一把钥匙`() {
        val root = tempRoot()
        val first = InstanceLock.acquire("desktop", root = root, selfPid = 4242L)
        assertTrue(first.isOwner)
        val again = InstanceLock.acquire("desktop", root = root, selfPid = 4242L)
        assertFalse(again.isOwner, "自己 pid 也算「活着」：再抢一次必须是只读，不能把锁当成僵尸")
        first.release()
    }

    @Test
    fun `只读闸一合上，键值存储一个字都不落盘（这才是 B-13 要防的那件事）`() = withGateReset {
        val root = tempRoot()
        keyValueStoreRootOverride = root
        try {
            val store = keyValueStore("gate")
            store.putString("k", "v1")
            assertTrue(File(root, "gate.properties").isFile, "可写时正常落盘")

            StoreWriteGate.goReadOnly("另一个实例正在运行（pid 4242）")
            store.putString("k", "v2")
            store.remove("k")
            store.putInt("i", 7)

            // 内存里是改过的（读得到），盘上必须还是原来那份 —— 只读进程绝不能覆盖写者的文件
            assertEquals("v1", newJvmFileKeyValueStore("gate").getString("k"))
            assertEquals(0, newJvmFileKeyValueStore("gate").getInt("i", 0))
            assertNull(store.getString("k"), "内存里是删过的：只读只挡落盘，不挡内存中的这次改动")
        } finally {
            keyValueStoreRootOverride = null
        }
    }
}
