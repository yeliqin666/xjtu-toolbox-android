package com.xjtu.toolbox.data

import android.app.Application
import com.xjtu.toolbox.account.AccountContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DataCacheConcurrencyTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val account = "cache-concurrency-test"
    private val dir = File(app.cacheDir, "data_cache" + AccountContext.suffixFor(account))

    // 只借反射取得原有的锁，控制真实 clearAll() 的执行顺序。
    // 两个版本使用同一测试源码；反射失败不能算作复现成功。
    private fun lockForTest(cache: DataCache): Any {
        val field = DataCache::class.java.declaredFields.firstOrNull { it.name == "lock" }
        if (field != null) return field.apply { isAccessible = true }.get(cache)
        val method = DataCache::class.java.getDeclaredMethod("lockFor", String::class.java)
        return method.apply { isAccessible = true }.invoke(cache, "key")
    }

    @Test
    fun allInstancesAndClearUseTheDirectoryLock() {
        val first = DataCache(app, account)
        val second = DataCache(app, account)
        first.put("key", "old")
        val task = FutureTask<Unit> { second.clearAll() }
        val worker = Thread(task, "cache-clear-regression").apply { isDaemon = true }
        try {
            synchronized(lockForTest(first)) {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!task.isDone && System.nanoTime() < deadline) {
                    if (worker.state == Thread.State.BLOCKED &&
                        worker.stackTrace.any { it.className == DataCache::class.java.name &&
                            it.methodName == "clearAll" }) break
                    Thread.yield()
                }
                if (task.isDone) {
                    task.get() // 其他异常不能当成目标缺陷
                    fail("S1_SHARED_LOCK: another instance cleared while the first lock was held")
                }
                assertEquals("worker did not reach clearAll monitor", Thread.State.BLOCKED, worker.state)
                assertEquals("old", first.getStale("key"))
            }
            task.get(5, TimeUnit.SECONDS)
            assertNull(second.getStale("key"))
        } finally {
            worker.join(5000)
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritersNeverPublishPartialFiles() {
        val values = listOf("a".repeat(65536), "b".repeat(65536), "c".repeat(65536))
        val caches = values.map { DataCache(app, account) }
        caches.first().put("key", values.first())
        val pool = Executors.newFixedThreadPool(values.size)
        val start = CountDownLatch(1)
        try {
            val jobs = caches.mapIndexed { index, cache ->
                pool.submit {
                    start.await()
                    repeat(40) {
                        cache.put("key", values[index])
                        assertTrue(cache.getStale("key") in values)
                        assertTrue(File(dir, "key.json").readText() in values)
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            pool.shutdownNow()
            caches.first().clearAll()
        }
    }

    @Test
    fun failedReplacementDoesNotOverwriteTargetOrLeaveTempFile() {
        val cache = DataCache(app, account)
        val target = File(dir, "key.json").apply { mkdirs() }
        val sentinel = File(target, "sentinel").apply { writeText("old") }
        try {
            cache.put("key", "new")
            assertEquals("old", sentinel.readText())
            assertTrue("S1_TEMP_LEAK: failed replacement left a temporary file",
                dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
