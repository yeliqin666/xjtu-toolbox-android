package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 自建待办存储在多个线程同时改时不能丢条目。 */
class SelfTodoStoreJvmTest {

    @Test
    fun `多个线程同时加待办，一条都不丢`() {
        val root = Files.createTempDirectory("xjtu-selftodo").toFile()
        keyValueStoreRootOverride = root
        val account = "concurrent-test"
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    repeat(perThread) { i -> SelfTodoStore.create(title = "t$t-$i", account = account) }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            val titles = SelfTodoStore.all(account).map { it.title }
            assertEquals(threads * perThread, titles.size)
            assertEquals(threads * perThread, titles.toSet().size)
        } finally {
            pool.shutdownNow()
            keyValueStoreRootOverride = null
        }
    }

    @Test
    fun `一边完成一边新增，完成的那条和新加的都在`() {
        val root = Files.createTempDirectory("xjtu-selftodo").toFile()
        keyValueStoreRootOverride = root
        val account = "complete-vs-create"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val seeded = (0 until 50).map { SelfTodoStore.create(title = "seed$it", account = account)!!.id }
            val start = CountDownLatch(1)
            pool.execute { start.await(); seeded.forEach { SelfTodoStore.complete(it, account) } }
            pool.execute { start.await(); repeat(50) { SelfTodoStore.create(title = "new$it", account = account) } }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            val all = SelfTodoStore.all(account)
            assertEquals(100, all.size)
            assertTrue(all.filter { it.title.startsWith("seed") }.all { it.done })
        } finally {
            pool.shutdownNow()
            keyValueStoreRootOverride = null
        }
    }
}