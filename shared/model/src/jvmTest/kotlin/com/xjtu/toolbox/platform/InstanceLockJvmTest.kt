package com.xjtu.toolbox.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 文件锁的进程内与跨进程行为，以及只读模式。 */
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
    fun acquireAndRelease() {
        val root = tempRoot()
        val lock = InstanceLock.acquire("desktop", root)
        try {
            assertTrue(lock.isOwner)
            assertNull(lock.readOnlyReason)
            val seen = assertNotNull(InstanceLock.peek(root))
            assertEquals(ProcessHandle.current().pid(), seen.ownerPid)
            assertEquals("desktop", seen.ownerRole)
            lock.release()
            assertFalse(lock.isOwner)
            assertNull(InstanceLock.peek(root))
            assertTrue(File(root, ".lock.guard").isFile)
        } finally {
            lock.release()
            root.deleteRecursively()
        }
    }

    @Test
    fun secondInstanceIsReadOnly() {
        val root = tempRoot()
        val first = InstanceLock.acquire("desktop", root)
        try {
            assertTrue(first.isOwner)
            val second = InstanceLock.acquire("serve", root)
            assertFalse(second.isOwner)
            assertEquals(first.ownerPid, second.ownerPid)
            assertTrue(assertNotNull(second.readOnlyReason).contains("desktop"))
            second.release()
            assertTrue(first.isOwner)
            assertNotNull(InstanceLock.peek(root))
        } finally {
            first.release()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleMetadataDoesNotPreventAcquisition() {
        val root = tempRoot()
        File(root, InstanceLock.LOCK_NAME).writeText(
            "pid=${ProcessHandle.current().pid()}\nrole=desktop\nprotocol=2\n"
        )
        val lock = InstanceLock.acquire("serve", root)
        try {
            assertTrue(lock.isOwner)
            assertEquals("serve", InstanceLock.peek(root)?.ownerRole)
        } finally {
            lock.release()
            root.deleteRecursively()
        }
    }

    @Test
    fun separateJvmCannotAcquireUntilOwnerExits() {
        val root = tempRoot()
        val java = File(System.getProperty("java.home"), "bin/" +
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = listOf(InstanceLockProbe::class.java, InstanceLock::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(java.absolutePath, "-cp", classpath,
            InstanceLockProbe::class.java.name, root.absolutePath)
            .redirectErrorStream(true).start()
        try {
            val ready = File(root, "ready")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(ready.exists(), "child did not acquire the lock")
            val denied = InstanceLock.acquire("desktop", root)
            assertFalse(denied.isOwner)
            denied.release()
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            val next = InstanceLock.acquire("desktop", root)
            try {
                assertTrue(next.isOwner, "OS must release the lock after the owner is killed: ${next.readOnlyReason}")
            } finally {
                next.release()
            }
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(10, TimeUnit.SECONDS)
            }
            root.deleteRecursively()
        }
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

/** 独立 JVM 持有真实文件锁，父进程强制结束它来验证异常退出。 */
object InstanceLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.single())
        val lock = InstanceLock.acquire("probe", root)
        check(lock.isOwner)
        File(root, "ready").writeText("ready")
        Thread.sleep(Long.MAX_VALUE)
    }
}
