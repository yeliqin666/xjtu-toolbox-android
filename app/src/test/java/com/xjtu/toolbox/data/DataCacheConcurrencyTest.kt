package com.xjtu.toolbox.data

import android.app.Application
import com.xjtu.toolbox.account.AccountContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    private val app = RuntimeEnvironment.getApplication()
    private val account = "cache-concurrency-test"
    private val dir = File(app.cacheDir, "data_cache" + AccountContext.suffixFor(account))

    @Test
    fun allInstancesAndClearUseTheDirectoryLock() {
        val first = DataCache(app, account)
        val second = DataCache(app, account)
        first.put("key", "old")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val pending = synchronized(DataCache.directoryLock(dir)) {
                val task = pool.submit {
                    entered.countDown()
                    second.clearAll()
                    first.put("key", "new")
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertThrows(java.util.concurrent.TimeoutException::class.java) {
                    task.get(200, TimeUnit.MILLISECONDS)
                }
                assertEquals("old", first.getStale("key"))
                task
            }
            pending.get(5, TimeUnit.SECONDS)
            assertEquals("new", second.getStale("key"))
        } finally {
            pool.shutdownNow()
            first.clearAll()
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
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            DataCache.deleteDirectory(dir)
        }
    }
}
