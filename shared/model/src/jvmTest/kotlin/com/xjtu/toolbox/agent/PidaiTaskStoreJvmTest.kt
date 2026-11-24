package com.xjtu.toolbox.agent

import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 后台写 lastRun 和前台加 / 取消任务同时发生时，取消的任务不能被写回来。 */
class PidaiTaskStoreJvmTest {

    @Test
    fun `一边记运行时间一边加了又取消，最后只剩原来那条`() {
        val root = Files.createTempDirectory("xjtu-pidai").toFile()
        keyValueStoreRootOverride = root
        val pool = Executors.newFixedThreadPool(2)
        try {
            val keep = PidaiTask(id = "keep", prompt = "keep", hour = 8, minute = 0)
            assertTrue(PidaiTaskStore.add(keep))
            val start = CountDownLatch(1)
            pool.execute {
                start.await()
                repeat(500) { PidaiTaskStore.markRun("keep", it.toLong()) }
            }
            pool.execute {
                start.await()
                repeat(200) {
                    val id = "tmp$it"
                    PidaiTaskStore.add(PidaiTask(id = id, prompt = id, hour = 9, minute = 0))
                    PidaiTaskStore.remove(id)
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            assertEquals(listOf("keep"), PidaiTaskStore.all().map { it.id })
            assertEquals(499L, PidaiTaskStore.all().single().lastRun)
        } finally {
            pool.shutdownNow()
            keyValueStoreRootOverride = null
        }
    }
}