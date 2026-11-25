package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomCourseStoreJvmTest {
    @Test
    fun concurrentInsertsPreserveRowsAndUniqueIds() = runBlocking {
        val root = Files.createTempDirectory("custom-courses-test").toFile()
        val previous = keyValueStoreRootOverride
        keyValueStoreRootOverride = root
        try {
            val backing = keyValueStore("custom_courses_concurrency")
            val stores = listOf(KeyValueCustomCourseStore(backing), KeyValueCustomCourseStore(backing))
            val ids = (0 until 32).map { i ->
                async(Dispatchers.Default) {
                    stores[i % 2].insert(CustomCourse(
                        accountId = "account", courseName = "course-$i", weekBits = "1",
                        dayOfWeek = 1, startSection = 1, endSection = 2, termCode = "term",
                    ))
                }
            }.awaitAll()
            val rows = stores.first().getAll("account")
            assertEquals((1L..32L).toSet(), ids.toSet())
            assertEquals((0 until 32).map { "course-$it" }.toSet(), rows.map { it.courseName }.toSet())
            assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        } finally {
            keyValueStoreRootOverride = previous
            root.deleteRecursively()
        }
    }

    @Test
    fun overlappingInsertsDoNotOverwriteAnEarlierSnapshot() = runBlocking {
        val root = Files.createTempDirectory("custom-courses-overlap").toFile()
        val previous = keyValueStoreRootOverride
        keyValueStoreRootOverride = root
        val readStarted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            val backing = keyValueStore("custom_courses_overlap")
            val pauseOnce = AtomicBoolean(true)
            val pausedStore = object : KeyValueStore by backing {
                override fun getString(key: String): String? {
                    val snapshot = backing.getString(key)
                    if (key == KeyValueCustomCourseStore.KEY && pauseOnce.compareAndSet(true, false)) {
                        readStarted.countDown()
                        check(resume.await(15, TimeUnit.SECONDS))
                    }
                    return snapshot
                }
            }
            val first = KeyValueCustomCourseStore(pausedStore)
            val second = KeyValueCustomCourseStore(backing)
            fun course(name: String) = CustomCourse(
                accountId = "account", courseName = name, weekBits = "1",
                dayOfWeek = 1, startSection = 1, endSection = 2, termCode = "term",
            )
            val a = async(Dispatchers.Default) { first.insert(course("first")) }
            val b = try {
                assertTrue(readStarted.await(5, TimeUnit.SECONDS))
                // Start immediately on this thread. Without the shared mutex B finishes
                // using the old snapshot; with it B suspends until A releases the mutex.
                async(start = CoroutineStart.UNDISPATCHED) { second.insert(course("second")) }
            } finally {
                resume.countDown()
            }
            val ids = listOf(a.await(), b.await())
            assertEquals(setOf(1L, 2L), ids.toSet())
            assertEquals(setOf("first", "second"), first.getAll("account").map { it.courseName }.toSet())
        } finally {
            resume.countDown()
            keyValueStoreRootOverride = previous
            root.deleteRecursively()
        }
    }
}
